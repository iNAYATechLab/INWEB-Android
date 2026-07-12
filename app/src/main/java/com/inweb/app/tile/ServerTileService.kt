package com.inweb.app.tile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.inweb.app.Constants
import com.inweb.app.R
import com.inweb.app.ServerService

/**
 * Notification-shade Quick-Settings tile.
 *
 * Tap → toggles the INWEB server on/off without opening the app.
 * The tile listens for [Constants.ACTION_STATE_CHANGED] broadcasts so its
 * label/icon stay in sync with the actual server state.
 */
@RequiresApi(Build.VERSION_CODES.N)
class ServerTileService : TileService() {

    /** True while the tile represents a running server. Local best-effort cache. */
    private var running: Boolean = false

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Constants.ACTION_STATE_CHANGED) return
            running = intent.getBooleanExtra(Constants.EXTRA_RUNNING, false)
            renderTile()
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(stateReceiver, IntentFilter(Constants.ACTION_STATE_CHANGED))
        renderTile()
    }

    override fun onStopListening() {
        try { LocalBroadcastManager.getInstance(this).unregisterReceiver(stateReceiver) } catch (_: Throwable) {}
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (running) {
            ServerService.stop(this)
        } else {
            ServerService.start(this)
        }
        // Optimistically flip the tile; the broadcast will correct us if needed.
        running = !running
        renderTile()
    }

    private fun renderTile() {
        val tile = qsTile ?: return
        if (running) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = getString(R.string.tile_running)
            tile.icon  = Icon.createWithResource(this, R.drawable.ic_stat_server)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = getString(R.string.tile_subtitle_running)
            }
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = getString(R.string.tile_stopped)
            tile.icon  = Icon.createWithResource(this, R.drawable.ic_stat_server)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = getString(R.string.tile_subtitle_stopped)
            }
        }
        tile.updateTile()
    }

    companion object {
        /**
         * Ask the system to redraw the tile (called from ServerService when
         * state changes so the tile refreshes even when not "listening").
         */
        fun requestUpdate(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
            try {
                TileService.requestListeningState(
                    ctx,
                    android.content.ComponentName(ctx, ServerTileService::class.java)
                )
            } catch (_: Throwable) { /* device may not have QS tiles */ }
        }
    }
}
