package com.inweb.app.framework

import android.content.Context
import android.util.Log
import com.inweb.app.AssetInstaller
import com.inweb.app.Constants
import com.inweb.app.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Downloads a [FrameworkTemplate] ZIP, extracts it under the web root,
 * flattens the (usually top-level) archive folder, and runs any post-install
 * hooks.
 *
 * Emits progress via a callback so the UI can render a determinate bar.
 *
 * MUST be invoked from a background dispatcher.
 */
class FrameworkInstaller(private val context: Context) {

    fun interface Progress { fun onProgress(step: Step, pct: Int, message: String) }

    enum class Step { PREPARING, DOWNLOADING, EXTRACTING, CONFIGURING, DONE }

    /**
     * @return the absolute path of the installed framework's root folder.
     */
    suspend fun install(
        template: FrameworkTemplate,
        progress: Progress = Progress { _, _, _ -> }
    ): File = withContext(Dispatchers.IO) {

        val extRoot = context.getExternalFilesDir(null) ?: context.filesDir
        val docRoot = File(extRoot, Constants.WWW_DIR).apply { mkdirs() }
        val target  = File(docRoot, template.targetSubdir)

        progress.onProgress(Step.PREPARING, 0, "Preparing…")
        // Nuke any prior install so we don't merge files.
        if (target.exists()) target.deleteRecursively()
        target.mkdirs()

        if (template.downloadUrl.isBlank()) {
            // Built-in template — generate files locally, skip download & extract.
            progress.onProgress(Step.EXTRACTING, 50, "Creating files…")
            generateBuiltIn(template, target)
        } else {
            val cacheZip = File(context.cacheDir, "fw_${template.id}.zip")
            downloadWithProgress(template.downloadUrl, cacheZip, progress)
            extractZip(cacheZip, target, progress)
            runCatching { cacheZip.delete() }
            flattenSingleRootFolder(target)
        }

        progress.onProgress(Step.CONFIGURING, 95, "Configuring…")
        runPostInstall(template, target)

        progress.onProgress(Step.DONE, 100, "Installed!")
        target
    }

    /* ---------------------------------------------------------------- */
    /*  Download                                                         */
    /* ---------------------------------------------------------------- */

