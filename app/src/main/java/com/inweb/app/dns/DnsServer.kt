package com.inweb.app.dns

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A standalone UDP DNS server, port 5353 by default (unprivileged, no
 * root). If the user has root, they can pick 53.
 *
 * Other devices on the LAN can point their DNS resolver at this phone's
 * IP and get INWEB's custom hostnames resolved — perfect for testing
 * `wordpress.local` from a laptop without editing /etc/hosts.
 *
 * Uses [InwebDnsResolver] for overrides; forwards unknown queries to an
 * upstream resolver (default 1.1.1.1).
 */
class DnsServer(
    private val port: Int = DEFAULT_PORT,
    private val upstreamDns: String = "1.1.1.1"
) {

    private val running = AtomicBoolean(false)
    private var socket: DatagramSocket? = null
    private val exec = Executors.newCachedThreadPool { r ->
        Thread(r, "InwebDnsSrv").apply { isDaemon = true }
    }

    val isRunning: Boolean get() = running.get()

    fun start() {
        if (running.get()) return
        try {
            socket = DatagramSocket(InetSocketAddress("0.0.0.0", port))
            running.set(true)
            exec.execute { serveLoop() }
            Log.i(TAG, "DNS server listening on 0.0.0.0:$port")
        } catch (t: Throwable) {
            Log.e(TAG, "Cannot bind DNS port $port (privileged?)", t)
            running.set(false)
        }
    }

    fun stop() {
        if (!running.get()) return
        running.set(false)
        runCatching { socket?.close() }
        socket = null
        Log.i(TAG, "DNS server stopped")
    }

    /* --------------------------------------------------------------- */

    private fun serveLoop() {
        val sock = socket ?: return
        val buf = ByteArray(2048)
        while (running.get() && !sock.isClosed) {
            val incoming = DatagramPacket(buf, buf.size)
            try {
                sock.receive(incoming)
                val payload = incoming.data.copyOfRange(0, incoming.length)
                val client = InetSocketAddress(incoming.address, incoming.port)
                exec.execute { handleOne(sock, payload, client) }
            } catch (t: Throwable) {
                if (running.get()) Log.w(TAG, "receive error", t)
            }
        }
    }

    private fun handleOne(sock: DatagramSocket, payload: ByteArray, client: InetSocketAddress) {
        val query = DnsPacket.parse(payload) ?: return
        val question = query.questions.firstOrNull() ?: return

        val override = InwebDnsResolver.resolveOverride(question.name)
        val response: ByteArray = when {
            override != null && question.type == DnsPacket.TYPE_A -> {
                Log.i(TAG, "↩ ${client.hostString} · ${question.name} → $override")
                DnsPacket.buildAResponse(query, override)
            }
            override != null && question.type == DnsPacket.TYPE_AAAA -> {
                DnsPacket.buildNxDomain(query)
            }
            else -> forwardUpstream(payload) ?: DnsPacket.buildNxDomain(query)
        }
        try {
            sock.send(DatagramPacket(response, response.size, client))
        } catch (t: Throwable) {
            Log.w(TAG, "send back to $client failed", t)
        }
    }

    private fun forwardUpstream(payload: ByteArray): ByteArray? {
        val up = DatagramSocket()
        return try {
            up.soTimeout = 2_000
            val addr = InetAddress.getByName(upstreamDns)
            up.send(DatagramPacket(payload, payload.size, addr, 53))
            val reply = DatagramPacket(ByteArray(2048), 2048)
            up.receive(reply)
            reply.data.copyOf(reply.length)
        } catch (t: Throwable) {
            Log.w(TAG, "upstream forward failed", t); null
        } finally { runCatching { up.close() } }
    }

    companion object {
        private const val TAG = "InwebDnsServer"
        /** Default unprivileged port (root-free). */
        const val DEFAULT_PORT = 5353
    }
}
