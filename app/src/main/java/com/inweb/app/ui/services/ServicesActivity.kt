package com.inweb.app.ui.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.inweb.app.Constants
import com.inweb.app.R
import com.inweb.app.ServerService
import com.inweb.app.services.ServiceStatus
import com.inweb.app.services.ServiceType
import com.inweb.app.ui.common.BottomNavHelper
import com.inweb.app.ui.common.PageScaffold
import com.inweb.app.util.Prefs

/**
 * Multi-service dashboard: one tile per supervised service
 * (Nginx, PHP-FPM, MariaDB). Each tile has its own status dot,
 * PID/port label, and a toggle button.
 *
 * Listens for [Constants.ACTION_SERVICE_STATE] broadcasts from
 * [ServerService] to update tiles in real time.
 */
class ServicesActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private val tiles = mutableMapOf<ServiceType, ServiceTile>()

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Constants.ACTION_SERVICE_STATE) return
            val id = intent.getStringExtra(Constants.EXTRA_SERVICE_ID) ?: return
            val statusName = intent.getStringExtra(Constants.EXTRA_STATUS) ?: return
            val pid = intent.getLongExtra(Constants.EXTRA_PID, -1L).takeIf { it > 0 }
            val message = intent.getStringExtra(Constants.EXTRA_MESSAGE)
            val type = ServiceType.fromId(id) ?: return
            val status = runCatching { ServiceStatus.valueOf(statusName) }.getOrNull() ?: return
            tiles[type]?.render(status, pid, message)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_services)
        PageScaffold.setup(this, getString(R.string.services_title)) {
            onBackPressedDispatcher.onBackPressed()
        }
        BottomNavHelper.attach(this, BottomNavHelper.Tab.SERVICES)
        prefs = Prefs(this)

        tiles[ServiceType.NGINX]   = ServiceTile(this, findViewById(R.id.nginxTile),   ServiceType.NGINX)
        tiles[ServiceType.PHP_FPM] = ServiceTile(this, findViewById(R.id.phpTile),     ServiceType.PHP_FPM)
        tiles[ServiceType.MYSQL]   = ServiceTile(this, findViewById(R.id.mysqlTile),   ServiceType.MYSQL)

        findViewById<Button>(R.id.startAllBtn).setOnClickListener { ServerService.start(this) }
        findViewById<Button>(R.id.stopAllBtn) .setOnClickListener { ServerService.stop(this) }

        tiles.values.forEach { it.render(ServiceStatus.STOPPED, null, null) }
    }

    override fun onStart() {
        super.onStart()
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(stateReceiver, IntentFilter(Constants.ACTION_SERVICE_STATE))
    }

    override fun onStop() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(stateReceiver)
        super.onStop()
    }

    /* ---------------------------------------------------------- */
    /*  Tile helper                                                */
    /* ---------------------------------------------------------- */

    inner class ServiceTile(
        private val activity: ServicesActivity,
        root: CardView,
        private val type: ServiceType
    ) {
        private val name:   TextView = root.findViewById(R.id.name)
        private val meta:   TextView = root.findViewById(R.id.meta)
        private val dot:    ImageView = root.findViewById(R.id.dot)
        private val status: TextView = root.findViewById(R.id.status)
        private val toggle: Button   = root.findViewById(R.id.toggle)

        private var currentStatus: ServiceStatus = ServiceStatus.STOPPED

        init {
            // Nginx tile actually represents "whichever web server the user chose".
            name.text = if (type == ServiceType.NGINX) prefs.webServer.displayName
                        else type.displayName
            toggle.setOnClickListener {
                when (currentStatus) {
                    ServiceStatus.RUNNING, ServiceStatus.STARTING ->
                        ServerService.stopOne(activity, type)
                    else ->
                        ServerService.startOne(activity, type)
                }
            }
        }

        fun render(s: ServiceStatus, pid: Long?, message: String?) {
            currentStatus = s
            val port = when (type) {
                ServiceType.NGINX   -> prefs.httpPort
                ServiceType.PHP_FPM -> type.defaultPort
                ServiceType.MYSQL   -> prefs.mysqlPort
            }

            when (s) {
                ServiceStatus.RUNNING -> {
                    dot.setImageResource(R.drawable.dot_green)
                    status.text = getString(R.string.svc_running)
                    status.setTextColor(color(R.color.status_running))
                    meta.text = if (pid != null) "port $port · pid $pid" else "port $port"
                    toggle.text = getString(R.string.stop)
                    toggle.setBackgroundColor(color(R.color.btn_stop))
                }
                ServiceStatus.STARTING -> {
                    dot.setImageResource(R.drawable.dot_amber)
                    status.text = getString(R.string.svc_starting)
                    status.setTextColor(color(R.color.status_warn))
                    meta.text = "port $port"
                    toggle.text = getString(R.string.stop)
                    toggle.setBackgroundColor(color(R.color.btn_stop))
                }
                ServiceStatus.STOPPING -> {
                    dot.setImageResource(R.drawable.dot_amber)
                    status.text = getString(R.string.svc_stopping)
                    status.setTextColor(color(R.color.status_warn))
                    meta.text = "port $port"
                    toggle.text = getString(R.string.stop)
                    toggle.setBackgroundColor(color(R.color.btn_stop))
                }
                ServiceStatus.STOPPED -> {
                    dot.setImageResource(R.drawable.dot_red)
                    status.text = getString(R.string.svc_stopped)
                    status.setTextColor(color(R.color.status_stopped))
                    meta.text = "port $port"
                    toggle.text = getString(R.string.start)
                    toggle.setBackgroundColor(color(R.color.btn_start))
                }
                ServiceStatus.ERROR -> {
                    dot.setImageResource(R.drawable.dot_red)
                    status.text = getString(R.string.svc_error)
                    status.setTextColor(color(R.color.status_stopped))
                    meta.text = message ?: getString(R.string.svc_check_logs)
                    toggle.text = getString(R.string.start)
                    toggle.setBackgroundColor(color(R.color.btn_start))
                }
            }
        }

        private fun color(id: Int): Int = ContextCompat.getColor(activity, id)
    }
}
