package com.inweb.app.vhost

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Backup / restore for the user's virtual hosts.
 *
 * Format is the same JSON the store uses on disk (a plain array of
 * objects), plus an optional `_meta` field with an app version stamp.
 * That means an exported file can be opened in any text editor and
 * hand-edited before re-importing.
 */
object VHostImportExport {

    private const val META_KEY = "_meta"

    /** Serialise every vhost the user has to a JSON string. */
    fun exportToJson(context: Context): String {
        val hosts = VirtualHostStore(context).all()
        val arr = JSONArray()
        for (vh in hosts) arr.put(vh.toJson())
        return JSONObject()
            .put(META_KEY, JSONObject()
                .put("app",       "INWEB")
                .put("version",   1)
                .put("exportedAt", System.currentTimeMillis())
                .put("count",     hosts.size))
            .put("hosts", arr)
            .toString(2)
    }

    /**
     * Import from a JSON string. Returns the number of hosts imported.
     * Existing entries with the same id are overwritten.
     */
    fun importFromJson(context: Context, json: String): Int {
        val root = JSONObject(json)
        val arr = root.optJSONArray("hosts") ?: JSONArray(json)  // support raw array too
        val store = VirtualHostStore(context)
        var count = 0
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val vh = obj.toVhost()
            if (vh.serverName.isBlank()) continue
            store.upsert(vh); count++
        }
        return count
    }

    /** Read a URI (from a document picker) into a string. */
    fun readUri(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?.toString(Charsets.UTF_8)
        }.getOrNull()
    }

    /** Write [content] to a URI (from a create-document picker). */
    fun writeUri(context: Context, uri: Uri, content: String): Boolean {
        return runCatching {
            context.contentResolver.openOutputStream(uri, "wt")?.use {
                it.write(content.toByteArray(Charsets.UTF_8))
            } != null
        }.getOrElse { false }
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
        optJSONArray("aliases")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                aliases += VirtualHost.Alias(o.optString("urlPath"), o.optString("fsPath"))
            }
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
}
