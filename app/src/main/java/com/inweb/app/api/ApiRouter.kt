package com.inweb.app.api

import android.content.Context
import android.util.Log
import com.inweb.app.dashboard.PrayerTimeCalculator
import com.inweb.app.data.SystemStats
import com.inweb.app.dns.HostEntry
import com.inweb.app.dns.HostEntryStore
import com.inweb.app.net.NetworkUtils
import com.inweb.app.services.ServiceType
import com.inweb.app.util.Prefs
import com.inweb.app.vhost.VirtualHost
import com.inweb.app.vhost.VirtualHostStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/**
 * Dispatches raw HTTP requests to typed handlers. Kept simple so new
 * endpoints are one-liners.
 */
class ApiRouter(private val context: Context) {

    private val prefs = Prefs(context)

    fun route(method: String, path: String, query: Map<String, String>, body: String): ApiResponse {
        return try {
            when {
                path == "/api/inweb/ping"        -> ping()
                path == "/api/inweb/status"      -> status()
                path == "/api/inweb/prefs" && method == "GET"  -> prefsAll()
                path == "/api/inweb/prefs" && method == "PUT"  -> prefsUpdate(body)
                path == "/api/inweb/vhosts" && method == "GET" -> vhostsAll()
                path == "/api/inweb/vhosts" && method == "POST"-> vhostUpsert(body)
                path.startsWith("/api/inweb/vhosts/") && method == "DELETE" ->
                    vhostDelete(path.removePrefix("/api/inweb/vhosts/"))
                path == "/api/inweb/hosts" && method == "GET"  -> hostsAll()
                path == "/api/inweb/hosts" && method == "POST" -> hostUpsert(body)
                path.startsWith("/api/inweb/hosts/") && method == "DELETE" ->
                    hostDelete(path.removePrefix("/api/inweb/hosts/"))
                path == "/api/inweb/logs"        -> logsTail(query)
                path == "/api/inweb/service/start" -> serviceControl(body, start = true)
                path == "/api/inweb/service/stop"  -> serviceControl(body, start = false)
                path == "/api/inweb/prayer-times" -> prayerTimes(query)
                else -> notFound()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "handler error: $method $path", t)
            error500(t.message)
        }
    }

    /* ---------------------------------------------------------------- */
    /*  Endpoints                                                       */
    /* ---------------------------------------------------------------- */

    private fun ping(): ApiResponse = ok(JSONObject()
        .put("pong",      true)
        .put("app",       "INWEB")
        .put("version",   "1.0.0")
        .put("apiVersion", 1))

