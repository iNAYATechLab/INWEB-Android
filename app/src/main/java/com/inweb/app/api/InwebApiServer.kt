package com.inweb.app.api

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A tiny REST + WebSocket-friendly HTTP server that exposes INWEB's
 * control plane to iOS apps and Web PWAs.
 *
 * All endpoints live under `/api/inweb/*` and return JSON. Zero external
 * dependencies — hand-rolled HTTP/1.1 handling (INWEB philosophy: tiny APK).
 *
 * Endpoints (v1):
 *
 *   GET  /api/inweb/status               → server + services state snapshot
 *   GET  /api/inweb/prefs                → all user preferences
 *   PUT  /api/inweb/prefs                → update preferences (JSON body)
 *   GET  /api/inweb/vhosts               → list virtual hosts
 *   POST /api/inweb/vhosts               → create / update vhost
 *   DELETE /api/inweb/vhosts/{id}        → remove vhost
 *   GET  /api/inweb/hosts                → list DNS host mappings
 *   POST /api/inweb/hosts                → create / update mapping
 *   DELETE /api/inweb/hosts/{id}         → remove mapping
 *   GET  /api/inweb/logs?file=access     → tail server logs
 *   POST /api/inweb/service/start        → { "service": "nginx" }
 *   POST /api/inweb/service/stop         → { "service": "nginx" }
 *   GET  /api/inweb/prayer-times?lat=&lng= → today's prayer times
 *
 * CORS is enabled globally — the PWA can be served from anywhere.
 */
class InwebApiServer(
    private val context: Context,
    private val port: Int = DEFAULT_PORT,
    /** Bearer token clients must send. Auto-generated on first launch. */
    private val bearerToken: String
) {

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val exec = Executors.newCachedThreadPool { r ->
        Thread(r, "InwebApi").apply { isDaemon = true }
    }
    private val router = ApiRouter(context)

    val isRunning: Boolean get() = running.get()

    fun start() {
        if (running.get()) return
        try {
            serverSocket = ServerSocket(port).apply { reuseAddress = true }
            running.set(true)
            exec.execute { acceptLoop() }
            Log.i(TAG, "API server on 0.0.0.0:$port  (Bearer $bearerToken)")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to bind API port $port", t)
            running.set(false)
        }
    }

    fun stop() {
        if (!running.get()) return
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    /* --------------------------------------------------------------- */

    private fun acceptLoop() {
        val ss = serverSocket ?: return
        while (running.get() && !ss.isClosed) {
            try {
                val s = ss.accept()
                s.tcpNoDelay = true
                exec.execute { handleOne(s) }
            } catch (t: Throwable) {
                if (running.get()) Log.w(TAG, "accept failed", t)
            }
        }
    }

    private fun handleOne(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = OutputStreamWriter(socket.getOutputStream())

            // Request line
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 3) { writeError(writer, 400, "bad request"); return }
            val method = parts[0]
            val fullPath = parts[1]
            val (path, query) = splitPathQuery(fullPath)

            // Headers
            val headers = HashMap<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().lowercase()] =
                    line.substring(i + 1).trim()
            }

            // Preflight CORS
            if (method == "OPTIONS") {
                writeCorsPreflight(writer); return
            }

            // Auth (except /api/inweb/ping and static dashboard files)
            val needsAuth = path.startsWith("/api/inweb/") &&
                            !path.startsWith("/api/inweb/ping")
            if (needsAuth) {
                val supplied = headers["authorization"]?.removePrefix("Bearer ")?.trim()
                if (supplied != bearerToken) {
                    writeJson(writer, 401,
                        JSONObject().put("error", "invalid or missing bearer token"))
                    return
                }
            }

            // Body
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (contentLength > 0) {
                val buf = CharArray(contentLength)
                reader.read(buf, 0, contentLength); String(buf)
            } else ""

            // Route it
            val response = router.route(method, path, query, body)
            writeResponse(writer, response)

        } catch (t: Throwable) {
            Log.w(TAG, "handler error", t)
        } finally {
            runCatching { socket.close() }
        }
    }

    /* --------------------------------------------------------------- */

    private fun splitPathQuery(full: String): Pair<String, Map<String, String>> {
        val i = full.indexOf('?')
        if (i < 0) return full to emptyMap()
        val q = full.substring(i + 1).split('&').mapNotNull { part ->
            val e = part.indexOf('=')
            if (e < 0) null
            else java.net.URLDecoder.decode(part.substring(0, e), "UTF-8") to
                 java.net.URLDecoder.decode(part.substring(e + 1), "UTF-8")
        }.toMap()
        return full.substring(0, i) to q
    }

    private fun writeResponse(out: OutputStreamWriter, resp: ApiResponse) {
        val bodyStr = resp.body
        out.write("HTTP/1.1 ${resp.status} ${statusText(resp.status)}\r\n")
        out.write("Content-Type: ${resp.contentType}; charset=utf-8\r\n")
        out.write("Content-Length: ${bodyStr.toByteArray(Charsets.UTF_8).size}\r\n")
        writeCorsHeaders(out)
        out.write("Connection: close\r\n")
        out.write("\r\n")
        out.write(bodyStr)
        out.flush()
    }

    private fun writeJson(out: OutputStreamWriter, status: Int, obj: Any) {
        writeResponse(out, ApiResponse(status, obj.toString(), "application/json"))
    }

    private fun writeError(out: OutputStreamWriter, status: Int, msg: String) {
        writeJson(out, status, JSONObject().put("error", msg))
    }

    private fun writeCorsPreflight(out: OutputStreamWriter) {
        out.write("HTTP/1.1 204 No Content\r\n")
        writeCorsHeaders(out)
        out.write("Connection: close\r\n\r\n")
        out.flush()
    }

    private fun writeCorsHeaders(out: OutputStreamWriter) {
        out.write("Access-Control-Allow-Origin: *\r\n")
        out.write("Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS\r\n")
        out.write("Access-Control-Allow-Headers: Content-Type, Authorization\r\n")
        out.write("Access-Control-Max-Age: 86400\r\n")
    }

    private fun statusText(code: Int) = when (code) {
        200 -> "OK"; 201 -> "Created"; 204 -> "No Content"
        400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"
        500 -> "Internal Server Error"; else -> "OK"
    }

    companion object {
        private const val TAG = "InwebApi"
        const val DEFAULT_PORT = 8181
    }
}

/** Immutable HTTP response returned by [ApiRouter]. */
data class ApiResponse(val status: Int, val body: String, val contentType: String = "application/json")
