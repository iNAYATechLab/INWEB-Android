package com.inweb.app

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.inweb.app.api.ApiServerManager
import com.inweb.app.dns.DnsServerManager
import com.inweb.app.livereload.LiveReloadManager
import com.inweb.app.services.ServiceStatus
import com.inweb.app.services.ServiceType
import com.inweb.app.tile.ServerTileService
import com.inweb.app.util.Prefs
import com.inweb.app.widget.ServerWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service that owns the multi-service lifecycle.
 *
 * Supports both "start all" (legacy) and "start one" (per-service tile).
 * Broadcasts state via two channels:
 *   – ACTION_STATE_CHANGED   (aggregate — used by MainActivity)
 *   – ACTION_SERVICE_STATE   (per-service — used by ServicesActivity)
 */
class ServerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var manager: ServerManager? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            Constants.ACTION_START     -> handleStartAll()
            Constants.ACTION_STOP      -> handleStopAll()
            Constants.ACTION_START_ONE -> {
                val id = intent.getStringExtra(Constants.EXTRA_SERVICE_ID)
                ServiceType.fromId(id)?.let { handleStartOne(it) }
            }
            Constants.ACTION_STOP_ONE -> {
                val id = intent.getStringExtra(Constants.EXTRA_SERVICE_ID)
                ServiceType.fromId(id)?.let { handleStopOne(it) }
            }
            else -> {
                if (manager?.isAnyRunning != true) handleStartAll()
            }
        }
        return START_STICKY
    }

    /* ---------------------------------------------------------------- */
    /*  Lifecycle handlers                                              */
    /* ---------------------------------------------------------------- */

    private fun handleStartAll() {
        startForegroundCompat(buildNotification("Starting…"))
        scope.launch {
            val ok = runCatching {
                val layout = AssetInstaller.install(this@ServerService)
                val mgr = ensureManager(layout)
                mgr.startAll()
                acquireWakeLock()
            }
            withContext(Dispatchers.Main) {
                ok.onSuccess {
                    val port = Prefs(this@ServerService).httpPort
                    updateNotification("INWEB running on http://localhost:$port")
                    LiveReloadManager.autoStartIfEnabled(this@ServerService)
                    DnsServerManager.autoStartIfEnabled(this@ServerService)
                    ApiServerManager.autoStartIfEnabled(this@ServerService)
                    broadcastAggregate(running = true, message = null)
                }.onFailure { err ->
                    Log.e(TAG, "Failed to start server", err)
                    broadcastAggregate(running = false, message = err.message ?: "Failed to start")
                    stopSelfSafely()
                }
            }
        }
    }

    private fun handleStopAll() {
        scope.launch {
            runCatching { manager?.stopAll() }
                .onFailure { Log.w(TAG, "Error while stopping all", it) }
            withContext(Dispatchers.Main) {
                broadcastAggregate(running = false, message = null)
                stopSelfSafely()
            }
        }
    }

    private fun handleStartOne(type: ServiceType) {
        startForegroundCompat(buildNotification("Starting ${type.displayName}…"))
        scope.launch {
            runCatching {
                val layout = AssetInstaller.install(this@ServerService)
                val mgr = ensureManager(layout)
                mgr.start(type)
                acquireWakeLock()
            }.onFailure { err ->
                Log.e(TAG, "Failed to start ${type.id}", err)
            }
            withContext(Dispatchers.Main) {
                refreshNotification()
                broadcastAggregate(running = manager?.isAnyRunning == true, message = null)
            }
        }
    }

    private fun handleStopOne(type: ServiceType) {
        scope.launch {
            runCatching { manager?.stop(type) }
                .onFailure { Log.w(TAG, "Error stopping ${type.id}", it) }
            withContext(Dispatchers.Main) {
                val stillRunning = manager?.isAnyRunning == true
                if (stillRunning) {
                    refreshNotification()
                } else {
                    stopSelfSafely()
                }
                broadcastAggregate(running = stillRunning, message = null)
            }
        }
    }

    private fun stopSelfSafely() {
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy — tearing down server")
        LiveReloadManager.autoStopIfEnabled()
        DnsServerManager.autoStopIfEnabled()
        ApiServerManager.autoStopIfEnabled()
        try { manager?.stopAll() } catch (t: Throwable) { Log.w(TAG, "stopAll() failed", t) }
        manager = null
        releaseWakeLock()
        scope.cancel()
        broadcastAggregate(running = false, message = null)
        super.onDestroy()
    }

    /* ---------------------------------------------------------------- */
    /*  Manager creation with per-service broadcast                     */
    /* ---------------------------------------------------------------- */

    private fun ensureManager(layout: AssetInstaller.Layout): ServerManager {
        manager?.let { return it }
        val mgr = ServerManager(this, layout) { type, status, msg, pid ->
            broadcastServiceState(type, status, msg, pid)
        }
        manager = mgr
        return mgr
    }

    /* ---------------------------------------------------------------- */
    /*  Notification                                                    */
    /* ---------------------------------------------------------------- */

    private fun buildNotification(text: String): Notification {
        val openAppPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, ServerService::class.java).setAction(Constants.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, Constants.NOTIF_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_server)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppPi)
            .addAction(0, getString(R.string.stop_server), stopPi)
            .build()
    }

    private fun refreshNotification() {
        val mgr = manager ?: return
        val parts = ServiceType.entries.filter { mgr.isRunning(it) }.map { it.displayName }
        val text = if (parts.isEmpty()) "Idle" else "Running: ${parts.joinToString(" · ")}"
        updateNotification(text)
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(Constants.NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Constants.NOTIF_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(Constants.NOTIF_ID, buildNotification(text))
    }

    /* ---------------------------------------------------------------- */
    /*  Wake lock                                                       */
    /* ---------------------------------------------------------------- */

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "INWEB::ServerWakeLock").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Throwable) {}
        wakeLock = null
    }

    /* ---------------------------------------------------------------- */
    /*  Broadcasts                                                       */
    /* ---------------------------------------------------------------- */

    private fun broadcastAggregate(running: Boolean, message: String?) {
        val i = Intent(Constants.ACTION_STATE_CHANGED)
            .putExtra(Constants.EXTRA_RUNNING, running)
            .putExtra(Constants.EXTRA_MESSAGE, message)
        LocalBroadcastManager.getInstance(this).sendBroadcast(i)

        // Keep the Quick-Settings tile and any home-screen widgets in sync.
        ServerWidgetProvider.onStateChanged(this, running)
        ServerTileService.requestUpdate(this)
    }

    private fun broadcastServiceState(type: ServiceType, status: ServiceStatus, msg: String?, pid: Long?) {
        val i = Intent(Constants.ACTION_SERVICE_STATE)
            .putExtra(Constants.EXTRA_SERVICE_ID, type.id)
            .putExtra(Constants.EXTRA_STATUS, status.name)
            .putExtra(Constants.EXTRA_MESSAGE, msg)
            .putExtra(Constants.EXTRA_PID, pid ?: -1L)
        LocalBroadcastManager.getInstance(this).sendBroadcast(i)
    }

    /* ---------------------------------------------------------------- */
    /*  Static helpers                                                  */
    /* ---------------------------------------------------------------- */

    companion object {
        private const val TAG = "ServerService"

        fun start(ctx: Context) = launch(ctx, Constants.ACTION_START)
        fun stop(ctx: Context)  = launch(ctx, Constants.ACTION_STOP)

        fun startOne(ctx: Context, type: ServiceType) =
            launch(ctx, Constants.ACTION_START_ONE) { it.putExtra(Constants.EXTRA_SERVICE_ID, type.id) }

        fun stopOne(ctx: Context, type: ServiceType) =
            launch(ctx, Constants.ACTION_STOP_ONE) { it.putExtra(Constants.EXTRA_SERVICE_ID, type.id) }

        private fun launch(ctx: Context, action: String, prep: (Intent) -> Unit = {}) {
            val i = Intent(ctx, ServerService::class.java).setAction(action).also(prep)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
    }
}
