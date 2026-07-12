package com.inweb.app.ui.vhost

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.inweb.app.Constants
import com.inweb.app.R
import com.inweb.app.ui.common.BottomNavHelper
import com.inweb.app.ui.common.PageScaffold
import com.inweb.app.util.Prefs
import com.inweb.app.vhost.SiteTemplate
import com.inweb.app.vhost.VirtualHost
import com.inweb.app.vhost.VirtualHostStore
import java.io.File

/**
 * Add / edit a virtual host. Fields:
 *   – Server name (e.g. "wordpress.local")
 *   – Label (optional friendly name)
 *   – Document root (path picker with a "Use www/<slug>" shortcut)
 *   – PHP mode (Auto / Static)
 *   – Enabled toggle
 *
 * On save the entry is persisted; the user gets a hint to restart the
 * server so nginx.conf is regenerated.
 */
class VHostEditorActivity : AppCompatActivity() {

    private lateinit var store: VirtualHostStore
    private lateinit var prefs: Prefs
    private var existing: VirtualHost? = null

    private lateinit var nameField:   EditText
    private lateinit var labelField:  EditText
    private lateinit var rootField:   EditText
    private lateinit var previewText: TextView
    private lateinit var phpGroup:    RadioGroup
    private lateinit var phpAuto:     RadioButton
    private lateinit var phpStatic:   RadioButton

