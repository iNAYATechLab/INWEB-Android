package com.inweb.app.github

import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/*
 * ════════════════════════════════════════════════════════════════
 *  INWEB — GitHub client (repo → zip → local site)
 * ════════════════════════════════════════════════════════════════
 *  লক্ষ্য: ফোনেই `github.com/<owner>/<repo>` থেকে প্রজেক্ট নামিয়ে INWEB-এর
 *  web root-এ বসিয়ে দেওয়া — কোনো laptop / FTP / cPanel ছাড়া।
 *
 *  ডিজাইন সিদ্ধান্ত (কেন zipball, `git clone` না):
 *   • `git` বাইনারি বান্ডল করলে APK-তে +4.3 MB (মাপা)। আর runtime-এ নামানো
 *     বাইনারি Android 10+ এ exec-ই করা যায় না (মাপা: `error=13, Permission denied`)।
 *     zipball → শুধু ডাটা নামে, কোনো এক্সিকিউটেবল লাগে না → শূন্য নতুন বাইনারি।
 *   • api.github.com/repos/{o}/{r}/zipball/{ref} → codeload → zip
 *     (মাপা: bootstrap `main` = 8.45 MB / 0.83 s, laravel `13.x` = 55 KB / 0.43 s)
 *
 *  সীমাবদ্ধতা (জেনেই এগোওয়া দরকার):
 *   • আনঅথেন্টিকেটেড api.github.com = ৬০ req/ঘণ্টা/IP → API কল ন্যূনতম;
 *     zipball (codeload) সেই লিমিটে পড়ে না।
 *   • প্রাইভেট repo T1-এ সাপোর্টেড না।
 *   • zip-slip → প্রতিটা এন্ট্রি canonicalPath দিয়ে যাচাই, সাইজ/এন্ট্রি ক্যাপ।
 */
object GitHubClient {

    private const val TAG = "GitHubClient"
    private const val API = "https://api.github.com"
    private const val UA = "INWEB-Android (+https://github.com/iNAYATechLab/INWEB-Android)"

    /** পাগল repo থেকে বাঁচার ছাদ */
    const val MAX_ENTRIES = 20000
    const val MAX_BYTES = 300L * 1024 * 1024
    const val MAX_MB = 300

    /* ─────────────────────────── মডেল ─────────────────────────── */

    data class Repo(val owner: String, val name: String) {
        val full: String get() = "$owner/$name"
    }

    data class RepoInfo(
        val repo: Repo,
        val defaultBranch: String,
        val sizeKb: Long,
        val language: String,
        val license: String,
        val archived: Boolean
    )

    enum class RefKind { BRANCH, TAG, RELEASE }
    data class Ref(val name: String, val kind: RefKind) {
        override fun toString(): String = when (kind) {
            RefKind.BRANCH -> "⑂ $name"
            RefKind.TAG -> "# $name"
            RefKind.RELEASE -> "📦 $name"
        }
    }

    data class Asset(val label: String, val url: String, val sizeBytes: Long) {
        override fun toString(): String =
            if (url.isEmpty()) label else "$label  (${(sizeBytes / 1024).coerceAtLeast(1)} KB)"
    }

    class GitHubError(message: String) : IOException(message)

    data class ExtractResult(val files: Int, val bytes: Long)

    /** ইমপোর্ট সম্পন্ন হলে যা ফেরত পাওয়া যায় */
    data class Outcome(
        val target: File,
        val fileCount: Int,
        val bytes: Long,
        val framework: String,
        val isStatic: Boolean
    )

    /* ─────────────────────────── ইনপুট পার্স ─────────────────────────── */

    /** `owner/repo` · `github.com/owner/repo` · `…/tree/<branch>` · `…/releases` */
    fun parseRepo(input: String): Repo? {
        var s = input.trim().trimEnd('/')
        if (s.isEmpty()) return null
        s = s.removePrefix("https://").removePrefix("http://")
            .removePrefix("www.github.com").removePrefix("github.com")
            .removeSuffix(".git")
        val parts = s.split('/').filter { it.isNotBlank() }
        if (parts.size < 2) return null
        val owner = parts[0]
        val name = parts[1]
        val ok = Regex("^[A-Za-z0-9._-]+$")
        if (!owner.matches(ok) || !name.matches(ok)) return null
        return Repo(owner, name)
    }

    /** `…/tree/<branch>` বা `…/tag/<tag>` থেকে রিফ */
    fun parseRef(input: String): String? {
        val seg = input.split('/')
        val i = seg.indexOfFirst { it == "tree" || it == "tag" }
        return if (i >= 0 && i + 1 < seg.size) seg[i + 1] else null
    }

    /** ফোল্ডার/সার্ভার-নাম স্যানিটাইজ: শুধু a–z 0–9 - _ */
    fun safeName(raw: String, fallback: String): String {
        val n = raw.trim().lowercase()
            .replace(Regex("[^a-z0-9_-]+"), "-")
            .trim('-', '.')
        return if (n.length >= 1) n else fallback
    }

