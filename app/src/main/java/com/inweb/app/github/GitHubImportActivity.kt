package com.inweb.app.github

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.inweb.app.AssetInstaller
import com.inweb.app.R
import com.inweb.app.ui.common.BottomNavHelper
import com.inweb.app.ui.common.PageScaffold
import com.inweb.app.ui.preview.PreviewActivity
import com.inweb.app.util.Prefs
import com.inweb.app.vhost.VirtualHost
import com.inweb.app.vhost.VirtualHostStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/*
 * ════════════════════════════════════════════════════════════════
 *  INWEB — Import a project from GitHub (T1: zipball → site → preview)
 * ════════════════════════════════════════════════════════════════
 *  Sites → ⋮ → "From GitHub"।
 *
 *  টার্গেট নীতি (ইউজার দুটোই চেয়েছে):
 *   ① সাইট সবসময় INWEB-এর নিজস্ব web root-এ নামে — কারণ nginx/apache শুধু
 *      রিয়েল ফাইলসিস্টেম পাথ পড়তে পারে; SAF tree-এর content:// URI থেকে
 *      সার্ভার সরাসরি সার্ভ করতে পারে না।
 *   ② চাইলে ইমপোর্টের পর পুরো প্রজেক্টের এক কপি ইউজার-বাছা ফোল্ডারে
 *      (ফাইল ম্যানেজারে দেখা/এডিট করা যায়) রেখে দেওয়া হয়।
 */
class GitHubImportActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var store: VirtualHostStore
    private lateinit var layout: AssetInstaller.Layout

    private lateinit var input: EditText
    private lateinit var fetchBtn: Button
    private lateinit var importBtn: Button
    private lateinit var info: TextView
    private lateinit var refSpinner: Spinner
    private lateinit var nameInput: EditText
    private lateinit var targetGroup: RadioGroup
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView

    private var info0: GitHubClient.RepoInfo? = null
    private var refs: List<GitHubClient.Ref> = emptyList()
    private var assets: List<GitHubClient.Asset> = emptyList()
    private var pendingTarget: File? = null
    private var bootstrapNote: String? = null

    private val treePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val src = pendingTarget
            if (uri == null || src == null) return@registerForActivityResult
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            lifecycleScope.launch(Dispatchers.IO) {
                val n = runCatching { copyInto(treeDoc(uri), src) }
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@GitHubImportActivity,
                        getString(R.string.gh_copy_done, n), Toast.LENGTH_LONG).show()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_github_import)
        PageScaffold.setup(this, getString(R.string.gh_title)) { finish() }
        BottomNavHelper.attach(this, BottomNavHelper.Tab.MORE)
        prefs = Prefs(this)
        store = VirtualHostStore(this)

        input = findViewById(R.id.ghInput)
        fetchBtn = findViewById(R.id.ghFetchBtn)
        importBtn = findViewById(R.id.ghImportBtn)
        info = findViewById(R.id.ghInfo)
        refSpinner = findViewById(R.id.ghRefSpinner)
        nameInput = findViewById(R.id.ghNameInput)
        targetGroup = findViewById(R.id.ghTargetGroup)
        progress = findViewById(R.id.ghProgress)
        status = findViewById(R.id.ghStatus)

        // AssetInstaller idempotent — শুধু docRoot লাগবে, IO থ্রেডেই চালাই
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { AssetInstaller.install(this@GitHubImportActivity) }
                .onSuccess { withContext(Dispatchers.Main) { layout = it; status.text = getString(R.string.gh_ready) } }
                .onFailure { withContext(Dispatchers.Main) { status.text = it.message } }
        }

        fetchBtn.setOnClickListener { doFetch() }
        importBtn.setOnClickListener { doImport() }
        input.setText(intent.getStringExtra(EXTRA_INPUT).orEmpty())
        if (input.text.isNotEmpty()) doFetch()
    }

    /* ─────────────────────────── fetch ─────────────────────────── */

    private fun doFetch() {
        val repo = GitHubClient.parseRepo(input.text.toString())
        if (repo == null) {
            Toast.makeText(this, R.string.gh_bad_input, Toast.LENGTH_LONG).show(); return
        }
        busy(true, getString(R.string.gh_fetching))
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val i = GitHubClient.repoInfo(repo)
                i to (GitHubClient.refs(i.repo, i.defaultBranch) to GitHubClient.releaseAssets(i.repo))
            }
            withContext(Dispatchers.Main) {
                busy(false, "")
                result.onSuccess { (i, pair) ->
                    info0 = i; refs = pair.first; assets = pair.second
                    info.text = getString(
                        R.string.gh_info, i.repo.full, i.defaultBranch,
                        i.sizeKb / 1024, i.language, i.license)
                    val labels = refs.map { it.toString() } + assets.map { it.toString() }
                    refSpinner.adapter = ArrayAdapter(
                        this@GitHubImportActivity, android.R.layout.simple_spinner_dropdown_item, labels)
                    GitHubClient.parseRef(input.text.toString())?.let { want ->
                        val idx = labels.indexOfFirst { it.contains(want) }
                        if (idx >= 0) refSpinner.setSelection(idx)
                    }
                    if (nameInput.text.isEmpty()) nameInput.setText(GitHubClient.safeName(i.repo.name, "site"))
                    importBtn.isEnabled = true
                }.onFailure {
                    info.text = it.message ?: "?"
                    importBtn.isEnabled = false
                }
            }
        }
    }

    /* ─────────────────────────── import ─────────────────────────── */

    private fun doImport() {
        val i0 = info0 ?: return Toast.makeText(this, R.string.gh_fetch_first, Toast.LENGTH_SHORT).show()
        if (!::layout.isInitialized) return
        val pos = refSpinner.selectedItemPosition
        val chosenRef: String
        val releaseUrl: String?
        if (pos in refs.indices) { chosenRef = refs[pos].name; releaseUrl = null }
        else {
            val a = assets.getOrNull(pos - refs.size)
            if (a == null) { Toast.makeText(this, R.string.gh_fetch_first, Toast.LENGTH_SHORT).show(); return }
            chosenRef = a.label.substringAfter(": ").trim()
            releaseUrl = a.url.ifBlank { null }
        }
        val name = GitHubClient.safeName(nameInput.text.toString(), i0.repo.name)
        if (name.isBlank() || name.startsWith(".")) {
            Toast.makeText(this, R.string.gh_bad_name, Toast.LENGTH_LONG).show(); return
        }
        val target = File(layout.docRoot, name)
        if (target.exists()) {
            Toast.makeText(this, getString(R.string.gh_folder_exists, name), Toast.LENGTH_LONG).show(); return
        }
        val copyOut = targetGroup.checkedRadioButtonId == R.id.ghTargetCopy

        busy(true, getString(R.string.gh_downloading, 0))
        importBtn.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            val r = runCatching {
                GitHubClient.importTo(i0.repo, chosenRef, target, cacheDir, releaseUrl) { pct ->
                    runOnUiThread {
                        progress.progress = pct
                        status.text = getString(R.string.gh_downloading, pct)
                    }
                }
            }
            withContext(Dispatchers.Main) {
                busy(false, "")
                importBtn.isEnabled = true
                r.onSuccess { o ->
                    val vh = VirtualHost(
                        serverName = name,
                        documentRoot = o.target.absolutePath,
                        phpMode = if (o.isStatic) VirtualHost.PhpMode.STATIC else VirtualHost.PhpMode.AUTO,
                        label = "${i0.repo.name} · GitHub"
                    )
                    store.upsert(vh)
                    // 🌱 WordPress হলে DB + wp-config.php তৈরি করে দিই
                    val extra = runCatching { SiteBootstrap.prepare(o.target, layout, prefs) }
                        .onFailure { Log.w("GitHubImport", "bootstrap failed", it) }
                        .getOrNull()
                    status.text = getString(R.string.gh_done, o.fileCount, human(o.bytes), o.framework)
                    if (!extra.isNullOrBlank()) bootstrapNote = extra
                    pendingTarget = o.target
                    if (copyOut) treePicker.launch(null) else showResult(o, name)
                }.onFailure {
                    status.text = it.message ?: "failed"
                    Toast.makeText(this@GitHubImportActivity, R.string.gh_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showResult(o: GitHubClient.Outcome, host: String) {
        val url = "http://$host:${prefs.httpPort}/"
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.gh_done_title)
            .setMessage(
                getString(R.string.gh_done_body, o.framework, host, if (o.isStatic)
                    getString(R.string.gh_static_note) else getString(R.string.gh_php_note))
                + (bootstrapNote?.let { "\n\n" + it } ?: ""))
            .setPositiveButton(R.string.gh_open_preview) { _, _ -> PreviewActivity.open(this, url) }
            .setNeutralButton(R.string.gh_copy_out) { _, _ -> treePicker.launch(null) }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    /* ─────────────────────────── SAF কপি-আউট ─────────────────────────── */

    private fun treeDoc(tree: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    /** tree-এ ফোল্ডার/ফাইল তৈরি করে রিকার্সিভ কপি; @return কপি হওয়া ফাইল সংখ্যা */
    private fun copyInto(parent: Uri, src: File): Int {
        var n = 0
        if (src.isDirectory) {
            val dirUri = runCatching {
                DocumentsContract.createDocument(
                    contentResolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, src.name)
            }.getOrNull() ?: parent
            src.listFiles()?.forEach { n += copyInto(dirUri, it) }
            return n
        }
        val mime = when {
            src.name.endsWith(".png") || src.name.endsWith(".jpg") -> "image/*"
            src.name.endsWith(".css") -> "text/css"
            src.name.endsWith(".js") -> "text/javascript"
            src.name.endsWith(".html") -> "text/html"
            src.name.endsWith(".php") -> "text/php"
            else -> "text/plain"
        }
        val dest = runCatching { DocumentsContract.createDocument(contentResolver, parent, mime, src.name) }
            .getOrNull() ?: return 0
        runCatching {
            contentResolver.openOutputStream(dest)?.use { out -> src.inputStream().use { it.copyTo(out) } }
        }
        return n + 1
    }

    /* ─────────────────────────── ছোট হেল্পার ─────────────────────────── */

    private fun busy(b: Boolean, msg: String) {
        progress.visibility = if (b) View.VISIBLE else View.GONE
        fetchBtn.isEnabled = !b
        if (b) status.text = msg
    }

    private fun human(bytes: Long): String = when {
        bytes > 1024 * 1024 -> "%.1f MB".format(bytes / 1048576.0)
        else -> "%.0f KB".format(bytes / 1024.0)
    }

    companion object {
        const val EXTRA_INPUT = "gh_input"
    }
}
