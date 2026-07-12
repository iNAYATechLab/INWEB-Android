package com.inweb.app.livereload

import android.content.Context
import android.util.Log
import com.inweb.app.Constants
import com.inweb.app.util.Prefs
import java.io.File

/**
 * Owns the LiveReload lifecycle:
 *
 *   – Starts / stops the WebSocket server on port 35729
 *   – Watches the web root for file changes
 *   – Broadcasts "reload" over the WebSocket on every change
 *
 * Singleton so all activities can share the same instance.
 */
object LiveReloadManager {

    private const val TAG = "LiveReload"

    private var server: LiveReloadServer? = null
    private var watcher: FileWatcher? = null

    val isEnabled: Boolean get() = server?.isRunning == true
    val clientCount: Int get() = server?.clientCount ?: 0

    /**
     * Enable Live Reload — starts the WebSocket server and the file watcher.
     * Also persists the preference so the next launch re-enables it.
     */
    fun enable(context: Context) {
        val prefs = Prefs(context)
        prefs.liveReloadEnabled = true
        startInternal(context)
    }

    fun disable(context: Context) {
        Prefs(context).liveReloadEnabled = false
        stopInternal()
    }

    /** Called from ServerService whenever the server starts, so LiveReload
     *  automatically comes up in step with it (if the user has enabled it). */
    fun autoStartIfEnabled(context: Context) {
        if (Prefs(context).liveReloadEnabled) startInternal(context)
    }

    fun autoStopIfEnabled() = stopInternal()

    /* ---------------------------------------------------------------- */

    private fun startInternal(context: Context) {
        if (isEnabled) return
        val srv = LiveReloadServer()
        srv.start()
        server = srv

        val extRoot = context.getExternalFilesDir(null) ?: context.filesDir
        val docRoot = File(extRoot, Constants.WWW_DIR)
        val fw = FileWatcher(docRoot) { changedPath ->
            Log.i(TAG, "file changed: $changedPath")
            val rel = changedPath.removePrefix(docRoot.absolutePath)
            srv.broadcastReload(rel.ifEmpty { changedPath })
        }
        fw.start()
        watcher = fw
        Log.i(TAG, "LiveReload started — watching ${docRoot.absolutePath}")
    }

    private fun stopInternal() {
        watcher?.stop(); watcher = null
        server?.stop(); server = null
        Log.i(TAG, "LiveReload stopped")
    }
}