    /* ─────────────────────────── HTTP ─────────────────────────── */

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }

    private fun HttpURLConnection.where(): String = url?.toString()?.substringAfter("github.com") ?: "?"

    /** রিডাইরেক্ট ম্যানুয়ালি ফলো করি (codeload) — instanceFollowRedirects=false */
    private fun jsonGet(path: String): String {
        var target = if (path.startsWith("http")) path else API + path
        repeat(5) {
            val c = open(target)
            try {
                when (c.responseCode) {
                    200 -> return c.inputStream.bufferedReader().use { it.readText() }
                    in 300..308 -> {
                        val loc = c.getHeaderField("Location")
                            ?: throw GitHubError("redirect without Location")
                        target = if (loc.startsWith("/")) API + loc else loc
                    }
                    401 -> throw GitHubError("এই repo-তে লগইন লাগে (প্রাইভেট repo এখন সাপোর্টড না)")
                    403, 429 -> throw GitHubError(
                        "GitHub API লিমিট (৬০ req/ঘণ্টা) — একটু পরে চেষ্টা করুন, " +
                        "অথবা ব্রাঞ্চ/ট্যাগের নাম নিজে লিখে দিন")
                    404 -> throw GitHubError("পাওয়া যায়নি: ${c.where()}")
                    else -> throw GitHubError("HTTP ${c.responseCode}")
                }
            } finally { c.disconnect() }
        }
        throw GitHubError("অনেকবার রিডাইরেক্ট — থামানো হলো")
    }

    /* ─────────────────────────── API র‍্যাপার ─────────────────────────── */

    fun repoInfo(repo: Repo): RepoInfo {
        val o = JSONObject(jsonGet("/repos/${repo.owner}/${repo.name}"))
        val lic = o.optJSONObject("license")?.optString("spdx_id").orEmpty()
        return RepoInfo(
            repo = repo,
            defaultBranch = o.optString("default_branch", "main"),
            sizeKb = o.optLong("size"),
            language = o.optString("language", "—").ifBlank { "—" },
            license = lic.ifBlank { "—" },
            archived = o.optBoolean("archived", false)
        )
    }

    /** default branch + ব্রাঞ্চ + ট্যাগ (১০০+১০০) */
    fun refs(repo: Repo, defaultBranch: String): List<Ref> {
        val out = ArrayList<Ref>()
        out += Ref(defaultBranch, RefKind.BRANCH)
        listOf("branches" to RefKind.BRANCH, "tags" to RefKind.TAG).forEach { (ep, kind) ->
            runCatching {
                val a = JSONArray(jsonGet("/repos/${repo.owner}/${repo.name}/$ep?per_page=100"))
                for (i in 0 until a.length()) {
                    val n = a.getJSONObject(i).optString("name")
                    if (n.isNotBlank() && out.none { it.name == n }) out += Ref(n, kind)
                }
            }.onFailure { Log.w(TAG, "$ep: ${it.message}") }
        }
        return out
    }

    /** রিলিজ ট্যাগ + .zip আর্টেফ্যাক্ট (প্রি-বিল্ট ডিস্ট্রো ডিপ্লয়ের জন্য) */
    fun releaseAssets(repo: Repo): List<Asset> {
        val out = ArrayList<Asset>()
        runCatching {
            val a = JSONArray(jsonGet("/repos/${repo.owner}/${repo.name}/releases?per_page=5"))
            for (i in 0 until a.length()) {
                val rel = a.getJSONObject(i)
                val tag = rel.optString("tag_name")
                if (tag.isNotBlank() && out.none { it.label == "release: $tag" })
                    out += Asset("release: $tag", zipballUrl(repo, tag), 0)
                rel.optJSONArray("assets")?.let { arr ->
                    for (j in 0 until arr.length()) {
                        val as = arr.getJSONObject(j)
                        val name = as.optString("name")
                        val url = as.optString("browser_download_url")
                        if (url.isNotBlank() && name.endsWith(".zip", ignoreCase = true))
                            out += Asset(name, url, as.optLong("size"))
                    }
                }
            }
        }.onFailure { Log.w(TAG, "releases: ${it.message}") }
        return out
    }

    fun zipballUrl(repo: Repo, ref: String): String =
        "$API/repos/${repo.owner}/${repo.name}/zipball/${Uri.encode(ref)}"

    /* ─────────────────────── ডাউনলোড + এক্সট্রাকশন ─────────────────────── */

    fun download(url: String, dest: File, onProgress: (Int) -> Unit): Long {
        var target = url
        repeat(5) {
            val c = open(target)
            try {
                when (c.responseCode) {
                    200 -> {
                        val total = c.contentLengthLong
                        dest.parentFile?.mkdirs()
                        c.inputStream.use { input ->
                            FileOutputStream(dest).use { out ->
                                val buf = ByteArray(64 * 1024)
                                var got = 0L
                                while (true) {
                                    val n = input.read(buf)
                                    if (n <= 0) break
                                    out.write(buf, 0, n)
                                    got += n
                                    if (got > MAX_BYTES)
                                        throw GitHubError("ডাউনলোড $MAX_MB MB ছাড়িয়ে গেছে — ছোট repo/রিফ নিন")
                                    if (total > 0) onProgress(((got * 100) / total).toInt().coerceIn(0, 99))
                                }
                            }
                        }
                        onProgress(100)
                        return dest.length()
                    }
                    in 300..308 -> {
                        val loc = c.getHeaderField("Location")
                            ?: throw GitHubError("redirect missing")
                        target = if (loc.startsWith("/")) "https://github.com$loc" else loc
                    }
                    404 -> throw GitHubError("রিফ পাওয়া যায়নি — ব্রাঞ্চ/ট্যাগ নাম মিলিয়ে দেখুন")
                    403, 429 -> throw GitHubError("ডাউনলোড লিমিট — একটু পরে আবার চেষ্টা করুন")
                    else -> throw GitHubError("HTTP ${c.responseCode}")
                }
            } finally { c.disconnect() }
        }
        throw GitHubError("অনেকবার রিডাইরেক্ট")
    }

    fun extractZip(zip: File, target: File): ExtractResult {
        target.mkdirs()
        var count = 0
        var bytes = 0L
        ZipInputStream(BufferedInputStream(FileInputStream(zip))).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                if (++count > MAX_ENTRIES) throw GitHubError("ফাইল সংখ্যা খুব বেশি (>$MAX_ENTRIES)")
                val name = e.name
                if (name.contains("..") || name.startsWith("/") || name.contains(":\\")) {
                    Log.w(TAG, "সন্দেহজনক এন্ট্রি বাদ: $name"); continue
                }
                val outFile = File(target, name)
                if (!e.isDirectory) {
                    val ct = target.canonicalPath
                    val cp = File(target, name).canonicalPath
                    if (cp != ct && !cp.startsWith(ct + File.separator)) {
                        Log.w(TAG, "zip-slip আটকানো: $name"); continue
                    }
                }
                if (e.isDirectory) { outFile.mkdirs(); continue }
                outFile.parentFile?.mkdirs()
                FileOutputStream(outFile).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = zin.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        bytes += n
                        if (bytes > MAX_BYTES) throw GitHubError("আনপ্যাকড সাইজ $MAX_MB MB ছাড়িয়ে গেছে")
                    }
                }
            }
        }
        return ExtractResult(count, bytes)
    }

    /** GitHub zipball-এর `owner-repo-sha/` র‍্যাপার ফোল্ডার সরায় */
    fun flattenSingleRoot(dir: File) {
        val kids = dir.listFiles() ?: return
        if (kids.size != 1 || !kids[0].isDirectory) return
        val inner = kids[0]
        inner.listFiles()?.forEach { child ->
            val moved = File(dir, child.name)
            if (!child.renameTo(moved)) {
                child.copyTo(moved, overwrite = true)
                child.deleteRecursively()
            }
        }
        inner.delete()
    }

    /** রুট দেখে ফ্রেমওয়ার্ক + static কিনা */
    fun detectFramework(dir: File): Pair<String, Boolean> {
        fun has(n: String) = File(dir, n).exists()
        return when {
            has("wp-config.php") || (has("wp-login.php") && has("wp-includes")) -> "WordPress" to false
            has("artisan") && has("composer.json") -> "Laravel" to false
            has("composer.json") -> "PHP (composer)" to false
            has("index.php") -> "PHP" to false
            has("package.json") -> "Node.js" to true
            has("index.html") || has("index.htm") -> "Static site" to true
            else -> "Unknown" to true
        }
    }

    /**
     * পুরো ফ্লো (ব্যাকগ্রাউন্ড থ্রেড থেকে কল করো):
     * zipball নামাও → cache-এ আনপ্যাক → flatten → [target]-এ সরকাও → ডিটেক্ট
     */
    @Throws(GitHubError::class)
    fun importTo(
        repo: Repo,
        ref: String,
        target: File,
        cacheDir: File,
        useReleaseUrl: String? = null,
        onProgress: (Int) -> Unit
    ): Outcome {
        if (target.exists()) throw GitHubError("ফোল্ডার আগে থেকেই আছে: ${target.name}")
        val cache = File(cacheDir, "gh-import").apply { mkdirs() }
        val zip = File(cache, "${repo.name}-${ref.hashCode()}.zip")
        val stage = File(cache, "stage")
        try {
            download(useReleaseUrl ?: zipballUrl(repo, ref), zip, onProgress)
            stage.deleteRecursively()
            val r = extractZip(zip, stage)
            flattenSingleRoot(stage)
            if (!stage.renameTo(target)) stage.copyRecursively(target, overwrite = false)
            target.mkdirs()
            val (fw, static) = detectFramework(target)
            return Outcome(target, r.files, r.bytes, fw, static)
        } catch (e: GitHubError) {
            target.deleteRecursively()      // অসম্পূর্ণ সাইট রেখে দিও না
            throw e
        } catch (e: Exception) {
            target.deleteRecursively()
            throw GitHubError(e.message ?: "ইমপোর্ট ব্যর্থ")
        } finally {
            zip.delete(); stage.deleteRecursively(); cache.deleteRecursively()
        }
    }
}
