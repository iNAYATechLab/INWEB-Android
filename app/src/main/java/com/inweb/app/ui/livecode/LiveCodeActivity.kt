package com.inweb.app.ui.livecode

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.inweb.app.Constants
import com.inweb.app.R
import com.inweb.app.ui.common.BottomNavHelper
import com.inweb.app.ui.editor.CodeEditorView
import com.inweb.app.ui.editor.highlight.Language
import com.inweb.app.ui.editor.highlight.SyntaxHighlighter
import com.inweb.app.ui.editor.themes.EditorTheme
import com.inweb.app.util.FileUtils
import com.inweb.app.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The signature INWEB "LIVE CODE EDITOR & PREVIEW" screen.
 *
 * Layout mirrors the reference mockup:
 *
 *   ┌──────────────────────────────────────┐
 *   │ ← LIVE CODE EDITOR & PREVIEW         │  ← Header
 *   │                                      │
 *   │  ┌─────────────────────────────────┐ │
 *   │  │ 👁 Preview  │  <> Code  (toggle)│ │
 *   │  └─────────────────────────────────┘ │
 *   │                                      │
 *   │  ┌─────────────────────────────────┐ │
 *   │  │ 📄 styles.css        ✎  ⋯       │ │  ← File tab
 *   │  ├─────────────────────────────────┤ │
 *   │  │ 1  /* Global Reset */            │ │
 *   │  │ 2  * {                           │ │  ← Code with
 *   │  │ 3    margin: 0;                  │ │    line numbers
 *   │  │ …                                │ │
 *   │  └─────────────────────────────────┘ │
 *   │                                      │
 *   │  ┌──────────────────────────────┐    │
 *   │  │ Bottom nav                   │    │
 *   │  └──────────────────────────────┘    │
 *   └──────────────────────────────────────┘
 *
 * Behaviour:
 *   – Loads the file passed via EXTRA_PATH.
 *   – "Preview" tab shows the file in a WebView (mapped to its localhost URL).
 *   – "Code" tab shows the CodeEditorView with syntax highlighting.
 *   – Auto-saves on tab switch, so Preview always shows fresh output.
 *   – Uses the existing LiveReload pipeline: saves trigger reloads inside
 *     the preview WebView automatically.
 */
class LiveCodeActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var file: File
    private lateinit var language: Language
    private lateinit var theme: EditorTheme
    private lateinit var highlighter: SyntaxHighlighter

    // Toggle
    private lateinit var toggleCode: TextView
    private lateinit var togglePreview: TextView

    // File tab
    private lateinit var fileNameLabel: TextView
    private lateinit var renameBtn: ImageButton
    private lateinit var moreBtn: ImageButton

    // Code container
    private lateinit var codeCard: View
    private lateinit var editor: CodeEditorView
    private lateinit var status: TextView

    // Preview container
    private lateinit var previewCard: View
    private lateinit var webView: WebView

    private var originalText: String = ""
    private var dirty: Boolean = false
    private var highlightJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live_code)
        BottomNavHelper.attach(this, BottomNavHelper.Tab.MORE)
        prefs = Prefs(this)

        val path = intent.getStringExtra(EXTRA_PATH)
        if (path.isNullOrBlank()) { finish(); return }
        file = File(path)
        if (!file.exists() || !file.isFile) {
            Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show()
            finish(); return
        }

        bindViews()
        setupHeader()
        setupToggle()
        setupEditor()
        setupPreview()

        showCode()        // default view = Code (matches mockup's "Preview" idle look)
        loadFile()
    }

    /* ---------------------------------------------------------------- */
    /*  View binding                                                     */
    /* ---------------------------------------------------------------- */

    private fun bindViews() {
        toggleCode    = findViewById(R.id.toggleCode)
        togglePreview = findViewById(R.id.togglePreview)
        fileNameLabel = findViewById(R.id.fileName)
        renameBtn     = findViewById(R.id.renameBtn)
        moreBtn       = findViewById(R.id.moreBtn)

        codeCard      = findViewById(R.id.codeCard)
        editor        = findViewById(R.id.editor)
        status        = findViewById(R.id.status)

        previewCard   = findViewById(R.id.previewCard)
        webView       = findViewById(R.id.webView)
    }

    private fun setupHeader() {
        findViewById<ImageButton>(R.id.headerBack).setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        renameBtn.setOnClickListener { promptRename() }
        moreBtn.setOnClickListener   { showMoreSheet() }
    }

    /* ---------------------------------------------------------------- */
    /*  Segmented toggle                                                 */
    /* ---------------------------------------------------------------- */

    private fun setupToggle() {
        toggleCode.setOnClickListener    { showCode() }
        togglePreview.setOnClickListener { showPreview() }
    }

    private fun showCode() {
        codeCard.visibility    = View.VISIBLE
        previewCard.visibility = View.GONE
        activateTab(toggleCode, togglePreview)
    }

    private fun showPreview() {
        // Save first so the preview always reflects on-disk state.
        if (dirty) save()
        codeCard.visibility    = View.GONE
        previewCard.visibility = View.VISIBLE
        activateTab(togglePreview, toggleCode)
        loadPreview()
    }

    private fun activateTab(active: TextView, inactive: TextView) {
        val activeBg   = ContextCompat.getColor(this, R.color.surface)
        val inactiveBg = 0x00000000
        val accentText = ContextCompat.getColor(this, R.color.text_primary)
        val mutedText  = ContextCompat.getColor(this, R.color.text_secondary)

        active.setBackgroundResource(R.drawable.toggle_tab_active)
        inactive.setBackgroundColor(inactiveBg)
        active.setTextColor(accentText)
        inactive.setTextColor(mutedText)
        active.setTypeface(active.typeface, Typeface.BOLD)
        inactive.setTypeface(Typeface.DEFAULT, Typeface.NORMAL)
    }

    /* ---------------------------------------------------------------- */
    /*  Code editor                                                      */
    /* ---------------------------------------------------------------- */

    private fun setupEditor() {
        theme       = EditorTheme.byId(prefs.editorThemeId)
        language    = Language.fromFilename(file.name)
        highlighter = SyntaxHighlighter(language, theme)

        editor.applyTheme(theme)
        editor.setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.editorFontSize.toFloat())
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                markDirty(s?.toString() != originalText)
                scheduleHighlight()
            }
        })

        fileNameLabel.text = file.name
    }

    private fun loadFile() {
        status.text = "Loading…"
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    if (file.length() > FileUtils.MAX_EDIT_BYTES)
                        error("File too large (>${FileUtils.humanSize(FileUtils.MAX_EDIT_BYTES)})")
                    file.readText()
                }.getOrDefault("")
            }
            originalText = text
            editor.setText(text)
            markDirty(false)
            status.text = "${language.displayName} · ${text.lines().size} lines · ${text.length} chars"
            highlighter.apply(editor.text!!)
        }
    }

    private fun save(finishAfter: Boolean = false) {
        val text = editor.text.toString()
        status.text = "Saving…"
        lifecycleScope.launch {
            val err = withContext(Dispatchers.IO) {
                runCatching { file.writeText(text) }.exceptionOrNull()?.message
            }
            if (err != null) {
                Toast.makeText(this@LiveCodeActivity, "Save failed: $err", Toast.LENGTH_LONG).show()
                return@launch
            }
            originalText = text
            markDirty(false)
            status.text = "Saved · ${text.lines().size} lines"
            if (finishAfter) finish()
        }
    }

    private fun scheduleHighlight() {
        highlightJob?.cancel()
        highlightJob = lifecycleScope.launch {
            delay(120)
            highlighter.apply(editor.text ?: return@launch)
        }
    }

    private fun markDirty(d: Boolean) {
        dirty = d
        fileNameLabel.text = (if (d) "● " else "") + file.name
    }

    /* ---------------------------------------------------------------- */
    /*  Preview (WebView)                                                */
    /* ---------------------------------------------------------------- */

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun setupPreview() {
        webView.settings.apply {
            javaScriptEnabled   = true
            domStorageEnabled   = true
            allowFileAccess     = false
            useWideViewPort     = true
            loadWithOverviewMode= true
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode    = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        webView.webViewClient = WebViewClient()
    }

    private fun loadPreview() {
        val url = urlForCurrentFile()
        if (url == null) {
            webView.loadData(
                "<html><body style='background:#0B1410;color:#F5F7FA;padding:2rem;" +
                "font-family:system-ui'><h2>⚠️ File is outside the web root</h2>" +
                "<p>Only files inside <code>www/</code> can be previewed.</p></body></html>",
                "text/html", "utf-8"
            )
            return
        }
        webView.loadUrl(url)
    }

    /** Map an on-disk file back to its served URL (if inside www/). */
    private fun urlForCurrentFile(): String? {
        val extRoot = getExternalFilesDir(null) ?: filesDir
        val docRoot = File(extRoot, Constants.WWW_DIR).canonicalPath
        val filePath = file.canonicalPath
        if (!filePath.startsWith(docRoot)) return null
        val rel = filePath.removePrefix(docRoot).replace('\\', '/')
            .let { if (it.startsWith("/")) it else "/$it" }
        return "http://localhost:${prefs.httpPort}$rel"
    }

    /* ---------------------------------------------------------------- */
    /*  File-tab actions                                                 */
    /* ---------------------------------------------------------------- */

    private fun promptRename() {
        val input = EditText(this).apply { setText(file.name) }
        AlertDialog.Builder(this)
            .setTitle(R.string.livecode_rename)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty() || newName.contains('/')) {
                    Toast.makeText(this, "Invalid name", Toast.LENGTH_SHORT).show(); return@setPositiveButton
                }
                val target = File(file.parentFile, newName)
                if (target.exists()) {
                    Toast.makeText(this, "Already exists", Toast.LENGTH_SHORT).show(); return@setPositiveButton
                }
                if (!file.renameTo(target)) {
                    Toast.makeText(this, "Rename failed", Toast.LENGTH_SHORT).show(); return@setPositiveButton
                }
                file = target
                fileNameLabel.text = file.name
                language = Language.fromFilename(file.name)
                highlighter.updateLanguage(language)
                highlighter.apply(editor.text!!)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showMoreSheet() {
        val items = arrayOf(
            getString(R.string.livecode_save),
            getString(R.string.livecode_open_url),
            getString(R.string.livecode_copy_content),
            getString(R.string.livecode_reload_disk),
        )
        AlertDialog.Builder(this).setTitle(file.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> save()
                    1 -> urlForCurrentFile()?.let { openInSystemBrowser(it) }
                    2 -> copyContent()
                    3 -> loadFile()
                }
            }.show()
    }

    private fun copyContent() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(file.name, editor.text.toString()))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun openInSystemBrowser(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: Throwable) { Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show() }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!dirty) { @Suppress("DEPRECATION") super.onBackPressed(); return }
        AlertDialog.Builder(this)
            .setTitle("Unsaved changes")
            .setMessage("Save before leaving?")
            .setPositiveButton("Save") { _, _ -> save(finishAfter = true) }
            .setNegativeButton("Discard") { _, _ -> finish() }
            .setNeutralButton("Cancel", null).show()
    }

    override fun onDestroy() {
        webView.stopLoading(); webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PATH = "extra_path"

        fun open(context: android.content.Context, file: File) {
            context.startActivity(
                Intent(context, LiveCodeActivity::class.java)
                    .putExtra(EXTRA_PATH, file.absolutePath)
            )
        }
    }
}
