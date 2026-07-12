package com.inweb.app.ui.editor

import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.inweb.app.R
import com.inweb.app.ui.common.PageScaffold
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
 * Pro text editor with:
 *   – Syntax highlighting (12+ languages, extension-detected)
 *   – Line-numbered gutter + current-line highlight
 *   – Find & Replace overlay
 *   – Multiple color themes (INWEB, Dracula, Monokai, Solarized)
 *   – Adjustable font size
 *   – Dirty tracking + async load/save + unsaved-changes guard
 */
class EditorActivity : AppCompatActivity() {

    private lateinit var file: File
    private lateinit var editor: CodeEditorView
    private lateinit var status: TextView
    private lateinit var prefs: Prefs
    private lateinit var findBar: FindBarController
    private lateinit var language: Language
    private lateinit var highlighter: SyntaxHighlighter
    private lateinit var currentTheme: EditorTheme

    private var originalText: String = ""
    private var dirty: Boolean = false
    private var highlightJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)
        prefs = Prefs(this)

        val path = intent.getStringExtra(EXTRA_PATH)
        if (path.isNullOrBlank()) { finish(); return }
        file = File(path)
        if (!file.exists() || !file.isFile) {
            Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show()
            finish(); return
        }

        PageScaffold.setup(this, file.name) { onBackPressedDispatcher.onBackPressed() }
        PageScaffold.setActionIcon(this, R.drawable.ic_save) { save() }
        PageScaffold.setSecondaryActionIcon(this, R.drawable.ic_more) { showEditorMenu() }

        editor = findViewById(R.id.editor)
        status = findViewById(R.id.status)

        currentTheme = EditorTheme.byId(prefs.editorThemeId)
        language     = Language.fromFilename(file.name)
        highlighter  = SyntaxHighlighter(language, currentTheme)

        editor.applyTheme(currentTheme)
        editor.setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.editorFontSize.toFloat())

        setupFindBar()
        loadFile()

        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                markDirty(s?.toString() != originalText)
                scheduleHighlight()
            }
        })
    }

    /* ------------------------------------------------------------- */
    /*  Menu                                                          */
    /* ------------------------------------------------------------- */

    private fun showEditorMenu() {
        val items = arrayOf(
            getString(R.string.editor_find),
            getString(R.string.editor_preview),
            getString(R.string.editor_theme),
            getString(R.string.editor_language),
            getString(R.string.editor_font_bigger),
            getString(R.string.editor_font_smaller),
            getString(R.string.editor_reload),
        )
        AlertDialog.Builder(this).setTitle(file.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> findBar.show()
                    1 -> openPreviewForCurrentFile()
                    2 -> pickTheme()
                    3 -> pickLanguage()
                    4 -> changeFontSize(+1)
                    5 -> changeFontSize(-1)
                    6 -> confirmReload()
                }
            }.show()
    }

    /**
     * Save the current file (if dirty), then open it in the built-in preview.
     * If the file lives under the web-root, we map it to its URL; otherwise
     * we fall back to the site root.
     */
    private fun openPreviewForCurrentFile() {
        if (dirty) save()
        val port = prefs.httpPort
        val extRoot = getExternalFilesDir(null) ?: filesDir
        val docRoot = java.io.File(extRoot, com.inweb.app.Constants.WWW_DIR).canonicalPath
        val filePath = file.canonicalPath
        val url = if (filePath.startsWith(docRoot)) {
            val rel = filePath.removePrefix(docRoot).replace('\\', '/')
                .let { if (it.startsWith("/")) it else "/$it" }
            "http://localhost:$port$rel"
        } else {
            "http://localhost:$port/"
        }
        com.inweb.app.ui.preview.PreviewActivity.open(this, url)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::findBar.isInitialized && findBar.isVisible()) { findBar.hide(); return }
        if (!dirty) { @Suppress("DEPRECATION") super.onBackPressed(); return }
        AlertDialog.Builder(this)
            .setTitle("Unsaved changes")
            .setMessage("Save before leaving?")
            .setPositiveButton("Save") { _, _ -> save(finishAfter = true) }
            .setNegativeButton("Discard") { _, _ -> finish() }
            .setNeutralButton("Cancel", null)
            .show()
    }

    /* ------------------------------------------------------------- */
    /*  Load / save                                                   */
    /* ------------------------------------------------------------- */

    private fun loadFile() {
        status.text = "Loading…"
        lifecycleScope.launch {
            val (text, err) = withContext(Dispatchers.IO) {
                runCatching {
                    if (file.length() > FileUtils.MAX_EDIT_BYTES)
                        error("File is too large (>${FileUtils.humanSize(FileUtils.MAX_EDIT_BYTES)})")
                    file.readText()
                }.fold({ it to null }, { "" to it.message })
            }
            if (err != null) {
                Toast.makeText(this@EditorActivity, err, Toast.LENGTH_LONG).show()
                finish(); return@launch
            }
            originalText = text
            editor.setText(text)
            markDirty(false)
            status.text = "${text.length} chars · ${text.lines().size} lines"
            // Initial paint.
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
                Toast.makeText(this@EditorActivity, "Save failed: $err", Toast.LENGTH_LONG).show()
                status.text = "Save failed"; return@launch
            }
            originalText = text
            markDirty(false)
            status.text = "${language.displayName} · ${FileUtils.humanSize(file.length())} · saved"
            Toast.makeText(this@EditorActivity, "Saved", Toast.LENGTH_SHORT).show()
            status.text = "Saved · ${text.length} chars"
            if (finishAfter) finish()
        }
    }

    private fun confirmReload() {
        if (!dirty) { loadFile(); return }
        AlertDialog.Builder(this)
            .setTitle("Discard changes?")
            .setMessage("Reload from disk and lose unsaved edits?")
            .setPositiveButton("Reload") { _, _ -> loadFile() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun markDirty(d: Boolean) {
        dirty = d
        findViewById<TextView>(R.id.headerTitle).text = (if (d) "● " else "") + file.name
    }

    /* ------------------------------------------------------------- */
    /*  Highlighting (debounced)                                     */
    /* ------------------------------------------------------------- */

    private fun scheduleHighlight() {
        highlightJob?.cancel()
        highlightJob = lifecycleScope.launch {
            delay(120)   // debounce fast typing
            val editable = editor.text ?: return@launch
            highlighter.apply(editable)
        }
    }

    /* ------------------------------------------------------------- */
    /*  Find bar                                                      */
    /* ------------------------------------------------------------- */

    private fun setupFindBar() {
        findBar = FindBarController(
            root         = findViewById(R.id.findBar),
            editor       = editor,
            findInput    = findViewById(R.id.findInput),
            replaceInput = findViewById(R.id.replaceInput),
            counter      = findViewById(R.id.findCounter),
            prevBtn      = findViewById(R.id.findPrev),
            nextBtn      = findViewById(R.id.findNext),
            replaceBtn   = findViewById(R.id.replaceBtn),
            replaceAllBtn= findViewById(R.id.replaceAllBtn),
            closeBtn     = findViewById(R.id.findClose)
        )
    }

    /* ------------------------------------------------------------- */
    /*  Theme / language / font pickers                              */
    /* ------------------------------------------------------------- */

    private fun pickTheme() {
        val themes = EditorTheme.ALL
        val labels = themes.map { it.displayName }.toTypedArray()
        val current = themes.indexOfFirst { it.id == currentTheme.id }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.editor_theme_title)
            .setSingleChoiceItems(labels, current) { d, which ->
                currentTheme = themes[which]
                prefs.editorThemeId = currentTheme.id
                editor.applyTheme(currentTheme)
                highlighter.updateTheme(currentTheme)
                highlighter.apply(editor.text!!)
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pickLanguage() {
        val langs = listOf(
            Language.PLAIN, Language.PHP, Language.HTML, Language.CSS,
            Language.JAVASCRIPT, Language.JSON, Language.SQL, Language.SHELL,
            Language.XML, Language.YAML, Language.MARKDOWN, Language.INI
        )
        val labels = langs.map { it.displayName }.toTypedArray()
        val current = langs.indexOfFirst { it.id == language.id }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.editor_lang_title)
            .setSingleChoiceItems(labels, current) { d, which ->
                language = langs[which]
                highlighter.updateLanguage(language)
                highlighter.apply(editor.text!!)
                status.text =
                    "${language.displayName} · ${FileUtils.humanSize(file.length())}"
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun changeFontSize(delta: Int) {
        val newSize = (prefs.editorFontSize + delta).coerceIn(10, 24)
        if (newSize == prefs.editorFontSize) return
        prefs.editorFontSize = newSize
        editor.setTextSize(TypedValue.COMPLEX_UNIT_SP, newSize.toFloat())
        editor.applyTheme(currentTheme)  // recompute gutter width for new font
    }

    companion object { const val EXTRA_PATH = "extra_path" }
}
