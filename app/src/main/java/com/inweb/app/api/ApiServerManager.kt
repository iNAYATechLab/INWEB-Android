package com.inweb.app.api

import android.content.Context
import android.util.Log
import com.inweb.app.util.Prefs

/**
 * Singleton wrapper for the INWEB REST API server.
 *
 * Lifecycle:
 *   – Auto-generates a Bearer token on first launch (persisted in Prefs).
 *   – Starts alongside the main web server if the user has it enabled.
 *   – Stops on server shutdown.
 */
object ApiServerManager {

    private const val TAG = "ApiServerMgr"

    @Volatile private var server: InwebApiServer? = null

    val isRunning: Boolean get() = server?.isRunning == true

    fun enable(context: Context) {
        Prefs(context).apiEnabled = true
        startInternal(context)
    }

    fun disable(context: Context) {
        Prefs(context).apiEnabled = false
        stopInternal()
    }

    fun autoStartIfEnabled(context: Context) {
        if (Prefs(context).apiEnabled) startInternal(context)
    }

    fun autoStopIfEnabled() = stopInternal()

    /**
     * The Bearer token clients must supply. Generated on first access.
     * Persisted so the same token works across restarts (users bookmark
     * their PWA with `?token=…`).
     */
    fun tokenFor(context: Context): String {
        val prefs = Prefs(context)
        val current = prefs.apiToken
        if (current.isNotBlank()) return current
        val fresh = generateToken()
        prefs.apiToken = fresh
        return fresh
    }

    /** Force a new token (rotate — invalidates any bookmarked PWA sessions). */
    fun regenerateToken(context: Context): String {
        val fresh = generateToken()
        Prefs(context).apiToken = fresh
        // Restart to pick up new token.
        if (isRunning) {
            stopInternal(); startInternal(context)
        }
        return fresh
    }

    /* --------------------------------------------------------------- */

    private fun startInternal(context: Context) {
        if (isRunning) return
        val prefs = Prefs(context)
        val srv = InwebApiServer(
            context     = context.applicationContext,
            port        = prefs.apiPort,
            bearerToken = tokenFor(context)
        )
        srv.start()
        server = srv
        Log.i(TAG, "API enabled on port ${prefs.apiPort}")
    }

    private fun stopInternal() {
        server?.stop(); server = null
    }

    private fun generateToken(): String {
        val alphabet = ("ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789").toCharArray()
        val rnd = java.security.SecureRandom()
        return CharArray(32) { alphabet[rnd.nextInt(alphabet.size)] }.concatToString()
    }
}
