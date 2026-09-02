package com.inweb.app.ui.share

import android.app.AlertDialog
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
import androidx.appcompat.app.AppCompatActivity
import com.inweb.app.R
import com.inweb.app.api.ApiServerManager
import com.inweb.app.livereload.LiveReloadManager
import com.inweb.app.net.NetworkUtils
import com.inweb.app.services.WebServerEngine
import com.inweb.app.ui.common.BottomNavHelper
import com.inweb.app.ui.common.PageScaffold
import com.inweb.app.util.AppLocale
import com.inweb.app.util.Prefs
import com.inweb.app.util.ThemeMode

/**
 * Settings screen. Sections:
 *   – NETWORK   : LAN bind toggle, HTTP port
 *   – DATABASE  : MariaDB enabled toggle, MariaDB port, root password, open phpMyAdmin
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        PageScaffold.setup(this, getString(R.string.settings_title)) {
            onBackPressedDispatcher.onBackPressed()
        }
        BottomNavHelper.attach(this, BottomNavHelper.Tab.MORE)
        prefs = Prefs(this)

        setupWebServerSection()
        setupLiveReloadSection()
        setupApiSection()
        setupNetworkSection()
        setupHttpsSection()
        setupDatabaseSection()
        setupUpdateSection()
        setupGeneralSection()
    }

    /* ---------------------------------------------------------- */
    /*  In-app updates (GitHub Releases)                           */
    /* ---------------------------------------------------------- */

    private fun setupUpdateSection() {
        val row    = findViewById<View>(R.id.updateCheckRow) ?: return
        val value  = findViewById<TextView>(R.id.updateVersionValue)
        val autoSw = findViewById<android.widget.Switch>(R.id.autoUpdateSwitch)

        val current = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Throwable) { "?" }

        value?.text = "v$current"
        row.setOnClickListener { com.inweb.app.util.UpdateChecker.manualCheck(this) }
        row.setOnLongClickListener {
            Toast.makeText(this, "GitHub: ${com.inweb.app.util.UpdateChecker.REPO}", Toast.LENGTH_SHORT).show()
            true
        }

        autoSw?.let { sw ->
            sw.isChecked = prefs.autoUpdateCheck
            sw.setOnCheckedChangeListener { _, checked -> prefs.autoUpdateCheck = checked }
        }
    }

    /* ---------------------------------------------------------- */
    /*  Web Dashboard / REST API                                   */
    /* ---------------------------------------------------------- */

    private fun setupApiSection() {
        val sw       = findViewById<android.widget.Switch>(R.id.apiSwitch)   ?: return
        val status   = findViewById<TextView>(R.id.apiStatus)
        val urlRow   = findViewById<View>(R.id.apiUrlRow)
        val urlValue = findViewById<TextView>(R.id.apiUrlValue)
        val tokRow   = findViewById<View>(R.id.apiTokenRow)
        val tokValue = findViewById<TextView>(R.id.apiTokenValue)
        val restartHint = findViewById<TextView>(R.id.restartHint)

        fun rebuildUrl(): String {
            val ip = NetworkUtils.snapshot(this).ipv4 ?: "192.168.x.x"
            return "http://$ip:${prefs.httpPort}/inweb-dashboard/"
        }

        fun refresh() {
            status?.text = if (ApiServerManager.isRunning)
                getString(R.string.api_status_on, prefs.apiPort) else getString(R.string.api_status_off)
            urlValue?.text  = rebuildUrl()
            val tok = prefs.apiToken
            tokValue?.text = if (tok.isEmpty()) "—" else tok.take(6) + "…" + tok.takeLast(4)
        }

        sw.isChecked = prefs.apiEnabled
        refresh()

        sw.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                ApiServerManager.tokenFor(this)     // ensure token exists
                ApiServerManager.enable(this)
            } else ApiServerManager.disable(this)
            refresh()
            restartHint.visibility = View.VISIBLE
        }

        urlRow.setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Dashboard URL", rebuildUrl()))
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
        }

        tokRow.setOnClickListener {
            val current = prefs.apiToken
            if (current.isEmpty()) {
                ApiServerManager.tokenFor(this); refresh()
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
                .setTitle(R.string.api_token_title)
                .setMessage(current)
                .setPositiveButton(R.string.copy) { _, _ ->
                    val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("INWEB token", current))
                    Toast.makeText(this, R.string.api_token_copied, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(R.string.api_regenerate) { _, _ ->
                    AlertDialog.Builder(this)
                        .setMessage(R.string.api_token_regen_confirm)
                        .setPositiveButton(R.string.api_regenerate) { _, _ ->
                            ApiServerManager.regenerateToken(this); refresh()
                        }
                        .setNegativeButton(android.R.string.cancel, null).show()
                }
                .setNeutralButton(android.R.string.cancel, null).show()
        }
    }

    /* ---------------------------------------------------------- */
    /*  Live Reload                                                */
    /* ---------------------------------------------------------- */

    private fun setupLiveReloadSection() {
        val sw = findViewById<Switch>(R.id.liveReloadSwitch) ?: return
        val status = findViewById<TextView>(R.id.liveReloadStatus)
        val restartHint = findViewById<TextView>(R.id.restartHint)

        fun refreshStatus() {
            status?.text = when {
                !prefs.liveReloadEnabled     -> getString(R.string.lr_status_off)
                LiveReloadManager.isEnabled  -> getString(R.string.lr_status_running,
                                                    LiveReloadManager.clientCount)
                else                         -> getString(R.string.lr_status_pending)
            }
        }
        sw.isChecked = prefs.liveReloadEnabled
        refreshStatus()
        sw.setOnCheckedChangeListener { _, checked ->
            if (checked) LiveReloadManager.enable(this)
            else         LiveReloadManager.disable(this)
            refreshStatus()
            restartHint.visibility = View.VISIBLE
        }
    }

    /* ---------------------------------------------------------- */
    /*  Web server engine picker                                   */
    /* ---------------------------------------------------------- */

    private fun setupWebServerSection() {
        val row   = findViewById<View>(R.id.webServerRow)
        val value = findViewById<TextView>(R.id.webServerValue)
        val restartHint = findViewById<TextView>(R.id.restartHint)

        value.text = prefs.webServer.displayName

        row.setOnClickListener {
            val engines = WebServerEngine.entries
            // Two-line labels: "Nginx\n · Battle-tested, event-driven, fast"
            val labels = engines.map { e ->
                "${e.displayName}\n${e.tagline}"
            }.toTypedArray()
            val current = engines.indexOf(prefs.webServer).coerceAtLeast(0)

            AlertDialog.Builder(this)
                .setTitle(R.string.web_server_dialog_title)
                .setSingleChoiceItems(labels, current) { d, which ->
                    val chosen = engines[which]
                    prefs.webServer = chosen
                    value.text = chosen.displayName
                    restartHint.visibility = View.VISIBLE
                    if (!chosen.supportsPhp) {
                        Toast.makeText(
                            this,
                            getString(R.string.web_server_no_php_warning, chosen.displayName),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    d.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /* ---------------------------------------------------------- */
    /*  HTTPS                                                     */
    /* ---------------------------------------------------------- */

    private fun setupHttpsSection() {
        val httpsSwitch    = findViewById<Switch>(R.id.httpsSwitch)
        val httpsPortRow   = findViewById<View>(R.id.httpsPortRow)
        val httpsPortValue = findViewById<TextView>(R.id.httpsPortValue)
        val httpsFpRow     = findViewById<View>(R.id.httpsFpRow)
        val httpsFpValue   = findViewById<TextView>(R.id.httpsFpValue)
        val restartHint    = findViewById<TextView>(R.id.restartHint)

        fun refreshFp() {
            val fp = prefs.httpsFingerprint
            httpsFpValue.text = if (fp.isEmpty()) getString(R.string.https_fp_none)
                                else fp.take(23) + "…"
        }
        httpsSwitch.isChecked = prefs.httpsEnabled
        httpsPortValue.text = prefs.httpsPort.toString()
        refreshFp()

        httpsSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.httpsEnabled = checked
            restartHint.visibility = View.VISIBLE
            if (checked && prefs.httpsFingerprint.isEmpty()) {
                Toast.makeText(this, R.string.https_cert_will_generate, Toast.LENGTH_LONG).show()
            }
        }
        httpsPortRow.setOnClickListener {
            promptPort(
                title   = getString(R.string.https_port_dialog),
                message = getString(R.string.http_port_dialog_msg),
                current = prefs.httpsPort
            ) { n ->
                prefs.httpsPort = n
                httpsPortValue.text = n.toString()
                restartHint.visibility = View.VISIBLE
            }
        }
        httpsFpRow.setOnClickListener {
            val fp = prefs.httpsFingerprint
            val msg = if (fp.isEmpty()) getString(R.string.https_fp_none_long)
                      else getString(R.string.https_fp_msg, fp)
            AlertDialog.Builder(this)
                .setTitle(R.string.https_fp_title)
                .setMessage(msg)
                .setPositiveButton(R.string.https_regenerate) { _, _ ->
                    // Nuke the cert; AssetInstaller will regenerate on next start.
                    val prefix = java.io.File(filesDir, com.inweb.app.Constants.ASSET_ROOT)
                    val sslDir = java.io.File(prefix, "ssl")
                    sslDir.listFiles()?.forEach { it.delete() }
                    prefs.httpsFingerprint = ""
                    refreshFp()
                    Toast.makeText(this, R.string.https_regenerated, Toast.LENGTH_SHORT).show()
                    restartHint.visibility = View.VISIBLE
                }
                .setNeutralButton(android.R.string.cancel, null)
                .show()
        }
    }

    /* ---------------------------------------------------------- */
    /*  General (theme + autostart)                                */
    /* ---------------------------------------------------------- */

    private fun setupGeneralSection() {
        val autoStartSwitch = findViewById<Switch>(R.id.autoStartSwitch)
        val themeRow        = findViewById<View>(R.id.themeRow)
        val themeValue      = findViewById<TextView>(R.id.themeValue)
        val langRow         = findViewById<View>(R.id.langRow)
        val langValue       = findViewById<TextView>(R.id.langValue)

        langValue.text = AppLocale.current().displayName
        langRow.setOnClickListener {
            val locales = AppLocale.entries
            val labels  = locales.map { it.displayName }.toTypedArray()
            val currentIdx = locales.indexOf(AppLocale.current()).coerceAtLeast(0)
            AlertDialog.Builder(this)
                .setTitle(R.string.language_dialog_title)
                .setSingleChoiceItems(labels, currentIdx) { d, which ->
                    AppLocale.apply(locales[which])
                    langValue.text = locales[which].displayName
                    d.dismiss()
                    recreate()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        autoStartSwitch.isChecked = prefs.autoStartOnBoot
        autoStartSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.autoStartOnBoot = checked
        }

        themeValue.text = prefs.themeMode.displayName
        themeRow.setOnClickListener {
            val modes = ThemeMode.entries
            val labels = modes.map { it.displayName }.toTypedArray()
            val current = modes.indexOf(prefs.themeMode)
            AlertDialog.Builder(this)
                .setTitle(R.string.theme_dialog_title)
                .setSingleChoiceItems(labels, current) { d, which ->
                    val chosen = modes[which]
                    prefs.themeMode = chosen
                    themeValue.text = chosen.displayName
                    ThemeMode.apply(chosen)
                    d.dismiss()
                    // Recreate so the theme applies to the currently-visible screen.
                    recreate()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }



    /* ---------------------------------------------------------- */
    /*  Network                                                   */
    /* ---------------------------------------------------------- */

    private fun setupNetworkSection() {
        val bindSwitch  = findViewById<Switch>(R.id.bindSwitch)
        val portValue   = findViewById<TextView>(R.id.portValue)
        val portRow     = findViewById<View>(R.id.portRow)
        val restartHint = findViewById<TextView>(R.id.restartHint)

        bindSwitch.isChecked = prefs.bindLan
        portValue.text = prefs.httpPort.toString()

        bindSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.bindLan = checked
            restartHint.visibility = View.VISIBLE
        }

        portRow.setOnClickListener {
            promptPort(
                title = getString(R.string.http_port_dialog_title),
                message = getString(R.string.http_port_dialog_msg),
                current = prefs.httpPort
            ) { n ->
                prefs.httpPort = n
                portValue.text = n.toString()
                restartHint.visibility = View.VISIBLE
            }
        }
    }

    /* ---------------------------------------------------------- */
    /*  Database                                                  */
    /* ---------------------------------------------------------- */

    private fun setupDatabaseSection() {
        val dbSwitch    = findViewById<Switch>(R.id.dbEnableSwitch)
        val dbPortValue = findViewById<TextView>(R.id.dbPortValue)
        val dbPortRow   = findViewById<View>(R.id.dbPortRow)
        val dbPwRow     = findViewById<View>(R.id.dbPwRow)
        val restartHint = findViewById<TextView>(R.id.restartHint)
        val openPmaBtn  = findViewById<View>(R.id.openPmaBtn)

        dbSwitch.isChecked = prefs.mysqlEnabled
        dbPortValue.text = prefs.mysqlPort.toString()

        dbSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.mysqlEnabled = checked
            restartHint.visibility = View.VISIBLE
        }

        dbPortRow.setOnClickListener {
            promptPort(
                title = "MariaDB port",
                message = getString(R.string.http_port_dialog_msg),
                current = prefs.mysqlPort
            ) { n ->
                prefs.mysqlPort = n
                dbPortValue.text = n.toString()
                restartHint.visibility = View.VISIBLE
            }
        }

        dbPwRow.setOnClickListener { showRootPasswordDialog() }

        openPmaBtn.setOnClickListener {
            val port = prefs.httpPort
            val url = "http://localhost:$port/phpmyadmin/"
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            catch (_: Throwable) { toast(getString(R.string.no_browser)) }
        }
    }

    private fun showRootPasswordDialog() {
        val pw = prefs.mysqlRootPassword
        val body = if (pw.isEmpty()) getString(R.string.db_root_not_ready) else pw

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.db_root_dialog_title)
            .setMessage(body)
            .setNegativeButton(R.string.db_regenerate) { _, _ -> confirmRegenerate() }
            .setNeutralButton(android.R.string.cancel, null)

        if (pw.isNotEmpty()) {
            dialog.setPositiveButton(R.string.db_copy_pw) { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("mysql root pw", pw))
                toast(getString(R.string.db_password_copied))
            }
        }
        dialog.show()
    }

    private fun confirmRegenerate() {
        AlertDialog.Builder(this)
            .setTitle(R.string.db_regenerate_confirm_title)
            .setMessage(R.string.db_regenerate_confirm_msg)
            .setPositiveButton(R.string.db_regenerate) { _, _ ->
                prefs.mysqlRootPassword = ""
                prefs.mysqlInitialised = false
                toast(getString(R.string.db_regenerate_done))
                findViewById<View>(R.id.restartHint).visibility = View.VISIBLE
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /* ---------------------------------------------------------- */
    /*  Shared                                                    */
    /* ---------------------------------------------------------- */

    private fun promptPort(title: String, message: String, current: Int, onOk: (Int) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(current.toString())
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val n = input.text.toString().toIntOrNull()
                if (n == null || n !in 1024..65535) {
                    toast(getString(R.string.port_range_error))
                } else onOk(n)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
