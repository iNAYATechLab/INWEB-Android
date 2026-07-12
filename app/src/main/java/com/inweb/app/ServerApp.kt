package com.inweb.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import com.inweb.app.ddns.DdnsWorker
import com.inweb.app.tile.ServerTileService
import com.inweb.app.util.Prefs
import com.inweb.app.util.ThemeMode
import com.inweb.app.widget.ServerWidgetProvider

class ServerApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Apply the user's preferred theme *before* any UI is inflated.
        ThemeMode.apply(Prefs(this).themeMode)

        createNotificationChannel()
        registerDynamicShortcuts()

        // Re-schedule the DDNS worker so periodic pushes survive app kills.
        DdnsWorker.schedule(this)
    }

    /* ---------------------------------------------------------------- */
    /*  Notification channel                                            */
    /* ---------------------------------------------------------------- */

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val existing = nm.getNotificationChannel(Constants.NOTIF_CHANNEL_ID)
            if (existing == null) {
                val ch = NotificationChannel(
                    Constants.NOTIF_CHANNEL_ID,
                    Constants.NOTIF_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Persistent notification while the local web server is running."
                    setShowBadge(false)
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    /* ---------------------------------------------------------------- */
    /*  Long-press launcher-icon shortcuts (Android 7.1+)               */
    /* ---------------------------------------------------------------- */

    private fun registerDynamicShortcuts() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return
        val sm = getSystemService(ShortcutManager::class.java) ?: return

        val startShortcut = ShortcutInfo.Builder(this, "start")
            .setShortLabel(getString(R.string.shortcut_start_short))
            .setLongLabel(getString(R.string.shortcut_start_long))
            .setIcon(Icon.createWithResource(this, R.drawable.ic_shortcut_start))
            .setIntent(
                Intent(this, MainActivity::class.java)
                    .setAction(ACTION_LAUNCH_START)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ).build()

        val stopShortcut = ShortcutInfo.Builder(this, "stop")
            .setShortLabel(getString(R.string.shortcut_stop_short))
            .setLongLabel(getString(R.string.shortcut_stop_long))
            .setIcon(Icon.createWithResource(this, R.drawable.ic_shortcut_stop))
            .setIntent(
                Intent(this, MainActivity::class.java)
                    .setAction(ACTION_LAUNCH_STOP)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ).build()

        sm.dynamicShortcuts = listOf(startShortcut, stopShortcut)
    }

    companion object {
        // Intent actions used by shortcuts to auto-trigger start/stop on launch.
        const val ACTION_LAUNCH_START = "com.inweb.app.LAUNCH_START"
        const val ACTION_LAUNCH_STOP  = "com.inweb.app.LAUNCH_STOP"

        /** Nudge Quick-Settings tile + home-screen widgets to redraw. */
        fun refreshUiSurfaces(ctx: Context) {
            ServerTileService.requestUpdate(ctx)
            ServerWidgetProvider.requestUpdate(ctx)
        }
    }
}
