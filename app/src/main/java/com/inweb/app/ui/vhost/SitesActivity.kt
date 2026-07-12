package com.inweb.app.ui.vhost

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.inweb.app.R
import com.inweb.app.ui.common.BottomNavHelper
import com.inweb.app.ui.common.PageScaffold
import com.inweb.app.ui.preview.PreviewActivity
import com.inweb.app.util.Prefs
import com.inweb.app.vhost.VHostImportExport
import com.inweb.app.vhost.VirtualHost
import com.inweb.app.vhost.VirtualHostStore

/**
 * Screen that lists all user-defined virtual hosts. Each row shows the
 * server name, doc root, and an enable/disable Switch. Tapping the row
 * opens the site in the built-in preview; the pencil icon jumps to
 * [VHostEditorActivity] for editing.
 *
 * A floating "+" adds a new host.
 *
 * After any change, [Prefs] is not touched — the store writes to
 * `vhosts.json` and the next server start regenerates nginx.conf.
 */
class SitesActivity : AppCompatActivity() {

    private lateinit var store: VirtualHostStore
    private lateinit var prefs: Prefs
    private lateinit var container: LinearLayout
    private lateinit var emptyState: View

    // Document picker for import (JSON file → back into the app)
    private val importPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        val json = VHostImportExport.readUri(this, uri)
        if (json == null) {
            Toast.makeText(this, R.string.vhost_import_read_fail, Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        runCatching { VHostImportExport.importFromJson(this, json) }
            .onSuccess { n ->
                Toast.makeText(this, getString(R.string.vhost_import_ok, n),
                    Toast.LENGTH_LONG).show()
                refresh()
            }
            .onFailure {
                Toast.makeText(this, getString(R.string.vhost_import_bad, it.message),
                    Toast.LENGTH_LONG).show()
            }
    }

    // Document picker for export (JSON file → user storage)
    private val exportPicker = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        val json = VHostImportExport.exportToJson(this)
        val ok = VHostImportExport.writeUri(this, uri, json)
        Toast.makeText(this,
            if (ok) R.string.vhost_export_ok else R.string.vhost_export_fail,
            Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sites)
        PageScaffold.setup(this, getString(R.string.sites_title)) {
            onBackPressedDispatcher.onBackPressed()
        }
        PageScaffold.setActionIcon(this, R.drawable.ic_add) { addNew() }
        PageScaffold.setSecondaryActionIcon(this, R.drawable.ic_more) { showOverflowMenu() }
        BottomNavHelper.attach(this, BottomNavHelper.Tab.MORE)

        prefs = Prefs(this)
        store = VirtualHostStore(this)
        container  = findViewById(R.id.container)
        emptyState = findViewById(R.id.emptyState)

        findViewById<View>(R.id.emptyAddBtn).setOnClickListener { addNew() }
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        val hosts = store.all()
        container.removeAllViews()
        emptyState.visibility = if (hosts.isEmpty()) View.VISIBLE else View.GONE

        val inflater = LayoutInflater.from(this)
        for (vh in hosts) {
            val row = inflater.inflate(R.layout.item_vhost, container, false)
            bindRow(row, vh)
            container.addView(row)
        }
    }

    private fun bindRow(row: View, vh: VirtualHost) {
        val name    = row.findViewById<TextView>(R.id.vhName)
        val root    = row.findViewById<TextView>(R.id.vhRoot)
        val url     = row.findViewById<TextView>(R.id.vhUrl)
        val badge   = row.findViewById<TextView>(R.id.vhBadge)
        val toggle  = row.findViewById<Switch>(R.id.vhToggle)
        val editBtn = row.findViewById<ImageButton>(R.id.vhEdit)
        val delBtn  = row.findViewById<ImageButton>(R.id.vhDelete)
        val dot     = row.findViewById<ImageView>(R.id.vhDot)

        name.text  = vh.displayLabel
        root.text  = vh.documentRoot
        url.text   = "http://${vh.serverName}:${prefs.httpPort}/"
        badge.text = if (vh.phpMode == VirtualHost.PhpMode.STATIC) "static" else "php"
        toggle.setOnCheckedChangeListener(null)   // avoid recursion
        toggle.isChecked = vh.enabled
        dot.setImageResource(if (vh.enabled) R.drawable.dot_green else R.drawable.dot_red)

        toggle.setOnCheckedChangeListener { _, checked ->
            store.setEnabled(vh.id, checked)
            dot.setImageResource(if (checked) R.drawable.dot_green else R.drawable.dot_red)
            hintRestart()
        }
        editBtn.setOnClickListener {
            startActivity(Intent(this, VHostEditorActivity::class.java)
                .putExtra(VHostEditorActivity.EXTRA_ID, vh.id))
        }
        delBtn.setOnClickListener { confirmDelete(vh) }
        row.setOnClickListener { openInPreview(vh) }
    }

    private fun openInPreview(vh: VirtualHost) {
        if (!vh.enabled) {
            Toast.makeText(this, R.string.sites_disabled_toast, Toast.LENGTH_SHORT).show()
            return
        }
        PreviewActivity.open(this, "http://${vh.serverName}:${prefs.httpPort}/")
    }

    private fun confirmDelete(vh: VirtualHost) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.sites_delete_title, vh.displayLabel))
            .setMessage(R.string.sites_delete_msg)
            .setPositiveButton(R.string.delete) { _, _ ->
                store.delete(vh.id); refresh(); hintRestart()
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun addNew() {
        startActivity(Intent(this, VHostEditorActivity::class.java))
    }

    private fun hintRestart() {
        Toast.makeText(this, R.string.sites_restart_hint, Toast.LENGTH_SHORT).show()
    }

    /* ---------------------------------------------------------------- */
    /*  Overflow menu — import / export / copy-to-clipboard              */
    /* ---------------------------------------------------------------- */

    private fun showOverflowMenu() {
        val items = arrayOf(
            getString(R.string.vhost_export_file),
            getString(R.string.vhost_export_clip),
            getString(R.string.vhost_import_file)
        )
        AlertDialog.Builder(this).setTitle(R.string.vhost_menu_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> exportPicker.launch("inweb-sites-${System.currentTimeMillis()}.json")
                    1 -> copyExportToClipboard()
                    2 -> importPicker.launch(arrayOf("application/json", "text/plain", "*/*"))
                }
            }.show()
    }

    private fun copyExportToClipboard() {
        val json = VHostImportExport.exportToJson(this)
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("INWEB sites JSON", json))
        Toast.makeText(this, R.string.vhost_export_clip_ok, Toast.LENGTH_SHORT).show()
    }
}
