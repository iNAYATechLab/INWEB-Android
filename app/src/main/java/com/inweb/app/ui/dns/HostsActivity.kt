package com.inweb.app.ui.dns

import android.app.Activity
import android.content.Intent
import android.net.VpnService
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
import com.inweb.app.dns.DnsServerManager
import com.inweb.app.dns.HostEntry
import com.inweb.app.dns.HostEntryStore
import com.inweb.app.dns.InwebDnsResolver
import com.inweb.app.dns.InwebVpnService
import com.inweb.app.net.NetworkUtils
import com.inweb.app.ui.common.BottomNavHelper
import com.inweb.app.ui.common.PageScaffold
import com.inweb.app.util.Prefs

/**
 * User-facing screen for **Local Hosts Mapping (Stage 2)**.
 *
 *   – Master switch to start/stop the VPN-based DNS interceptor.
 *   – List of host → IP entries with per-row enable toggles.
 *   – "+" in the header opens [HostEditorActivity] to add a new one.
 *
 * On any change we call [InwebDnsResolver.rebuild] so the running VPN
 * picks the new snapshot on the very next query.
 */
class HostsActivity : AppCompatActivity() {

    private lateinit var store: HostEntryStore
    private lateinit var container: LinearLayout
    private lateinit var vpnSwitch: Switch
    private lateinit var vpnStatus: TextView
    private lateinit var emptyState: View
    private lateinit var dnsSrvSwitch: Switch
    private lateinit var dnsSrvStatus: TextView

    /** VpnService.prepare() launches this to ask the user for permission. */
    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode == Activity.RESULT_OK) {
                InwebVpnService.start(this)
                renderStatus(true)
            } else {
                Toast.makeText(this, R.string.dns_vpn_denied, Toast.LENGTH_LONG).show()
                vpnSwitch.isChecked = false
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hosts)
        PageScaffold.setup(this, getString(R.string.dns_hosts_title)) {
            onBackPressedDispatcher.onBackPressed()
        }
        PageScaffold.setActionIcon(this, R.drawable.ic_add) { openEditor(null) }
        BottomNavHelper.attach(this, BottomNavHelper.Tab.MORE)

        store        = HostEntryStore(this)
        container    = findViewById(R.id.container)
        emptyState   = findViewById(R.id.emptyState)
        vpnSwitch    = findViewById(R.id.vpnSwitch)
        vpnStatus    = findViewById(R.id.vpnStatus)
        dnsSrvSwitch = findViewById(R.id.dnsServerSwitch)
        dnsSrvStatus = findViewById(R.id.dnsServerStatus)

        findViewById<View>(R.id.emptyAddBtn).setOnClickListener { openEditor(null) }
        findViewById<View>(R.id.emptyPresetBtn).setOnClickListener { seedPresets() }

        vpnSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked) requestVpn() else {
                InwebVpnService.stop(this)
                renderStatus(false)
            }
        }

        // LAN DNS server toggle (Stage 3)
        dnsSrvSwitch.isChecked = Prefs(this).dnsServerEnabled
        renderDnsServerStatus()
        dnsSrvSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked) DnsServerManager.enable(this) else DnsServerManager.disable(this)
            renderDnsServerStatus()
        }
    }

    private fun renderDnsServerStatus() {
        val prefs = Prefs(this)
        val running = DnsServerManager.isRunning
        val port = prefs.dnsServerPort
        val ip = NetworkUtils.snapshot(this).ipv4 ?: "no LAN"
        dnsSrvStatus.text = if (running)
            getString(R.string.dns_server_on, ip, port)
        else
            getString(R.string.dns_server_off)
        dnsSrvStatus.setTextColor(if (running) 0xFF10B981.toInt() else 0xFF9AB5AA.toInt())
    }

    override fun onResume() {
        super.onResume()
        refresh()
        renderDnsServerStatus()
    }

    /* ---------------------------------------------------------------- */
    /*  VPN permission + status                                          */
    /* ---------------------------------------------------------------- */

    private fun requestVpn() {
        val consent = VpnService.prepare(this)
        if (consent != null) vpnPermission.launch(consent)
        else {
            InwebVpnService.start(this)
            renderStatus(true)
        }
    }

    private fun renderStatus(on: Boolean) {
        vpnStatus.text = getString(
            if (on) R.string.dns_vpn_status_on else R.string.dns_vpn_status_off
        )
        vpnStatus.setTextColor(if (on) 0xFF10B981.toInt() else 0xFF9AB5AA.toInt())
    }

    /* ---------------------------------------------------------------- */
    /*  List rendering                                                   */
    /* ---------------------------------------------------------------- */

    private fun refresh() {
        val entries = store.all()
        container.removeAllViews()
        emptyState.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        val inflater = LayoutInflater.from(this)
        for (e in entries) {
            val row = inflater.inflate(R.layout.item_host_entry, container, false)
            bindRow(row, e); container.addView(row)
        }
        InwebDnsResolver.rebuild(this)
    }

    private fun bindRow(row: View, e: HostEntry) {
        val name    = row.findViewById<TextView>(R.id.hostName)
        val ip      = row.findViewById<TextView>(R.id.hostIp)
        val note    = row.findViewById<TextView>(R.id.hostNote)
        val toggle  = row.findViewById<Switch>(R.id.hostToggle)
        val editBtn = row.findViewById<ImageButton>(R.id.hostEdit)
        val delBtn  = row.findViewById<ImageButton>(R.id.hostDelete)
        val dot     = row.findViewById<ImageView>(R.id.hostDot)

        name.text = e.hostname
        ip.text   = "→ ${e.ip}"
        note.text = e.note.ifBlank { getString(R.string.dns_no_note) }
        toggle.setOnCheckedChangeListener(null)
        toggle.isChecked = e.enabled
        dot.setImageResource(if (e.enabled) R.drawable.dot_green else R.drawable.dot_red)

        toggle.setOnCheckedChangeListener { _, checked ->
            store.setEnabled(e.id, checked); refresh()
        }
        editBtn.setOnClickListener { openEditor(e.id) }
        delBtn.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.dns_delete_title, e.hostname))
                .setPositiveButton(R.string.delete) { _, _ -> store.delete(e.id); refresh() }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
    }

    private fun openEditor(id: String?) {
        startActivity(Intent(this, HostEditorActivity::class.java)
            .apply { if (id != null) putExtra(HostEditorActivity.EXTRA_ID, id) })
    }

    /** Convenience: seed a handful of common .local entries. */
    private fun seedPresets() {
        val examples = listOf(
            "wordpress.local"   to "127.0.0.1",
            "laravel.local"     to "127.0.0.1",
            "api.local"         to "127.0.0.1",
            "phpmyadmin.local"  to "127.0.0.1"
        )
        for ((h, ip) in examples) {
            store.upsert(HostEntry(hostname = h, ip = ip, note = "preset"))
        }
        refresh()
        Toast.makeText(this, R.string.dns_presets_added, Toast.LENGTH_SHORT).show()
    }
}
