package com.inweb.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.inweb.app.Constants
import com.inweb.app.R
import com.inweb.app.ServerService
import com.inweb.app.util.Prefs

/**
 * Home-screen widget: shows current status and a big Start/Stop button.
 *
 * Because widgets can't call [LocalBroadcastManager], we listen for our
 * *own* system-wide broadcast [ACTION_WIDGET_TOGGLE] and rely on
 * [ServerApp.refreshUiSurfaces] being called whenever the server state
 * changes to trigger [onUpdate].
 */
class ServerWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val running = readRunningFlag(context)
        for (id in appWidgetIds) {
            updateOne(context, appWidgetManager, id, running)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_WIDGET_TOGGLE -> {
                val running = readRunningFlag(context)
                if (running) ServerService.stop(context) else ServerService.start(context)
                // Optimistic flip so the widget feels snappy.
                writeRunningFlag(context, !running)
                pushUpdate(context)
            }
            ACTION_WIDGET_REFRESH -> pushUpdate(context)
        }
    }

    private fun updateOne(context: Context, mgr: AppWidgetManager, widgetId: Int, running: Boolean) {
        val views = RemoteViews(context.packageName, R.layout.widget_server)

        // Text and colours
        val statusText = if (running) R.string.status_running else R.string.status_stopped
        val bgColor = ContextCompat.getColor(
            context,
            if (running) R.color.btn_start else R.color.btn_stop
        )
        views.setTextViewText(R.id.widgetStatus, context.getString(statusText))
        views.setInt(R.id.widgetToggleBtn, "setBackgroundColor", bgColor)
        views.setTextViewText(
            R.id.widgetToggleBtn,
            context.getString(if (running) R.string.stop_server else R.string.start_server)
        )

        // Sub-line with URL when running.
        val urlLine = if (running) {
            "http://localhost:${Prefs(context).httpPort}"
        } else {
            context.getString(R.string.app_tagline)
        }
        views.setTextViewText(R.id.widgetSubtitle, urlLine)

        // Click → send our own broadcast which we handle in onReceive.
        val toggleIntent = Intent(context, ServerWidgetProvider::class.java)
            .setAction(ACTION_WIDGET_TOGGLE)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getBroadcast(context, 0, toggleIntent, flags)
        views.setOnClickPendingIntent(R.id.widgetToggleBtn, pi)

        // Tap on the card body → open the app.
        val openApp = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (openApp != null) {
            val openPi = PendingIntent.getActivity(context, 1, openApp, flags)
            views.setOnClickPendingIntent(R.id.widgetRoot, openPi)
        }

        mgr.updateAppWidget(widgetId, views)
    }

    /* --------------------------------------------------------------- */

    companion object {
        private const val PREF = "inweb_widget"
        private const val KEY_RUNNING = "server_running"
        const val ACTION_WIDGET_TOGGLE  = "com.inweb.app.WIDGET_TOGGLE"
        const val ACTION_WIDGET_REFRESH = "com.inweb.app.WIDGET_REFRESH"

        /** Called from anywhere in the app when the server flips on/off. */
        fun onStateChanged(ctx: Context, running: Boolean) {
            writeRunningFlag(ctx, running)
            pushUpdate(ctx)
        }

        /** Ask installed widgets to redraw. */
        fun requestUpdate(ctx: Context) = pushUpdate(ctx)

        private fun pushUpdate(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, ServerWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val intent = Intent(ctx, ServerWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            ctx.sendBroadcast(intent)
        }

        private fun writeRunningFlag(ctx: Context, running: Boolean) {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_RUNNING, running).apply()
        }

        private fun readRunningFlag(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_RUNNING, false)
    }
}