    /** Only relevant when creating (not editing). */
    private var selectedTemplate: SiteTemplate = SiteTemplate.BLANK

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vhost_editor)
        prefs = Prefs(this)
        store = VirtualHostStore(this)

        val id = intent.getStringExtra(EXTRA_ID)
        existing = id?.let { store.byId(it) }

        PageScaffold.setup(
            this,
            title = if (existing != null) getString(R.string.vhost_edit_title)
                    else getString(R.string.vhost_new_title),
            onBack = { onBackPressedDispatcher.onBackPressed() }
        )
        PageScaffold.setActionIcon(this, R.drawable.ic_save) { save() }
        BottomNavHelper.attach(this, BottomNavHelper.Tab.MORE)

        nameField   = findViewById(R.id.vhName)
        labelField  = findViewById(R.id.vhLabel)
        rootField   = findViewById(R.id.vhRoot)
        previewText = findViewById(R.id.vhPreviewUrl)
        phpGroup    = findViewById(R.id.phpGroup)
        phpAuto     = findViewById(R.id.phpAuto)
        phpStatic   = findViewById(R.id.phpStatic)

        // Populate from existing (edit) or provide sensible defaults.
        val e = existing
        if (e != null) {
            nameField.setText(e.serverName)
            labelField.setText(e.label)
            rootField.setText(e.documentRoot)
            when (e.phpMode) {
                VirtualHost.PhpMode.AUTO   -> phpAuto.isChecked = true
                VirtualHost.PhpMode.STATIC -> phpStatic.isChecked = true
            }
        } else {
            phpAuto.isChecked = true
            // Default docroot suggestion: <externalFiles>/www/<slug>/
            rootField.setText(defaultDocroot("new-site"))
        }

        // Live URL preview.
        nameField.addTextChangedListener(SimpleWatcher {
            previewText.text = "http://${nameField.text.trim()}:${prefs.httpPort}/"
            // If the user hasn't customised the docroot, keep it in sync with the slug.
            if (existing == null || rootField.text.toString().startsWith(baseWwwPath())) {
                rootField.setText(defaultDocroot(nameField.text.toString()))
            }
        })
        previewText.text = "http://${nameField.text.trim().ifEmpty { "site.local" }}:${prefs.httpPort}/"

        findViewById<View>(R.id.useWwwBtn).setOnClickListener {
            rootField.setText(defaultDocroot(nameField.text.toString()))
        }

        // Template picker — only shown for new sites (not edit).
        val templateRow = findViewById<View>(R.id.templateRow)
        val templateValue = findViewById<TextView>(R.id.templateValue)
        val templateTagline = findViewById<TextView>(R.id.templateTagline)
        if (existing != null) {
            templateRow.visibility = View.GONE
        } else {
            renderTemplate(templateValue, templateTagline)
            templateRow.setOnClickListener { pickTemplate(templateValue, templateTagline) }
        }
    }

    private fun renderTemplate(value: TextView, tagline: TextView) {
        value.text   = "${selectedTemplate.emoji}  ${selectedTemplate.displayName}"
        tagline.text = selectedTemplate.tagline
    }

    private fun pickTemplate(value: TextView, tagline: TextView) {
        val templates = SiteTemplate.entries
        val labels = templates.map { "${it.emoji}  ${it.displayName}\n${it.tagline}" }.toTypedArray()
        val current = templates.indexOf(selectedTemplate).coerceAtLeast(0)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.vhost_template_dialog)
            .setSingleChoiceItems(labels, current) { d, which ->
                selectedTemplate = templates[which]
                // Auto-fill helpful defaults from the template.
                if (nameField.text.isEmpty()) nameField.setText(selectedTemplate.defaultServerName)
                if (labelField.text.isEmpty()) labelField.setText(selectedTemplate.defaultLabel)
                if (selectedTemplate.phpMode == VirtualHost.PhpMode.STATIC) phpStatic.isChecked = true
                else phpAuto.isChecked = true
                renderTemplate(value, tagline)
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun baseWwwPath(): String {
        val ext = getExternalFilesDir(null) ?: filesDir
        return File(ext, Constants.WWW_DIR).absolutePath
    }

    private fun defaultDocroot(slug: String): String {
        val safe = slug.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "site" }
        return "${baseWwwPath()}/$safe"
    }

    private fun save() {
        val name = nameField.text.toString().trim()
        val root = rootField.text.toString().trim()
        if (!name.matches(Regex("^[a-zA-Z0-9.\\-_]+$"))) {
            Toast.makeText(this, R.string.vhost_bad_name, Toast.LENGTH_LONG).show(); return
        }
        if (root.isEmpty()) {
            Toast.makeText(this, R.string.vhost_bad_root, Toast.LENGTH_SHORT).show(); return
        }

        // Auto-create the folder. On brand-new sites, seed with the picked
        // template's files; otherwise fall back to the default landing page.
        val dir = File(root)
        val isBrandNew = existing == null && !dir.exists()
        if (!dir.exists() && !dir.mkdirs()) {
            Toast.makeText(this, R.string.vhost_mkdir_failed, Toast.LENGTH_LONG).show(); return
        }
        if (isBrandNew) {
            if (selectedTemplate.files.isEmpty()) {
                File(dir, "index.html").writeText(landingPage(name))
            } else {
                for (tf in selectedTemplate.files) {
                    val target = File(dir, tf.relPath)
                    target.parentFile?.mkdirs()
                    if (!target.exists()) target.writeText(tf.content)
                }
            }
        }

        val toSave = (existing ?: VirtualHost(serverName = name, documentRoot = root)).copy(
            serverName   = name,
            label        = labelField.text.toString().trim(),
            documentRoot = root,
            phpMode      = if (phpStatic.isChecked) VirtualHost.PhpMode.STATIC else VirtualHost.PhpMode.AUTO,
        )
        store.upsert(toSave)
        Toast.makeText(this, R.string.vhost_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun landingPage(name: String): String = """
        <!doctype html>
        <html><head><meta charset="utf-8"><title>$name — INWEB</title>
        <style>
        body{font-family:system-ui;padding:2rem;max-width:640px;margin:auto;
             background:#0B1410;color:#F5F7FA}
        h1{color:#14B8A6}code{background:#132821;padding:2px 6px;border-radius:4px}
        </style></head>
        <body>
        <h1>🎉 $name is live</h1>
        <p>This virtual host is served by INWEB. Drop your PHP/HTML files here:</p>
        <p><code>${rootField.text}</code></p>
        <p>Any changes take effect immediately — with Live Reload on, the browser auto-refreshes too.</p>
        </body></html>
    """.trimIndent()

    companion object { const val EXTRA_ID = "extra_id" }
}

/** One-shot minimal TextWatcher lambda helper. */
private class SimpleWatcher(private val onChange: () -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    override fun afterTextChanged(s: android.text.Editable?) { onChange() }
}
