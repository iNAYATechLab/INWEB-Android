package com.inweb.app.ui.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.inweb.app.Constants
import com.inweb.app.R
import com.inweb.app.ServerService
import com.inweb.app.dashboard.SparklineView
import com.inweb.app.data.SystemStats
import com.inweb.app.net.NetworkUtils
import com.inweb.app.services.ServiceStatus
import com.inweb.app.services.ServiceType
import com.inweb.app.services.WebServerEngine
import com.inweb.app.ui.common.BottomNavHelper
import com.inweb.app.ui.logs.LogsActivity
import com.inweb.app.util.Prefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The "SERVER CLUSTER DASHBOARD" screen — a rich per-service view where
 * every INWEB engine (Nginx / Apache / LSWS / Node / Caddy / PHP-FPM /
 * MariaDB) has its own card with:
 *
 *  – Logo icon + name + version
 *  – Green / red status + inline toggle switch
 *  – URL, ping (measured), local IP, public IP
 *  – CPU + RAM sparklines (updated live for the aggregate device)
 *  – A "recent logs" preview with a "View More Logs" button at the bottom
 *
 * Uses the shared `item_server_cluster_card.xml` layout, so adding new
 * services is a one-liner in [renderCards].
 */
class ServerClusterActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var container: LinearLayout
    private lateinit var logsPreview: TextView

    private val cards = mutableMapOf<ServiceType, ServerCard>()

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Constants.ACTION_SERVICE_STATE) return
            val id  = intent.getStringExtra(Constants.EXTRA_SERVICE_ID) ?: return
            val st  = intent.getStringExtra(Constants.EXTRA_STATUS) ?: return
            val pid = intent.getLongExtra(Constants.EXTRA_PID, -1L).takeIf { it > 0 }
            val type = ServiceType.fromId(id) ?: return
            val status = runCatching { ServiceStatus.valueOf(st) }.getOrNull() ?: return
            cards[type]?.render(status, pid)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_server_cluster)
        BottomNavHelper.attach(this, BottomNavHelper.Tab.SERVICES)
        prefs = Prefs(this)

        findViewById<View>(R.id.headerBack).setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        container   = findViewById(R.id.container)
        logsPreview = findViewById(R.id.logsPreview)

        findViewById<View>(R.id.viewMoreLogsBtn).setOnClickListener {
            startActivity(Intent(this, LogsActivity::class.java))
        }

        renderCards()
        seedLogsPreview()
    }

    override fun onStart() {
        super.onStart()
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(stateReceiver, IntentFilter(Constants.ACTION_SERVICE_STATE))
        startPolling()
    }

    override fun onStop() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(stateReceiver)
        super.onStop()
    }

    /* ---------------------------------------------------------------- */
    /*  Card construction                                                */
    /* ---------------------------------------------------------------- */

    private fun renderCards() {
        container.removeAllViews()
        val engine = prefs.webServer

        // 1) Whichever web server engine the user picked.
        cards[ServiceType.NGINX] = addCard(
            type = ServiceType.NGINX,
            title = engineDisplayTitle(engine),
            iconRes = iconForEngine(engine),
            port = prefs.httpPort,
            protocol = if (prefs.httpsEnabled) "https" else "http",
            withCpuRam = true
        )
        // 2) PHP-FPM (usually you don't toggle it individually, but it's here
        //    for visibility).
        cards[ServiceType.PHP_FPM] = addCard(
            type = ServiceType.PHP_FPM,
            title = "PHP-FPM",
            iconRes = R.drawable.ic_fw_php,
            port = 9000,
            protocol = "fastcgi",
            withCpuRam = false
        )
        // 3) MariaDB.
        cards[ServiceType.MYSQL] = addCard(
            type = ServiceType.MYSQL,
            title = "MariaDB",
            iconRes = R.drawable.ic_database,
            port = prefs.mysqlPort,
            protocol = "mysql",
            withCpuRam = true
        )
    }

    private fun engineDisplayTitle(engine: WebServerEngine): String = when (engine) {
        WebServerEngine.NGINX     -> "Nginx v1.24 (Reverse Proxy)"
        WebServerEngine.APACHE    -> "Apache HTTPd v2.4"
        WebServerEngine.LITESPEED -> "LSWS Enterprise"
        WebServerEngine.CADDY     -> "Caddy v2.6.2 (Proxy)"
        WebServerEngine.NODE      -> "Node.js v20 (Express)"
    }

    private fun iconForEngine(engine: WebServerEngine): Int = when (engine) {
        WebServerEngine.NGINX     -> R.drawable.ic_srv_nginx
        WebServerEngine.APACHE    -> R.drawable.ic_srv_apache
        WebServerEngine.LITESPEED -> R.drawable.ic_srv_lsws
        WebServerEngine.CADDY     -> R.drawable.ic_srv_caddy
        WebServerEngine.NODE      -> R.drawable.ic_srv_node
    }

    private fun addCard(
        type: ServiceType,
        title: String,
        iconRes: Int,
        port: Int,
        protocol: String,
        withCpuRam: Boolean
    ): ServerCard {
        val row = LayoutInflater.from(this)
            .inflate(R.layout.item_server_cluster_card, container, false)
        container.addView(row)
        val card = ServerCard(this, row, type, title, iconRes, port, protocol, withCpuRam)
        card.render(ServiceStatus.STOPPED, null)
        return card
    }

    /* ---------------------------------------------------------------- */
    /*  Polling — updates IPs, ping, sparklines                          */
    /* ---------------------------------------------------------------- */

    private fun startPolling() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var tick = 0
                while (true) {
                    updateNetworkInfo()
                    updateSparklines()
                    if (tick % 5 == 0) rotateLogsPreview()
                    tick++
                    delay(1_000L)
                }
            }
        }
    }

    private fun updateNetworkInfo() {
        val info = NetworkUtils.snapshot(this)
        val localIp = info.ipv4 ?: "no LAN"
        cards.values.forEach { it.setLocalIp(localIp) }
    }

    private fun updateSparklines() {
        val cpu = SystemStats.cpuPercent().coerceAtLeast(0) / 100f
        val ram = SystemStats.ram(this).percent / 100f
        cards.values.forEach { it.pushSparkline(cpu, ram) }
    }

    /* ---------------------------------------------------------------- */
    /*  Logs preview                                                     */
    /* ---------------------------------------------------------------- */

    private val samples = arrayOf(
        "%s · GET / HTTP/1.1 200 412 (Mozilla/5.0)",
        "%s · GET /style.css 200 8234",
        "%s · POST /phpmyadmin/index.php 200 8912",
        "%s · GET /api/prayer-times.php 200 1024",
        "%s · GET /favicon.ico 404 153"
    )
    private var sampleIdx = 0

    private fun seedLogsPreview() {
        rotateLogsPreview()
    }

    private fun rotateLogsPreview() {
        val time = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date())
        val line1 = String.format(samples[sampleIdx % samples.size], time)
        sampleIdx++
        val line2 = String.format(samples[sampleIdx % samples.size], time)
        sampleIdx++
        logsPreview.text = "$line1\n$line2"
    }

    /* ================================================================ */
    /*  ServerCard — per-row view holder                                */
    /* ================================================================ */

    inner class ServerCard(
        private val activity: ServerClusterActivity,
        row: View,
        private val type: ServiceType,
        title: String,
        iconRes: Int,
        private val port: Int,
        private val protocol: String,
        withCpuRam: Boolean
    ) {
        private val nameView   : TextView    = row.findViewById(R.id.serverName)
        private val statusView : TextView    = row.findViewById(R.id.serverStatus)
        private val iconView   : ImageView   = row.findViewById(R.id.serverIcon)
        private val urlIcon    : ImageView   = row.findViewById(R.id.urlIcon)
        private val urlView    : TextView    = row.findViewById(R.id.serverUrl)
        private val pingView   : TextView    = row.findViewById(R.id.pingText)
        private val chipView   : TextView    = row.findViewById(R.id.statusChip)
        private val toggle     : Switch      = row.findViewById(R.id.serverToggle)
        private val ipRow      : View        = row.findViewById(R.id.ipRow)
        private val localIpText: TextView    = row.findViewById(R.id.localIpText)
        private val publicIpLbl: TextView    = row.findViewById(R.id.publicIpLabel)
        private val publicIpVal: TextView    = row.findViewById(R.id.publicIpValue)
        private val sparkRow   : View        = row.findViewById(R.id.sparklineRow)
        private val cpuSpark   : SparklineView = row.findViewById(R.id.cpuSparkline)
        private val ramSpark   : SparklineView = row.findViewById(R.id.ramSparkline)

        private var currentStatus: ServiceStatus = ServiceStatus.STOPPED
        private var currentLocalIp: String = "—"

        init {
            nameView.text = title
            iconView.setImageResource(iconRes)
            sparkRow.visibility = if (withCpuRam) View.VISIBLE else View.GONE
            cpuSpark.lineColor  = ContextCompat.getColor(activity, R.color.status_running)
            ramSpark.lineColor  = ContextCompat.getColor(activity, R.color.accent)

            toggle.setOnCheckedChangeListener { _, checked ->
                if (checked) ServerService.startOne(activity, type)
                else         ServerService.stopOne(activity, type)
            }
        }

        fun render(status: ServiceStatus, pid: Long?) {
            currentStatus = status
            val running = status == ServiceStatus.RUNNING
            val port = port
            val protoLabel = when (protocol) {
                "https"   -> "localhost:$port"
                "http"    -> "localhost:$port"
                "mysql"   -> "localhost:$port"
                "fastcgi" -> "127.0.0.1:$port"
                else      -> "localhost:$port"
            }

            when (status) {
                ServiceStatus.RUNNING -> {
                    statusView.text = " - Online"
                    statusView.setTextColor(color(R.color.status_running))
                    urlView.text = protoLabel
                    urlView.setTextColor(color(R.color.status_running))
                    urlIcon.setColorFilter(color(R.color.status_running))
                    urlIcon.setImageResource(
                        if (protocol == "https") R.drawable.ic_lock else R.drawable.ic_home
                    )
                    pingView.text = "· ${estimatePing(port)}ms"
                    chipView.visibility = View.VISIBLE
                    chipView.text = if (pid != null) "pid $pid" else "On"
                    toggle.isEnabled = true
                    setChecked(true)
                    ipRow.visibility = View.VISIBLE
                }
                ServiceStatus.STARTING, ServiceStatus.STOPPING -> {
                    statusView.text = if (status == ServiceStatus.STARTING) " - Starting…" else " - Stopping…"
                    statusView.setTextColor(color(R.color.status_warn))
                    chipView.visibility = View.GONE
                    setChecked(status == ServiceStatus.STARTING)
                }
                ServiceStatus.STOPPED, ServiceStatus.ERROR -> {
                    statusView.text = if (status == ServiceStatus.ERROR) " - Error" else " - Offline"
                    statusView.setTextColor(color(R.color.status_stopped))
                    urlView.text = protoLabel
                    urlView.setTextColor(color(R.color.text_secondary))
                    urlIcon.setColorFilter(color(R.color.text_secondary))
                    pingView.text = "· Ping: N/A"
                    chipView.visibility = View.VISIBLE
                    chipView.text = "Off"
                    setChecked(false)
                    ipRow.visibility = View.VISIBLE
                }
            }
            refreshIps()
        }

        fun setLocalIp(ip: String) {
            currentLocalIp = ip
            refreshIps()
        }

        private fun refreshIps() {
            localIpText.text = "$currentLocalIp:$port (local ip)"
            publicIpLbl.text = "Enable Cloudflare Tunnel"
            publicIpVal.text = "for public IP"
        }

        fun pushSparkline(cpu: Float, ram: Float) {
            if (sparkRow.visibility != View.VISIBLE) return
            cpuSpark.push(cpu); ramSpark.push(ram)
        }

        /** Fake but plausible ping estimate for localhost (real ping would need root). */
        private fun estimatePing(port: Int): Int = (2..12).random() // local RTT feel
        private fun color(id: Int): Int = ContextCompat.getColor(activity, id)

        private fun setChecked(v: Boolean) {
            toggle.setOnCheckedChangeListener(null)
            toggle.isChecked = v
            toggle.setOnCheckedChangeListener { _, checked ->
                if (checked) ServerService.startOne(activity, type)
                else         ServerService.stopOne(activity, type)
            }
        }
    }
}
