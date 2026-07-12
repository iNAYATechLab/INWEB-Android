package com.inweb.app.dns

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * JSON-backed store for user's host mappings.
 *
 *   filesDir/hosts.json
 *
 * Read-heavy / write-rare — a single monitor is plenty. Every mutation
 * writes the full file synchronously and (in production) triggers a
 * cache-refresh on [InwebDnsResolver].
 */
class HostEntryStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)
    private val lock = Any()

    fun all(): List<HostEntry> = synchronized(lock) {
        if (!file.exists()) return@synchronized emptyList()
        return@synchronized runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { arr.getJSONObject(it).toEntry() }
                .sortedBy { it.hostname.lowercase() }
        }.getOrElse {
            Log.w(TAG, "hosts.json corrupted — starting fresh", it); emptyList()
        }
    }

    /** All *enabled* entries, keyed by lowercase hostname → IP. */
    fun asMap(): Map<String, String> = synchronized(lock) {
        all().filter { it.enabled }.associate { it.hostname.lowercase() to it.ip }
    }

    fun byId(id: String): HostEntry? = all().firstOrNull { it.id == id }

    fun upsert(e: HostEntry) = synchronized(lock) {
        val list = all().toMutableList()
        val i = list.indexOfFirst { it.id == e.id }
        if (i >= 0) list[i] = e else list += e
        save(list); notifyChanged()
    }

    fun delete(id: String) = synchronized(lock) {
        save(all().filterNot { it.id == id }); notifyChanged()
    }

    fun setEnabled(id: String, enabled: Boolean) = synchronized(lock) {
        save(all().map { if (it.id == id) it.copy(enabled = enabled) else it })
        notifyChanged()
    }

    /* --------------------------------------------------------------- */

    private fun save(list: List<HostEntry>) {
        val arr = JSONArray()
        for (e in list) arr.put(JSONObject().apply {
            put("id",        e.id)
            put("hostname",  e.hostname)
            put("ip",        e.ip)
            put("enabled",   e.enabled)
            put("note",      e.note)
            put("createdAt", e.createdAt)
        })
        file.writeText(arr.toString(2))
    }

    private fun JSONObject.toEntry() = HostEntry(
        id        = optString("id", java.util.UUID.randomUUID().toString()),
        hostname  = optString("hostname"),
        ip        = optString("ip"),
        enabled   = optBoolean("enabled", true),
        note      = optString("note"),
        createdAt = optLong("createdAt", System.currentTimeMillis()),
    )

    /** Hook: notify observers (DnsResolver, VpnService) that data changed. */
    private fun notifyChanged() {
        InwebDnsResolver.invalidateCache()
    }

    companion object {
        private const val TAG = "HostEntryStore"
        private const val FILE_NAME = "hosts.json"
    }
}
