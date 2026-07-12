package com.inweb.app.dns

import android.content.Context
import java.util.concurrent.atomic.AtomicReference

/**
 * The heart of INWEB's custom DNS. Answers "does this hostname have a
 * user-defined override?" and, if not, defers to the upstream resolver
 * (system DNS via UDP query in Stage 3).
 *
 * Also serves the virtual-host domains automatically: any hostname that
 * matches an *enabled* [com.inweb.app.vhost.VirtualHost.serverName]
 * resolves to `127.0.0.1` — no manual host entry needed.
 *
 * Thread-safe & lock-free — uses [AtomicReference] snapshot swap.
 */
object InwebDnsResolver {

    /** Snapshot of "hostname → IPv4" merged from vhosts + host entries. */
    private val snapshot = AtomicReference<Map<String, String>>(emptyMap())

    /** Bump this and cache is transparently rebuilt on next query. */
    private val dirty = AtomicReference(true)

    /** Resolve one hostname. Returns null if we don't have an override. */
    fun resolveOverride(host: String): String? {
        val h = host.lowercase().trimEnd('.')
        return currentSnapshot()[h]
    }

    /** Full snapshot of every override currently in effect (read-only). */
    fun overrides(): Map<String, String> = currentSnapshot()

    /** Called by store on any change. Cheap. */
    fun invalidateCache() { dirty.set(true) }

    /**
     * Rebuild the snapshot from the store + running vhosts.
     * MUST be called at least once with a real [Context] before the DNS
     * server / VPN service starts serving queries.
     */
    fun rebuild(context: Context) {
        val map = HashMap<String, String>()

        // Vhosts → 127.0.0.1 (they always live on this device)
        val vhostStore = com.inweb.app.vhost.VirtualHostStore(context)
        for (vh in vhostStore.all()) {
            if (vh.enabled && vh.serverName.isNotBlank()) {
                map[vh.serverName.lowercase()] = "127.0.0.1"
            }
        }

        // User-defined host entries override vhosts (more specific wins).
        val hostStore = HostEntryStore(context)
        for ((h, ip) in hostStore.asMap()) map[h] = ip

        snapshot.set(map)
        dirty.set(false)
    }

    /* --------------------------------------------------------------- */

    private fun currentSnapshot(): Map<String, String> = snapshot.get() ?: emptyMap()
}