    private fun downloadWithProgress(url: String, dest: File, progress: Progress) {
        Log.i(TAG, "Downloading $url → ${dest.absolutePath}")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout    = 30_000
            instanceFollowRedirects = true
            requestMethod  = "GET"
            setRequestProperty("User-Agent", "INWEB/1.0 (Android)")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) error("HTTP $code while downloading $url")
            val totalBytes = conn.contentLengthLong.coerceAtLeast(1L)
            conn.inputStream.use { input ->
                BufferedOutputStream(FileOutputStream(dest)).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int; var written = 0L; var lastPct = -1
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read); written += read
                        val pct = ((written * 100 / totalBytes).toInt()).coerceIn(0, 99)
                        if (pct != lastPct) {
                            lastPct = pct
                            progress.onProgress(
                                Step.DOWNLOADING, pct,
                                "Downloading ${humanBytes(written)} / ${humanBytes(totalBytes)}"
                            )
                        }
                    }
                    output.flush()
                }
            }
        } finally { conn.disconnect() }
    }

    /* ---------------------------------------------------------------- */
    /*  Extract                                                          */
    /* ---------------------------------------------------------------- */

    private fun extractZip(zip: File, target: File, progress: Progress) {
        Log.i(TAG, "Extracting ${zip.name} → ${target.absolutePath}")
        val total = countEntries(zip).coerceAtLeast(1)
        var seen  = 0

        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val outFile = safeChildFile(target, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        zis.copyTo(fos)
                    }
                }
                seen++
                val pct = (seen * 100 / total).coerceIn(0, 99)
                progress.onProgress(Step.EXTRACTING, pct, "Extracting… ($seen / $total)")
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /** Guards against zip-slip: refuses paths that escape [target]. */
    private fun safeChildFile(target: File, name: String): File {
        val child = File(target, name)
        val childPath  = child.canonicalPath
        val targetPath = target.canonicalPath
        require(childPath == targetPath || childPath.startsWith(targetPath + File.separator)) {
            "Refusing zip entry that escapes target: $name"
        }
        return child
    }

    private fun countEntries(zip: File): Int {
        var n = 0
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (zis.nextEntry != null) { n++; zis.closeEntry() }
        }
        return n
    }

    /**
     * WordPress zip contains `wordpress/…`, Laravel contains `laravel-11.x/…`,
     * Bootstrap contains `startbootstrap-landing-page-master/…`, etc.
     * If [target] has exactly one child directory (and nothing else), lift its
     * contents up one level so users don't see `wordpress/wordpress/wp-admin`.
     */
    private fun flattenSingleRootFolder(target: File) {
        val kids = target.listFiles().orEmpty()
        if (kids.size != 1 || !kids[0].isDirectory) return
        val root = kids[0]
        root.listFiles()?.forEach { it.renameTo(File(target, it.name)) }
        root.delete()
    }

    /* ---------------------------------------------------------------- */
    /*  Post-install                                                     */
    /* ---------------------------------------------------------------- */

    private fun runPostInstall(template: FrameworkTemplate, target: File) {
        when (template.postInstall) {
            FrameworkTemplate.PostInstall.NONE                -> Unit
            FrameworkTemplate.PostInstall.WORDPRESS_WP_CONFIG -> renderWordPressConfig(target)
            FrameworkTemplate.PostInstall.LARAVEL_ENV_KEY     -> renderLaravelEnv(target)
        }
    }

    private fun renderWordPressConfig(target: File) {
        val sample = File(target, "wp-config-sample.php")
        val out    = File(target, "wp-config.php")
        if (!sample.exists() || out.exists()) return
        val prefs = Prefs(context)
        val salts = (1..8).joinToString("\n") {
            val key = arrayOf(
                "AUTH_KEY", "SECURE_AUTH_KEY", "LOGGED_IN_KEY", "NONCE_KEY",
                "AUTH_SALT", "SECURE_AUTH_SALT", "LOGGED_IN_SALT", "NONCE_SALT"
            )[it - 1]
            "define('$key', '${randomString(64)}');"
        }
        var text = sample.readText()
        text = text
            .replace("database_name_here", "inweb_wp")
            .replace("username_here",      "root")
            .replace("password_here",       prefs.mysqlRootPassword)
            .replace("localhost", "127.0.0.1:${prefs.mysqlPort}")
        // Inject salts by replacing the whole salt block.
        val saltMarker = "'put your unique phrase here'"
        text = text.split("\n").joinToString("\n") { line ->
            if (line.contains(saltMarker)) "" else line
        }
        text = text.replace(
            "/**#@-*/",
            "$salts\n\n/**#@-*/"
        )
        out.writeText(text)
        Log.i(TAG, "WordPress wp-config.php rendered.")
    }

    private fun renderLaravelEnv(target: File) {
        val example = File(target, ".env.example")
        val env     = File(target, ".env")
        if (example.exists() && !env.exists()) {
            var text = example.readText()
            // Generate base64 32-byte APP_KEY.
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            val key   = "base64:" + android.util.Base64.encodeToString(
                bytes, android.util.Base64.NO_WRAP
            )
            text = text.replace(Regex("^APP_KEY=.*$", RegexOption.MULTILINE), "APP_KEY=$key")
            env.writeText(text)
            Log.i(TAG, "Laravel .env generated with random APP_KEY.")
        }
    }

    /* ---------------------------------------------------------------- */
    /*  Built-in templates                                              */
    /* ---------------------------------------------------------------- */

    private fun generateBuiltIn(template: FrameworkTemplate, target: File) {
        when (template.id) {
            "phpinfo_sandbox" -> {
                File(target, "index.php").writeText("""
                    <!doctype html>
                    <html><head><meta charset="utf-8"><title>PHP Playground · INWEB</title>
                    <style>body{font-family:system-ui;padding:2rem;max-width:640px;margin:auto;background:#0B1410;color:#F5F7FA}
                    a{color:#14B8A6}pre{background:#132821;padding:1rem;border-radius:8px;overflow:auto}</style></head>
                    <body>
                    <h1>🐘 PHP Playground</h1>
                    <p>Server time: <strong><?= date('r') ?></strong></p>
                    <p>PHP version: <strong><?= phpversion() ?></strong></p>
                    <p>Try:</p>
                    <ul>
                        <li><a href="info.php">phpinfo()</a></li>
                        <li><a href="form.php">Form demo</a></li>
                    </ul>
                    </body></html>
                """.trimIndent())
                File(target, "info.php").writeText("<?php phpinfo(); ?>\n")
                File(target, "form.php").writeText("""
                    <?php
                    ${'$'}name = ${'$'}_POST['name'] ?? '';
                    ?>
                    <!doctype html><html><head><meta charset="utf-8"><title>Form demo</title>
                    <style>body{font-family:system-ui;padding:2rem;background:#0B1410;color:#F5F7FA}
                    input,button{padding:.5rem;font-size:1rem}</style></head>
                    <body>
                    <h1>Hello, <?= htmlspecialchars(${'$'}name ?: 'stranger') ?>!</h1>
                    <form method="post">
                      <input name="name" placeholder="Your name" required>
                      <button>Greet me</button>
                    </form>
                    </body></html>
                """.trimIndent())
            }
        }
    }

    /* ---------------------------------------------------------------- */
    /*  Helpers                                                          */
    /* ---------------------------------------------------------------- */

    private fun humanBytes(b: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var v = b.toDouble(); var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return "%.1f %s".format(v, units[i])
    }

    private fun randomString(len: Int): String {
        val alphabet = ("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz" +
                        "0123456789!@#\$%^&*()-_+=[]{}").toCharArray()
        val rnd = SecureRandom()
        return CharArray(len) { alphabet[rnd.nextInt(alphabet.size)] }
            .concatToString().replace("'", "*")   // keep out of single-quoted strings
    }

    companion object { private const val TAG = "FrameworkInstaller" }
}
