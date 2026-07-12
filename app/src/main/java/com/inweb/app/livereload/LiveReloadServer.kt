package com.inweb.app.livereload

import android.util.Log
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.experimental.xor

/**
 * A minimal WebSocket server implementing the LiveReload protocol.
 *
 * Why not a library? INWEB values a tiny APK. A full WebSocket
 * implementation is ~300 lines — worth writing inline rather than pulling in
 * java-websocket / netty / okhttp-ws (all 100 KB+).
 *
 * Protocol:
 *   1. Client (injected JS in the WebView) opens `ws://localhost:35729/livereload`
 *   2. We send `{ command: "hello", protocols: ["http://livereload.com/protocols/official-7"] }`
 *   3. Client replies with `hello`
 *   4. When any file changes we send `{ command: "reload", path: "<path>", liveCSS: true }`
 *   5. Client's injected JS reloads the page (or hot-swaps CSS).
 *
 * Port 35729 is the LiveReload de-facto default so any browser LiveReload
 * extension "just works" out of the box.
 */
class LiveReloadServer(private val port: Int = DEFAULT_PORT) {

    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "LiveReloadWS").apply { isDaemon = true }
    }

    /** All currently-connected WebSocket sessions. Key = client id. */
    private val clients = ConcurrentHashMap<String, ClientSession>()

    val isRunning: Boolean get() = running.get()
    val clientCount: Int    get() = clients.size

    /* ---------------------------------------------------------------- */
    /*  Lifecycle                                                        */
    /* ---------------------------------------------------------------- */

    fun start() {
        if (running.get()) return
        try {
            serverSocket = ServerSocket(port).apply { reuseAddress = true }
            running.set(true)
            executor.execute { acceptLoop() }
            Log.i(TAG, "LiveReload server listening on ws://localhost:$port/livereload")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to bind LiveReload port $port", e)
            running.set(false)
        }
    }

    fun stop() {
        if (!running.get()) return
        running.set(false)
        clients.values.forEach { runCatching { it.close() } }
        clients.clear()
        runCatching { serverSocket?.close() }
        serverSocket = null
        Log.i(TAG, "LiveReload server stopped")
    }

    /** Broadcast a reload directive to every connected browser. */
    fun broadcastReload(path: String) {
        val cssOnly = path.endsWith(".css", ignoreCase = true)
        val json = """{"command":"reload","path":${jsonString(path)},"liveCSS":$cssOnly}"""
        Log.i(TAG, "→ reload($path)  [$cssOnly liveCSS] · ${clients.size} clients")
        clients.values.forEach { c ->
            runCatching { c.sendText(json) }.onFailure { Log.w(TAG, "send failed", it) }
        }
    }

    /* ---------------------------------------------------------------- */
    /*  Accept loop                                                      */
    /* ---------------------------------------------------------------- */

    private fun acceptLoop() {
        val ss = serverSocket ?: return
        while (running.get() && !ss.isClosed) {
            try {
                val socket = ss.accept()
                socket.tcpNoDelay = true
                executor.execute { handleClient(socket) }
            } catch (e: IOException) {
                if (running.get()) Log.w(TAG, "accept() failed", e)
            }
        }
    }

    private fun handleClient(socket: Socket) {
        val id = "c${System.nanoTime()}"
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val output = socket.getOutputStream()

            // ---- Read HTTP handshake ----
            val headers = mutableMapOf<String, String>()
            var line: String? = reader.readLine() ?: return
            val requestLine = line
            while (!line.isNullOrEmpty()) {
                line = reader.readLine() ?: break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    headers[line.substring(0, idx).trim().lowercase()] =
                        line.substring(idx + 1).trim()
                }
            }

            val wsKey = headers["sec-websocket-key"] ?: run {
                Log.w(TAG, "Rejecting non-WS connection: $requestLine")
                socket.close(); return
            }

            // ---- Send WS handshake response ----
            val acceptKey = wsAcceptKey(wsKey)
            val handshake = buildString {
                append("HTTP/1.1 101 Switching Protocols\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Accept: $acceptKey\r\n")
                append("\r\n")
            }
            output.write(handshake.toByteArray())
            output.flush()

            // ---- Register client session ----
            val session = ClientSession(id, socket, output)
            clients[id] = session
            Log.i(TAG, "← WS client $id connected  (${clients.size} total)")

            // ---- Send LiveReload hello ----
            session.sendText("""{"command":"hello","protocols":["http://livereload.com/protocols/official-7"],"serverName":"INWEB"}""")

            // ---- Read frames until closed ----
            session.readLoop()
        } catch (e: Throwable) {
            Log.w(TAG, "Client $id error", e)
        } finally {
            clients.remove(id)
            runCatching { socket.close() }
            Log.i(TAG, "→ WS client $id disconnected (${clients.size} remain)")
        }
    }

    /* ---------------------------------------------------------------- */
    /*  Per-client session                                              */
    /* ---------------------------------------------------------------- */

    private class ClientSession(
        val id: String,
        private val socket: Socket,
        private val out: OutputStream
    ) {
        private val input = socket.getInputStream()

        @Synchronized
        fun sendText(text: String) {
            if (socket.isClosed) return
            val payload = text.toByteArray(Charsets.UTF_8)
            val frame = ByteArray(2 + when {
                payload.size <= 125       -> 0
                payload.size <= 0xffff    -> 2
                else                       -> 8
            } + payload.size)

            frame[0] = 0x81.toByte()   // FIN=1, opcode=1 (text)
            var pos = 1
            when {
                payload.size <= 125    -> frame[pos++] = payload.size.toByte()
                payload.size <= 0xffff -> {
                    frame[pos++] = 126
                    frame[pos++] = (payload.size ushr 8).toByte()
                    frame[pos++] = payload.size.toByte()
                }
                else -> {
                    frame[pos++] = 127
                    for (i in 7 downTo 0) frame[pos++] = (payload.size ushr (8 * i)).toByte()
                }
            }
            System.arraycopy(payload, 0, frame, pos, payload.size)
            out.write(frame); out.flush()
        }

        fun readLoop() {
            while (!socket.isClosed) {
                val b1 = input.read(); if (b1 == -1) return
                val b2 = input.read(); if (b2 == -1) return
                val opcode = b1 and 0x0f
                val masked = (b2 and 0x80) != 0
                var len = b1 and 0x7f      // reuse b1's low bits, but really need b2's
                len = b2 and 0x7f
                if (len == 126) {
                    len = (input.read() shl 8) or input.read()
                } else if (len == 127) {
                    // Skip 8 bytes; assume small payloads only (control frames + pings).
                    for (i in 0 until 8) input.read()
                    len = 0
                }
                val mask = if (masked) ByteArray(4) { input.read().toByte() } else ByteArray(0)
                val payload = ByteArray(len)
                var read = 0
                while (read < len) {
                    val n = input.read(payload, read, len - read)
                    if (n == -1) return; read += n
                }
                if (masked) {
                    for (i in payload.indices) payload[i] = payload[i] xor mask[i % 4]
                }
                when (opcode) {
                    0x8 -> return                                    // close
                    0x9 -> sendPong(payload)                         // ping → pong
                    0x1 -> { /* text — ignore in our unidirectional protocol */ }
                }
            }
        }

        private fun sendPong(data: ByteArray) {
            val frame = ByteArray(2 + data.size)
            frame[0] = 0x8A.toByte()                                 // FIN + opcode 0xA
            frame[1] = data.size.toByte()
            System.arraycopy(data, 0, frame, 2, data.size)
            out.write(frame); out.flush()
        }

        fun close() { runCatching { socket.close() } }
    }

    /* ---------------------------------------------------------------- */
    /*  Helpers                                                          */
    /* ---------------------------------------------------------------- */

    private fun wsAcceptKey(key: String): String {
        val sha1 = MessageDigest.getInstance("SHA-1")
            .digest((key + WS_MAGIC).toByteArray())
        return android.util.Base64.encodeToString(sha1, android.util.Base64.NO_WRAP)
    }

    private fun jsonString(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    companion object {
        private const val TAG = "LiveReloadServer"
        const val DEFAULT_PORT = 35729
        private const val WS_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    }
}