    private fun status(): ApiResponse {
        val net = NetworkUtils.snapshot(context)
        val ram = SystemStats.ram(context)
        val storage = SystemStats.storage()
        return ok(JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("services", JSONObject().apply {
                put("engine",    prefs.webServer.id)
                put("httpPort",  prefs.httpPort)
                put("mysqlPort", prefs.mysqlPort)
                put("httpsEnabled",       prefs.httpsEnabled)
                put("liveReloadEnabled",  prefs.liveReloadEnabled)
                put("dnsServerEnabled",   prefs.dnsServerEnabled)
            })
            put("device", JSONObject().apply {
                put("localIp", net.ipv4 ?: JSONObject.NULL)
                put("ssid",    net.ssid ?: JSONObject.NULL)
                put("cpu",     SystemStats.cpuPercent())
                put("ramUsed", ram.usedBytes)
                put("ramTotal",ram.totalBytes)
                put("storageFree",  storage.freeBytes)
                put("storageTotal", storage.totalBytes)
            })
        })
    }

    private fun prefsAll(): ApiResponse = ok(JSONObject().apply {
        put("webServer",         prefs.webServer.id)
        put("httpPort",          prefs.httpPort)
        put("bindLan",           prefs.bindLan)
        put("mysqlEnabled",      prefs.mysqlEnabled)
        put("mysqlPort",         prefs.mysqlPort)
        put("httpsEnabled",      prefs.httpsEnabled)
        put("httpsPort",         prefs.httpsPort)
        put("liveReloadEnabled", prefs.liveReloadEnabled)
        put("dnsServerEnabled",  prefs.dnsServerEnabled)
        put("dnsServerPort",     prefs.dnsServerPort)
        put("themeMode",         prefs.themeMode.id)
    })

    /**
     * Body: any subset of keys → applies each mutation. Unknown keys ignored.
     */
    private fun prefsUpdate(body: String): ApiResponse {
        val j = JSONObject(body)
        if (j.has("bindLan"))           prefs.bindLan           = j.getBoolean("bindLan")
        if (j.has("httpPort"))          prefs.httpPort          = j.getInt("httpPort")
        if (j.has("mysqlEnabled"))      prefs.mysqlEnabled      = j.getBoolean("mysqlEnabled")
        if (j.has("mysqlPort"))         prefs.mysqlPort         = j.getInt("mysqlPort")
        if (j.has("httpsEnabled"))      prefs.httpsEnabled      = j.getBoolean("httpsEnabled")
        if (j.has("httpsPort"))         prefs.httpsPort         = j.getInt("httpsPort")
        if (j.has("liveReloadEnabled")) prefs.liveReloadEnabled = j.getBoolean("liveReloadEnabled")
        if (j.has("dnsServerEnabled"))  prefs.dnsServerEnabled  = j.getBoolean("dnsServerEnabled")
        return ok(JSONObject().put("updated", true))
    }

    private fun vhostsAll(): ApiResponse {
        val arr = JSONArray()
        VirtualHostStore(context).all().forEach { arr.put(vhostToJson(it)) }
        return ok(JSONObject().put("vhosts", arr))
    }

    private fun vhostUpsert(body: String): ApiResponse {
        val j = JSONObject(body)
        val vh = VirtualHost(
            id           = j.optString("id", java.util.UUID.randomUUID().toString()),
            serverName   = j.getString("serverName"),
            documentRoot = j.getString("documentRoot"),
            phpMode      = runCatching { VirtualHost.PhpMode.valueOf(j.optString("phpMode", "AUTO")) }
                              .getOrDefault(VirtualHost.PhpMode.AUTO),
            enabled      = j.optBoolean("enabled", true),
            label        = j.optString("label"),
        )
        VirtualHostStore(context).upsert(vh)
        return ok(vhostToJson(vh), 201)
    }

    private fun vhostDelete(id: String): ApiResponse {
        VirtualHostStore(context).delete(id)
        return ok(JSONObject().put("deleted", id))
    }

    private fun hostsAll(): ApiResponse {
        val arr = JSONArray()
        HostEntryStore(context).all().forEach {
            arr.put(JSONObject()
                .put("id",       it.id).put("hostname", it.hostname).put("ip", it.ip)
                .put("enabled",  it.enabled).put("note", it.note))
        }
        return ok(JSONObject().put("hosts", arr))
    }

    private fun hostUpsert(body: String): ApiResponse {
        val j = JSONObject(body)
        val hostname = j.getString("hostname").lowercase()
        val ip       = j.getString("ip")
        if (!HostEntry.validHostname(hostname)) return error400("invalid hostname")
        if (!HostEntry.validIpv4(ip))           return error400("invalid ipv4")
        val e = HostEntry(
            id       = j.optString("id", java.util.UUID.randomUUID().toString()),
            hostname = hostname, ip = ip,
            enabled  = j.optBoolean("enabled", true),
            note     = j.optString("note", "")
        )
        HostEntryStore(context).upsert(e)
        return ok(JSONObject()
            .put("id", e.id).put("hostname", e.hostname).put("ip", e.ip), 201)
    }

    private fun hostDelete(id: String): ApiResponse {
        HostEntryStore(context).delete(id)
        return ok(JSONObject().put("deleted", id))
    }

    /**
     * Query: file=access|error|php-fpm.error, bytes=<N>
     */
    private fun logsTail(query: Map<String, String>): ApiResponse {
        val which = query["file"] ?: "access"
        val bytes = (query["bytes"]?.toIntOrNull() ?: 8_192).coerceIn(512, 200_000)
        val logsDir = File(context.filesDir, "server_env/logs")
        val f = File(logsDir, "${which}.log")
        if (!f.exists()) return ok(JSONObject().put("content", "").put("size", 0))
        val len = f.length()
        val start = (len - bytes).coerceAtLeast(0L)
        val tail = RandomAccessFile(f, "r").use { raf ->
            raf.seek(start)
            val buf = ByteArray((len - start).toInt())
            raf.readFully(buf); String(buf, Charsets.UTF_8)
        }
        return ok(JSONObject()
            .put("file", which).put("size", len).put("content", tail))
    }

    /**
     * Body: { "service": "nginx" | "php-fpm" | "mysql" }
     * We only *record* the intent; MainActivity's broadcast handler wakes
     * up ServerService via the sticky INTENT it emits.
     */
    private fun serviceControl(body: String, start: Boolean): ApiResponse {
        val id = JSONObject(body).optString("service")
        val type = ServiceType.fromId(id) ?: return error400("unknown service '$id'")
        if (start) com.inweb.app.ServerService.startOne(context, type)
        else       com.inweb.app.ServerService.stopOne(context, type)
        return ok(JSONObject()
            .put("service", type.id)
            .put("action",  if (start) "start" else "stop"))
    }

    private fun prayerTimes(query: Map<String, String>): ApiResponse {
        val lat = query["lat"]?.toDoubleOrNull()
        val lng = query["lng"]?.toDoubleOrNull()
        val timings = PrayerTimeCalculator.computeToday(lat, lng)
        val out = JSONObject()
        for ((prayer, ms) in timings.timings) {
            out.put(prayer.name.lowercase(), ms)
        }
        return ok(JSONObject()
            .put("latitude",  lat ?: JSONObject.NULL)
            .put("longitude", lng ?: JSONObject.NULL)
            .put("timings",   out))
    }

    /* ---------------------------------------------------------------- */
    /*  Helpers                                                          */
    /* ---------------------------------------------------------------- */

    private fun vhostToJson(vh: VirtualHost) = JSONObject()
        .put("id",           vh.id)
        .put("serverName",   vh.serverName)
        .put("documentRoot", vh.documentRoot)
        .put("phpMode",      vh.phpMode.name)
        .put("enabled",      vh.enabled)
        .put("label",        vh.label)

    private fun ok(body: JSONObject, status: Int = 200) =
        ApiResponse(status, body.toString())

    private fun error400(msg: String) =
        ApiResponse(400, JSONObject().put("error", msg).toString())

    private fun error500(msg: String?) =
        ApiResponse(500, JSONObject().put("error", msg ?: "internal").toString())

    private fun notFound() =
        ApiResponse(404, JSONObject().put("error", "not found").toString())

    companion object { private const val TAG = "ApiRouter" }
}
