package com.inweb.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.inweb.app.dashboard.PrayerTimeCalculator
import com.inweb.app.data.SystemStats
import com.inweb.app.databinding.ActivityMainBinding
import com.inweb.app.net.NetworkUtils
import com.inweb.app.tile.ServerTileService
import com.inweb.app.ui.files.FilesActivity
import com.inweb.app.ui.framework.FrameworksActivity
import com.inweb.app.ui.islamic.IslamicApisActivity
import com.inweb.app.ui.logs.LogsActivity
import com.inweb.app.ui.onboarding.OnboardingActivity
import com.inweb.app.ui.preview.PreviewActivity
import com.inweb.app.ui.services.ServerClusterActivity
import com.inweb.app.ui.services.ServicesActivity
import com.inweb.app.ui.share.NetworkInfoActivity
import com.inweb.app.ui.share.SettingsActivity
import com.inweb.app.util.Prefs
import com.inweb.app.widget.ServerWidgetProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * INWEB dashboard — inspired by professional server-status dashboards
 * with a distinctive Bangla / Islamic identity for INWEB.
 *
 *   ┌─────────────────────────────────────┐
 *   │ 🕌 Next prayer strip                │  ← Fajr 4:23 AM · 1h 34m
 *   ├─────────────────────────────────────┤
 *   │       ● SERVER STATUS               │
 *   │        OPERATIONAL                  │  ← Big status + Master toggle
 *   │  NGINX v1.24.0 | Localhost 8080     │
 *   ├───────────────┬─────────────────────┤
 *   │ CPU Usage 32% │ RAM 1.8/4 GB        │  ← 2×2 stat grid with sparklines
 *   │ ▁▂▃▅▇▆▄▃▂▁    │ ▓▓▓▓▓░░░░░          │
 *   ├───────────────┼─────────────────────┤
 *   │ Network ↑↓    │ Storage             │
 *   ├───────────────┴─────────────────────┤
 *   │ Active Sites                    + │  ← Web root + phpMyAdmin + APIs
 *   ├─────────────────────────────────────┤
 *   │ Local IP  · Public IP  (copy chip)  │
 *   ├─────────────────────────────────────┤
 *   │ Home | Sites | Logs | Terminal | ⋯  │  ← Bottom nav
 *   └─────────────────────────────────────┘
 */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs
    private var running: Boolean = false

    private val requestNotifPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) toast(getString(R.string.perm_denied_notif))
        }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Constants.ACTION_STATE_CHANGED) return
            val isRunning = intent.getBooleanExtra(Constants.EXTRA_RUNNING, false)
            renderServerState(isRunning)
            ServerWidgetProvider.onStateChanged(this@MainActivity, isRunning)
            ServerTileService.requestUpdate(this@MainActivity)
            intent.getStringExtra(Constants.EXTRA_MESSAGE)?.takeIf { it.isNotBlank() }
                ?.let { toast(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (!prefs.onboarded) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish(); return
        }

        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        // 🩺 আগের session-এ crash হয়ে থাকলে report দেখাও (একবার)
        com.inweb.app.util.CrashLogger.showPendingIfAny(this)

        // === In-app updater: silent GitHub Releases check (12h throttle) ===
        com.inweb.app.util.UpdateChecker.autoCheck(this)

        // === Header ===============================================
        b.userGreeting.text = getString(R.string.assalamu_alaikum)
        b.userName.text     = getString(R.string.app_name)   // could be user name later
        b.notifBtn.setOnClickListener  { toast(getString(R.string.notif_placeholder)) }
        b.settingsBtn.setOnClickListener{
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // === Prayer strip =========================================
        updatePrayerStrip()

        // === Master server toggle =================================
        b.serverToggle.setOnClickListener {
            if (running) ServerService.stop(this)
            else {
                maybeRequestNotificationPermission()
                ServerService.start(this)
            }
        }
        b.serverCard.setOnClickListener { openBestUrl() }
        b.serverUrl.setOnClickListener  { openBestUrl() }
        b.serverUrl.setOnLongClickListener {
            openInBrowser("http://localhost:${prefs.httpPort}"); true
        }

        // === Stat cards ===========================================
        // Colour the sparklines to match the semantic they carry.
        b.cpuSparkline.lineColor  = ContextCompat.getColor(this, R.color.accent)
        b.ramSparkline.lineColor  = ContextCompat.getColor(this, R.color.status_running)
        b.netSparklineIn.lineColor  = ContextCompat.getColor(this, R.color.status_running)
        b.netSparklineOut.lineColor = ContextCompat.getColor(this, R.color.accent)

        // === Active sites tiles ===================================
        b.addSiteBtn.setOnClickListener { startActivity(Intent(this, FilesActivity::class.java)) }
        // Tap → built-in preview; long-press → system browser (power-user escape hatch).
        b.siteWww.setOnClickListener        { openInPreview("http://localhost:${prefs.httpPort}/") }
        b.siteWww.setOnLongClickListener    { openInBrowser("http://localhost:${prefs.httpPort}/"); true }
        b.sitePma.setOnClickListener        { openInPreview("http://localhost:${prefs.httpPort}/phpmyadmin/") }
        b.sitePma.setOnLongClickListener    { openInBrowser("http://localhost:${prefs.httpPort}/phpmyadmin/"); true }
        b.siteIslamicApi.setOnClickListener     { openInPreview("http://localhost:${prefs.httpPort}/api/") }
        b.siteIslamicApi.setOnLongClickListener { openInBrowser("http://localhost:${prefs.httpPort}/api/"); true }

        // === IP info ==============================================
        b.copyLocalIp.setOnClickListener  { copy("local IP",  b.localIpText.text.toString()) }
        b.copyPublicIp.setOnClickListener { copy("public IP", b.publicIpText.text.toString()) }

        // === Bottom nav ===========================================
        b.navHome.setOnClickListener      { /* already here */ }
        b.navServices.setOnClickListener  { startActivity(Intent(this, ServerClusterActivity::class.java)) }
        b.navLogs.setOnClickListener      { startActivity(Intent(this, LogsActivity::class.java)) }
        b.navShare.setOnClickListener     { startActivity(Intent(this, NetworkInfoActivity::class.java)) }
        b.navMore.setOnClickListener      { showMoreSheet() }

        // Initial render — will be overwritten as soon as the first stats
        // tick fires (see startPolling below).
        renderServerState(false)
    }

    override fun onStart() {
        super.onStart()
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(stateReceiver, IntentFilter(Constants.ACTION_STATE_CHANGED))
        startPolling()
    }

    override fun onStop() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(stateReceiver)
        super.onStop()
    }

    /* ---------------------------------------------------------------- */
    /*  Polling loop: stats every 1s, prayer strip every 30s            */
    /* ---------------------------------------------------------------- */

    private fun startPolling() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var ticks = 0
                while (true) {
                    tickStats()
                    if (ticks % 30 == 0) updatePrayerStrip()
                    if (ticks % 5 == 0)  updateIpAddresses()
                    ticks++
                    delay(1_000L)
                }
            }
        }
    }

    private fun tickStats() {
        // CPU ---------------------------------------------------------
        val cpu = SystemStats.cpuPercent().coerceAtLeast(0)
        b.cpuValue.text = "$cpu%"
        b.cpuSparkline.push(cpu / 100f)

        // RAM ---------------------------------------------------------
        val ram = SystemStats.ram(this)
        b.ramValue.text = getString(
            R.string.ram_format,
            SystemStats.humanBytes(ram.usedBytes),
            SystemStats.humanBytes(ram.totalBytes)
        )
        b.ramBar.progress = ram.percent
        b.ramSparkline.push(ram.percent / 100f)

        // Storage -----------------------------------------------------
        val storage = SystemStats.storage()
        b.storageValue.text = getString(
            R.string.storage_format,
            SystemStats.humanBytes(storage.freeBytes),
            SystemStats.humanBytes(storage.totalBytes)
        )
        b.storageBar.progress = storage.percent

        // Network -----------------------------------------------------
        val net = SystemStats.networkIo()
        b.netInValue.text  = "↓ ${SystemStats.humanBytesPerSec(net.rxBytesPerSec)}"
        b.netOutValue.text = "↑ ${SystemStats.humanBytesPerSec(net.txBytesPerSec)}"
        // Normalise the sparkline to a rolling window using autoScale.
        b.netSparklineIn.autoScale  = true
        b.netSparklineOut.autoScale = true
        b.netSparklineIn.push(net.rxBytesPerSec.toFloat())
        b.netSparklineOut.push(net.txBytesPerSec.toFloat())
    }

    /* ---------------------------------------------------------------- */
    /*  Prayer strip                                                     */
    /* ---------------------------------------------------------------- */

    private fun updatePrayerStrip() {
        val now = System.currentTimeMillis()
        val timings = PrayerTimeCalculator.computeToday(latOrNull = null, lngOrNull = null)
        val (next, at) = timings.nextAfter(now)

        // Format time in the user's chosen 12/24-hour style.
        val hoursFmt = if (DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm a"
        val fmt = SimpleDateFormat(hoursFmt, Locale.getDefault())
        val nextName = if (isBanglaLocale()) next.labelBn else next.labelEn

        b.prayerName.text = getString(R.string.prayer_next_prefix, nextName)
        b.prayerTime.text = fmt.format(Date(at))
        b.prayerCountdown.text = humanCountdown(at - now)
    }

    private fun humanCountdown(ms: Long): String {
        if (ms <= 0) return "—"
        val totalMin = ms / 60_000
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h == 0L -> "${m}m"
            m == 0L -> "${h}h"
            else    -> "${h}h ${m}m"
        }
    }

    private fun isBanglaLocale(): Boolean =
        resources.configuration.locales[0].language == "bn"

    /* ---------------------------------------------------------------- */
    /*  Server state rendering                                          */
    /* ---------------------------------------------------------------- */

    private fun renderServerState(isRunning: Boolean) {
        running = isRunning
        if (isRunning) {
            b.statusText.text = getString(R.string.status_operational)
            b.statusText.setTextColor(color(R.color.status_running))
            b.statusDot.setImageResource(R.drawable.dot_green)
            b.statusSubtitle.text = getString(
                R.string.status_subtitle_running,
                "NGINX v1.24", "localhost:${prefs.httpPort}"
            )
            b.serverToggle.text = getString(R.string.stop_server_short)
            b.serverToggle.setBackgroundColor(color(R.color.btn_stop))
            b.serverUrl.visibility = View.VISIBLE
            b.serverUrl.text = "http://localhost:${prefs.httpPort}"
        } else {
            b.statusText.text = getString(R.string.status_offline)
            b.statusText.setTextColor(color(R.color.status_stopped))
            b.statusDot.setImageResource(R.drawable.dot_red)
            b.statusSubtitle.text = getString(R.string.status_subtitle_stopped)
            b.serverToggle.text = getString(R.string.start_server_short)
            b.serverToggle.setBackgroundColor(color(R.color.btn_start))
            b.serverUrl.visibility = View.GONE
        }
    }

    /* ---------------------------------------------------------------- */
    /*  IP addresses                                                     */
    /* ---------------------------------------------------------------- */

    private fun updateIpAddresses() {
        lifecycleScope.launch {
            val info = NetworkUtils.snapshot(this@MainActivity)
            val port = prefs.httpPort
            b.localIpText.text = info.ipv4?.let { "$it:$port" }
                ?: getString(R.string.ip_no_network)
            // We deliberately don't fetch the public IP over the network here
            // — that would send user IP to a third-party. Show a placeholder
            // that tells them to use the Tunnel screen if they need public.
            b.publicIpText.text = getString(R.string.ip_public_hint)
        }
    }

    /* ---------------------------------------------------------------- */
    /*  Menu sheet + helpers                                             */
    /* ---------------------------------------------------------------- */

    private fun showMoreSheet() {
        val items = arrayOf(
            getString(R.string.btn_sites),
            getString(R.string.btn_hosts),
            getString(R.string.btn_files),
            getString(R.string.btn_frameworks),
            getString(R.string.btn_islamic),
            getString(R.string.btn_security),
            getString(R.string.btn_ddns),
            getString(R.string.btn_settings)
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.more_title)
            .setItems(items) { _, which ->
                startActivity(Intent(this, when (which) {
                    0 -> com.inweb.app.ui.vhost.SitesActivity::class.java
                    1 -> com.inweb.app.ui.dns.HostsActivity::class.java
                    2 -> FilesActivity::class.java
                    3 -> FrameworksActivity::class.java
                    4 -> IslamicApisActivity::class.java
                    5 -> com.inweb.app.ui.security.SecurityActivity::class.java
                    6 -> com.inweb.app.ui.ddns.DdnsActivity::class.java
                    else -> SettingsActivity::class.java
                }))
            }.show()
    }

    private fun openBestUrl() {
        if (!running) { toast(getString(R.string.tap_start_first)); return }
        openInPreview("http://localhost:${prefs.httpPort}")
    }

    /** Preferred: open URL inside the built-in WebView preview. */
    private fun openInPreview(url: String) {
        if (!running) { toast(getString(R.string.tap_start_first)); return }
        PreviewActivity.open(this, url)
    }

    /** Fallback for long-press: hand off to the system browser. */
    private fun openInBrowser(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: Throwable) { toast(getString(R.string.no_browser)) }
    }

    private fun copy(label: String, value: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, value))
        toast(getString(R.string.copied_generic, value))
    }

    /**
     * 🔐 ইনস্টল/আপডেটের পরের প্রথম ওপেনেই দরকারি পারমিশনগুলো চেয়ে নেওয়া হয়
     * (Android platform ইনস্টল-টাইমে জিজ্ঞেস করে না — runtime-এই চাইতে হয়)।
     * Onboarding-এর পারমিশন স্টেপটা রিইউজ হচ্ছে, তাই লজিক ডুপ্লিকেট নয়।
     */
    private fun maybeRequestNotificationPermission() {
        if (prefs.permsAskedVersionCode == BuildConfig.VERSION_CODE) return
        prefs.permsAskedVersionCode = BuildConfig.VERSION_CODE

        val missing = com.inweb.app.util.PermissionCenter.missingCount(this)
        if (missing == 0) {
            // শুধু নোটিফিকেশন বাকি থাকলে সরাসরি রানটাইম ডায়ালগই যথেষ্ট
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestNotifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.perm_nudge_title)
            .setMessage(getString(R.string.perm_nudge_body, missing))
            .setPositiveButton(R.string.perm_allow_all) { _, _ ->
                startActivity(android.content.Intent(this, com.inweb.app.ui.onboarding.OnboardingActivity::class.java)
                    .putExtra(com.inweb.app.ui.onboarding.OnboardingActivity.EXTRA_PERMISSIONS_ONLY, true))
            }
            .setNegativeButton(R.string.perm_later, null)
            .show()
    }

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
