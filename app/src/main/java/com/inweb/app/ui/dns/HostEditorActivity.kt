package com.inweb.app.ui.dns

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.inweb.app.R
import com.inweb.app.dns.HostEntry
import com.inweb.app.dns.HostEntryStore
import com.inweb.app.ui.common.BottomNavHelper
import com.inweb.app.ui.common.PageScaffold

/**
 * Add or edit a single [HostEntry]. Validates hostname + IPv4 before saving.
 */
class HostEditorActivity : AppCompatActivity() {

    private lateinit var store: HostEntryStore
    private var existing: HostEntry? = null

    private lateinit var hostField: EditText
    private lateinit var ipField:   EditText
    private lateinit var noteField: EditText
    private lateinit var preview:   TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_host_editor)
        store = HostEntryStore(this)

        val id = intent.getStringExtra(EXTRA_ID)
        existing = id?.let { store.byId(it) }

        PageScaffold.setup(this,
            title = if (existing != null) getString(R.string.dns_edit_title)
                    else getString(R.string.dns_new_title),
            onBack = { onBackPressedDispatcher.onBackPressed() }
        )
        PageScaffold.setActionIcon(this, R.drawable.ic_save) { save() }
        BottomNavHelper.attach(this, BottomNavHelper.Tab.MORE)

        hostField = findViewById(R.id.hostName)
        ipField   = findViewById(R.id.hostIp)
        noteField = findViewById(R.id.hostNote)
        preview   = findViewById(R.id.hostPreview)

        val e = existing
        if (e != null) {
            hostField.setText(e.hostname)
            ipField.setText(e.ip)
            noteField.setText(e.note)
        } else {
            ipField.setText("127.0.0.1")
        }

        val watcher = com.inweb.app.util.TextChangedListener { updatePreview() }
        hostField.addTextChangedListener(watcher)
        ipField.addTextChangedListener(watcher)
        updatePreview()

        findViewById<View>(R.id.presetLocalhost).setOnClickListener {
            ipField.setText("127.0.0.1"); updatePreview()
        }
        findViewById<View>(R.id.presetBlock).setOnClickListener {
            ipField.setText("0.0.0.0"); updatePreview()
        }
    }

    private fun updatePreview() {
        val h = hostField.text.toString().trim()
        val ip = ipField.text.toString().trim()
        preview.text = if (h.isEmpty()) "…" else "$h  →  ${ip.ifEmpty { "?" }}"
    }

    private fun save() {
        val host = hostField.text.toString().trim().lowercase()
        val ip   = ipField.text.toString().trim()
        if (!HostEntry.validHostname(host)) {
            Toast.makeText(this, R.string.dns_bad_hostname, Toast.LENGTH_LONG).show(); return
        }
        if (!HostEntry.validIpv4(ip)) {
            Toast.makeText(this, R.string.dns_bad_ip, Toast.LENGTH_LONG).show(); return
        }
        val toSave = (existing ?: HostEntry(hostname = host, ip = ip)).copy(
            hostname = host, ip = ip, note = noteField.text.toString().trim()
        )
        store.upsert(toSave)
        Toast.makeText(this, R.string.dns_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object { const val EXTRA_ID = "extra_id" }
}
