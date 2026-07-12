package com.inweb.app.ddns

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Talks to each DDNS provider's HTTP API to update the A record for the
 * user's chosen hostname.
 *
 * Zero external dependencies — pure `HttpURLConnection`. Runs on
 * Dispatchers.IO so it never blocks the UI.
 */
object DdnsClient {

    private const val TAG = "DdnsClient"
    private const val USER_AGENT = "INWEB-DDNS/1.0 (Android)"

    /** Result of a single push attempt. */
    sealed class Result {
        data class Success(val message: String, val ip: String) : Result()
        data class Failure(val message: String) : Result()
    }

    /**
     * Detects the current public IP and updates the DNS record.
     * Blocks on I/O — must be called on Dispatchers.IO.
     */
    suspend fun push(config: DdnsConfig): Result = withContext(Dispatchers.IO) {
        if (!config.isValid) return@withContext Result.Failure("Missing hostname or credentials")

        val ip = detectPublicIp() ?: return@withContext Result.Failure("Could not detect public IP")
        Log.i(TAG, "Detected public IP: $ip · provider=${config.provider.id}")

        return@withContext runCatching {
            when (config.provider) {
                DdnsProvider.DUCKDNS    -> pushDuckDns(config, ip)
                DdnsProvider.NOIP       -> pushNoIp(config, ip)
                DdnsProvider.CLOUDFLARE -> pushCloudflare(config, ip)
                DdnsProvider.INAYA      -> pushInaya(config, ip)
            }
        }.getOrElse { Result.Failure(it.message ?: "unknown error") }
    }

    /* ---------------------------------------------------------------- */
    /*  Public IP detection — tries 3 endpoints                         */
    /* ---------------------------------------------------------------- */

    private val IP_ENDPOINTS = listOf(
        "https://api.ipify.org",
        "https://ifconfig.me/ip",
        "https://icanhazip.com"
    )

    private fun detectPublicIp(): String? {
        for (url in IP_ENDPOINTS) {
            val ip = httpGet(url, connectMs = 5000, readMs = 5000)?.trim()
            if (!ip.isNullOrBlank() && ip.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$"))) {
                return ip
            }
        }
        return null
    }

    /* ---------------------------------------------------------------- */
    /*  Provider-specific updaters                                       */
    /* ---------------------------------------------------------------- */

    /**
     * DuckDNS update URL:
     *   https://www.duckdns.org/update?domains=<host>&token=<token>&ip=<ip>
     * Response: "OK" or "KO"
     */
    private fun pushDuckDns(cfg: DdnsConfig, ip: String): Result {
        val url = "https://www.duckdns.org/update" +
                  "?domains=${enc(cfg.hostname)}" +
                  "&token=${enc(cfg.credential)}" +
                  "&ip=${enc(ip)}"
        val body = httpGet(url) ?: return Result.Failure("network error")
        return if (body.trim().equals("OK", ignoreCase = true))
            Result.Success("DuckDNS updated → ${cfg.fullDomain}", ip)
        else
            Result.Failure("DuckDNS rejected: ${body.take(120)}")
    }

    /**
     * No-IP update URL (HTTP Basic Auth over HTTPS):
     *   https://dynupdate.no-ip.com/nic/update?hostname=<host>&myip=<ip>
     * Response: "good <ip>", "nochg <ip>", or an error code.
     */
    private fun pushNoIp(cfg: DdnsConfig, ip: String): Result {
        val url = "https://dynupdate.no-ip.com/nic/update" +
                  "?hostname=${enc(cfg.fullDomain.ifBlank { cfg.hostname })}" +
                  "&myip=${enc(ip)}"
        val basicAuth = android.util.Base64.encodeToString(
            "${cfg.username}:${cfg.credential}".toByteArray(),
            android.util.Base64.NO_WRAP
        )
        val body = httpGet(url, extraHeaders = mapOf("Authorization" to "Basic $basicAuth"))
            ?: return Result.Failure("network error")
        val first = body.substringBefore(' ').lowercase()
        return when (first) {
            "good", "nochg" -> Result.Success("No-IP updated ($first)", ip)
            else            -> Result.Failure("No-IP error: ${body.take(120)}")
        }
    }

    /**
     * Cloudflare — updates an A record via v4 API.
     * `username` field is used as the zone ID, `hostname` is the full record name.
     */
    private fun pushCloudflare(cfg: DdnsConfig, ip: String): Result {
        val zoneId = cfg.username
        val name   = cfg.fullDomain.ifBlank { cfg.hostname }
        val token  = cfg.credential
        val headers = mapOf(
            "Authorization" to "Bearer $token",
            "Content-Type"  to "application/json",
        )

        // 1) Find record id.
        val listUrl = "https://api.cloudflare.com/client/v4/zones/$zoneId" +
                      "/dns_records?type=A&name=${enc(name)}"
        val listBody = httpGet(listUrl, extraHeaders = headers)
            ?: return Result.Failure("Cloudflare list failed")
        val idMatch = Regex(""""id":"([a-f0-9]+)"""").find(listBody)
            ?: return Result.Failure("Cloudflare: record not found — create A record for $name in dashboard first.")
        val recordId = idMatch.groupValues[1]

        // 2) PUT new IP.
        val body = """{"type":"A","name":"$name","content":"$ip","ttl":120,"proxied":false}"""
        val putUrl = "https://api.cloudflare.com/client/v4/zones/$zoneId/dns_records/$recordId"
        val putResp = httpMethod("PUT", putUrl, body = body, extraHeaders = headers)
            ?: return Result.Failure("Cloudflare PUT failed")
        return if (putResp.contains(""""success":true"""))
            Result.Success("Cloudflare updated → $name", ip)
        else
            Result.Failure("Cloudflare rejected: ${putResp.take(160)}")
    }

    /**
     * INAYA DNS — hypothetical INWEB-hosted DDNS.
     * Endpoint returns JSON `{"ok":true}` on success.
     */
    private fun pushInaya(cfg: DdnsConfig, ip: String): Result {
        val url = "https://inayadns.app/api/update" +
                  "?host=${enc(cfg.hostname)}" +
                  "&ip=${enc(ip)}"
        val headers = mapOf("X-INAYA-Token" to cfg.credential)
        val body = httpGet(url, extraHeaders = headers)
        return when {
            body == null                        -> Result.Failure("INAYA DNS unreachable")
            body.contains(""""ok":true""")      -> Result.Success("INAYA DNS updated → ${cfg.fullDomain}", ip)
            else                                -> Result.Failure("INAYA DNS: ${body.take(120)}")
        }
    }

    /* ---------------------------------------------------------------- */
    /*  HTTP helpers                                                    */
    /* ---------------------------------------------------------------- */

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun httpGet(
        url: String,
        connectMs: Int = 10_000,
        readMs: Int = 10_000,
        extraHeaders: Map<String, String> = emptyMap()
    ): String? = httpMethod("GET", url, body = null, connectMs, readMs, extraHeaders)

    private fun httpMethod(
        method: String,
        url: String,
        body: String? = null,
        connectMs: Int = 10_000,
        readMs: Int = 10_000,
        extraHeaders: Map<String, String> = emptyMap()
    ): String? {
        val conn = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = connectMs
                readTimeout    = readMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
                if (body != null) {
                    doOutput = true
                    outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
                }
            }
        } catch (t: Throwable) { Log.w(TAG, "conn setup failed: $url", t); return null }

        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: return null)
            BufferedReader(InputStreamReader(stream)).use { it.readText() }
        } catch (t: Throwable) { Log.w(TAG, "read failed: $url", t); null }
          finally { runCatching { conn.disconnect() } }
    }
}
