package com.inweb.app.vhost

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * On-disk storage for the user's virtual hosts. Uses a single JSON file
 * (`filesDir/vhosts.json`) so backups and manual edits are easy.
 *
 * Thread-safe via a monitor — read-heavy, write-rare pattern is fine.
 */
class VirtualHostStore(context: Context) {

    private val file = java.io.File(context.filesDir, FILE_NAME)
    private val lock = Any()

    /** All hosts, newest-first. */
    fun all(): List<VirtualHost> = synchronized(lock) {
        if (!file.exists()) return@synchronized emptyList()
        return@synchronized runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { arr.getJSONObject(it).toVhost() }
                .sortedByDescending { it.createdAt }
        }.getOrElse {
            Log.w(TAG, "Corrupted vhosts.json; starting fresh", it); emptyList()
        }
    }

    fun byId(id: String): VirtualHost? = all().firstOrNull { it.id == id }

    fun upsert(vh: VirtualHost) = synchronized(lock) {
        val list = all().toMutableList()
        val i = list.indexOfFirst { it.id == vh.id }
        if (i >= 0) list[i] = vh else list += vh
        save(list)
    }

    fun delete(id: String) = synchronized(lock) {
        save(all().filterNot { it.id == id })
    }

    fun setEnabled(id: String, enabled: Boolean) = synchronized(lock) {
        val list = all().map { if (it.id == id) it.copy(enabled = enabled) else it }
        save(list)
    }

    private fun save(list: List<VirtualHost>) {
        val arr = JSONArray()
        for (vh in list) arr.put(vh.toJson())
        file.writeText(arr.toString(2))
    }

    /* --------------------------------------------------------------- */

    private fun VirtualHost.toJson(): JSONObject = JSONObject().apply {
        put("id",           id)
        put("serverName",   serverName)
        put("documentRoot", documentRoot)
        put("phpMode",      phpMode.name)
        put("enabled",      enabled)
        put("label",        label)
        put("createdAt",    createdAt)
        val a = JSONArray()
        for (al in aliases) a.put(JSONObject()
            .put("urlPath", al.urlPath).put("fsPath", al.fsPath))
        put("aliases", a)
    }

    private fun JSONObject.toVhost(): VirtualHost {
        val aliases = mutableListOf<VirtualHost.Alias>()
        val a = optJSONArray("aliases")
        if (a != null) for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            aliases += VirtualHost.Alias(o.optString("urlPath"), o.optString("fsPath"))
        }
        return VirtualHost(
            id           = optString("id", java.util.UUID.randomUUID().toString()),
            serverName   = optString("serverName"),
            documentRoot = optString("documentRoot"),
            phpMode      = runCatching { VirtualHost.PhpMode.valueOf(optString("phpMode", "AUTO")) }
                              .getOrDefault(VirtualHost.PhpMode.AUTO),
            enabled      = optBoolean("enabled", true),
            label        = optString("label"),
            createdAt    = optLong("createdAt", System.currentTimeMillis()),
            aliases      = aliases,
        )
    }

    companion object {
        private const val TAG = "VirtualHostStore"
        private const val FILE_NAME = "vhosts.json"
    }
}
