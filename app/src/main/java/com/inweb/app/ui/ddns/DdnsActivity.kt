package com.inweb.app.ui.ddns

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.inweb.app.R
import com.inweb.app.ddns.DdnsConfig
import com.inweb.app.ddns.DdnsProvider
import com.inweb.app.ddns.DdnsWorker
import com.inweb.app.ui.common.BottomNavHelper
import com.inweb.app.ui.common.PageScaffold
import com.inweb.app.util.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dynamic DNS configuration screen.
 *
 * Layout (top → bottom):
 *   1. Enable Switch                              (with live status pill)
 *   2. Provider picker                            (DuckDNS / No-IP / Cloudflare / INAYA)
 *   3. Hostname editor                            (shows full URL preview)
 *   4. Credential / API token editor              (password-masked)
 *   5. Secondary field (only for providers that need one)
 *   6. Update-interval editor                     (5 / 15 / 30 / 60 min)
 *   7. "Update Now" button + last-push status card
 *   8. "Copy public URL" convenience button
 */
class DdnsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private var cfg: DdnsConfig = DdnsConfig()

    private lateinit var ddnsSwitch:   Switch
    private lateinit var statusPill:   TextView
    private lateinit var providerRow:  View
    private lateinit var providerValue:TextView
    private lateinit var providerTagline: TextView
    private lateinit var hostnameRow:  View
    private lateinit var hostnameValue:TextView
    private lateinit var domainPreview:TextView
    private lateinit var credRow:      View
    private lateinit var credValue:    TextView
    private lateinit var credLabel:    TextView
    private lateinit var usernameRow:  View
    private lateinit var usernameValue:TextView
    private lateinit var intervalRow:  View
    private lateinit var intervalValue:TextView
    private lateinit var pushNowBtn:   View
    private lateinit var openUrlBtn:   View
    private lateinit var copyUrlBtn:   View
    private lateinit var lastStatus:   TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ddns)
        PageScaffold.setup(this, getString(R.string.ddns_title)) {
            onBackPressedDispatcher.onBackPressed()
        }
        BottomNavHelper.attach(this, BottomNavHelper.Tab.MORE)

        prefs = Prefs(this)
        cfg = prefs.ddns

        bindViews()
        renderAll()
    }

    override fun onResume() {
        super.onResume()
        // Refresh last-push info when returning from background.
        renderLastStatus()
    }

    /* ---------------------------------------------------------------- */
    /*  Bind + click handlers                                            */
    /* ---------------------------------------------------------------- */

    private fun bindViews() {
        ddnsSwitch      = findViewById(R.id.ddnsSwitch)
        statusPill      = findViewById(R.id.statusPill)
        providerRow     = findViewById(R.id.providerRow)
        providerValue   = findViewById(R.id.providerValue)
        providerTagline = findViewById(R.id.providerTagline)
        hostnameRow     = findViewById(R.id.hostnameRow)
        hostnameValue   = findViewById(R.id.hostnameValue)
        domainPreview   = findViewById(R.id.domainPreview)
        credRow         = findViewById(R.id.credRow)
        credValue       = findViewById(R.id.credValue)
        credLabel       = findViewById(R.id.credLabel)
        usernameRow     = findViewById(R.id.usernameRow)
        usernameValue   = findViewById(R.id.usernameValue)
        intervalRow     = findViewById(R.id.intervalRow)
        intervalValue   = findViewById(R.id.intervalValue)
        pushNowBtn      = findViewById(R.id.pushNowBtn)
        openUrlBtn      = findViewById(R.id.openUrlBtn)
        copyUrlBtn      = findViewById(R.id.copyUrlBtn)
        lastStatus      = findViewById(R.id.lastStatus)

        ddnsSwitch.setOnCheckedChangeListener { _, checked ->
            update { it.copy(enabled = checked) }
            DdnsWorker.schedule(this)      // enqueue or cancel accordingly
        }
        providerRow.setOnClickListener { pickProvider() }
        hostnameRow.setOnClickListener {
            promptText(getString(R.string.ddns_hostname_dialog), cfg.hostname,
                InputType.TYPE_CLASS_TEXT) { update { c -> c.copy(hostname = it) } }
        }
        credRow.setOnClickListener {
            promptText(getString(R.string.ddns_cred_dialog, cfg.provider.credentialLabel),
                cfg.credential,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            ) { update { c -> c.copy(credential = it) } }
        }
        usernameRow.setOnClickListener {
            val label = if (cfg.provider == DdnsProvider.CLOUDFLARE)
                getString(R.string.ddns_cf_zone_dialog) else getString(R.string.ddns_username_dialog)
            promptText(label, cfg.username, InputType.TYPE_CLASS_TEXT) {
                update { c -> c.copy(username = it) }
            }
        }
        intervalRow.setOnClickListener { pickInterval() }

        pushNowBtn.setOnClickListener {
            if (!cfg.isValid) {
                Toast.makeText(this, R.string.ddns_incomplete, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            DdnsWorker.pushNow(this)
            Toast.makeText(this, R.string.ddns_push_enqueued, Toast.LENGTH_SHORT).show()
            // Give the worker a moment, then re-render.
            lastStatus.postDelayed({ renderLastStatus() }, 2500)
        }
        openUrlBtn.setOnClickListener {
            val url = "http://${cfg.fullDomain}:${prefs.httpPort}"
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            catch (_: Throwable) { Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show() }
        }
        copyUrlBtn.setOnClickListener {
            val url = "http://${cfg.fullDomain}:${prefs.httpPort}"
            val cm  = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("DDNS URL", url))
            Toast.makeText(this, getString(R.string.copied_url, url), Toast.LENGTH_SHORT).show()
        }
    }

    /* ---------------------------------------------------------------- */
    /*  Mutation + render                                                */
    /* ---------------------------------------------------------------- */

    private fun update(mutator: (DdnsConfig) -> DdnsConfig) {
        cfg = mutator(cfg)
        prefs.ddns = cfg
        renderAll()
        if (cfg.enabled) DdnsWorker.schedule(this)
    }

    private fun renderAll() {
        ddnsSwitch.isChecked = cfg.enabled
        providerValue.text   = cfg.provider.displayName
        providerTagline.text = cfg.provider.tagline
        credLabel.text       = cfg.provider.credentialLabel
        credValue.text       = if (cfg.credential.isEmpty()) getString(R.string.ddns_not_set)
                               else "•".repeat(cfg.credential.length.coerceAtMost(12))
        hostnameValue.text   = cfg.hostname.ifBlank { getString(R.string.ddns_not_set) }
        domainPreview.text   = cfg.fullDomain.ifBlank { "…" }

        usernameRow.visibility = if (cfg.provider.needsUsername) View.VISIBLE else View.GONE
        usernameValue.text = cfg.username.ifBlank { getString(R.string.ddns_not_set) }

        intervalValue.text = resources.getQuantityString(
            R.plurals.ddns_interval_minutes, cfg.intervalMinutes, cfg.intervalMinutes
        )

        val ok = cfg.enabled && cfg.isValid
        statusPill.text = when {
            !cfg.enabled     -> getString(R.string.ddns_status_off)
            !cfg.isValid     -> getString(R.string.ddns_status_incomplete)
            else             -> getString(R.string.ddns_status_active)
        }
        statusPill.setTextColor(
            if (ok) 0xFF10B981.toInt() else if (cfg.enabled) 0xFFF59E0B.toInt() else 0xFF9AB5AA.toInt()
        )

        val hasUrl = cfg.isValid
        openUrlBtn.isEnabled = hasUrl
        copyUrlBtn.isEnabled = hasUrl
        openUrlBtn.alpha = if (hasUrl) 1f else 0.35f
        copyUrlBtn.alpha = if (hasUrl) 1f else 0.35f

        renderLastStatus()
    }

    private fun renderLastStatus() {
        val ts = prefs.ddnsLastPushMs
        val msg = prefs.ddnsLastResult
        lastStatus.text = if (ts == 0L || msg.isBlank()) {
            getString(R.string.ddns_last_none)
        } else {
            val age = SimpleDateFormat("MMM d · HH:mm:ss", Locale.getDefault()).format(Date(ts))
            "$age\n$msg"
        }
    }

    /* ---------------------------------------------------------------- */
    /*  Dialogs                                                          */
    /* ---------------------------------------------------------------- */

    private fun pickProvider() {
        val providers = DdnsProvider.entries
        val labels    = providers.map { "${it.displayName}\n${it.tagline}" }.toTypedArray()
        val current   = providers.indexOf(cfg.provider).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.ddns_provider_dialog)
            .setSingleChoiceItems(labels, current) { d, which ->
                update { it.copy(provider = providers[which]) }
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pickInterval() {
        val options = intArrayOf(15, 30, 60, 120, 360, 720, 1440)
        val labels  = options.map { m -> resources.getQuantityString(
            R.plurals.ddns_interval_minutes, m, m) }.toTypedArray()
        val idx = options.indexOf(cfg.intervalMinutes.coerceAtLeast(15)).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.ddns_interval_dialog)
            .setSingleChoiceItems(labels, idx) { d, which ->
                update { it.copy(intervalMinutes = options[which]) }
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun promptText(title: String, current: String, type: Int, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            inputType = type; setText(current); setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(title).setView(input)
            .setPositiveButton(R.string.save) { _, _ -> onOk(input.text.toString().trim()) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }
}
