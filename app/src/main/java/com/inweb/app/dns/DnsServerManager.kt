package com.inweb.app.dns

import android.content.Context
import android.util.Log
import com.inweb.app.util.Prefs

/**
 * Singleton wrapper around [DnsServer] so all UIs + ServerService share the
 * same instance. Handles auto-start when the app's preferences say so.
 */
object DnsServerManager {
    private const val TAG = "DnsServerMgr"

    @Volatile private var server: DnsServer? = null

    val isRunning: Boolean get() = server?.isRunning == true

    /** Persist "on" + start now. */
    fun enable(context: Context) {
        Prefs(context).dnsServerEnabled = true
        startInternal(context)
    }

    fun disable(context: Context) {
        Prefs(context).dnsServerEnabled = false
        stopInternal()
    }

    /** Called from ServerService.onStart — brings DNS up if user has it on. */
    fun autoStartIfEnabled(context: Context) {
        if (Prefs(context).dnsServerEnabled) startInternal(context)
    }

    fun autoStopIfEnabled() = stopInternal()

    private fun startInternal(context: Context) {
        if (isRunning) return
        val prefs = Prefs(context)
        InwebDnsResolver.rebuild(context)
        val srv = DnsServer(port = prefs.dnsServerPort)
        srv.start()
        server = srv
        Log.i(TAG, "DNS server enabled on port ${prefs.dnsServerPort}")
    }

    private fun stopInternal() {
        server?.stop(); server = null
    }
}
