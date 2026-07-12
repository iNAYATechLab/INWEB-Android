package com.inweb.app.dns

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.inweb.app.Constants
import com.inweb.app.MainActivity
import com.inweb.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A tiny, root-free "custom DNS" VPN.
 *
 * Strategy:
 *   – Set INWEB itself as the system-wide DNS server (10.99.99.53).
 *   – Allocate a private tunnel address (10.99.99.2) that never routes
 *     regular traffic — only DNS queries get pulled in.
 *   – Add a route only for the DNS resolver address so *only* DNS goes
 *     through the tunnel; browsing traffic is untouched by our VPN.
 *   – Read IP packets from the tunnel; parse the UDP/DNS payload;
 *     answer locally from [InwebDnsResolver] or forward to the real
 *     upstream (default 1.1.1.1) if we have no override.
 *
 * All in pure Kotlin, no NDK, no root. Uses less than ~2 MB RAM.
 */
class InwebVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var scope: CoroutineScope? = null
    private var readerJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopVpn(); stopSelf(); return START_NOT_STICKY }
            else        -> startVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (vpnInterface != null) return
        Log.i(TAG, "Starting INWEB DNS VPN")

        // 1. Establish the TUN device.
        val builder = Builder()
            .setSession(getString(R.string.dns_vpn_session))
            .addAddress(TUN_ADDRESS, 30)
            .addDnsServer(FAKE_DNS_ADDRESS)          // we're the DNS server
            .addRoute(FAKE_DNS_ADDRESS, 32)          // *only* DNS routed
            // Exclude our own package so INWEB's own outbound calls aren't
            // caught by our tunnel (avoids the loopback problem).
            .also { b ->
                runCatching { b.addDisallowedApplication(packageName) }
            }
            .setBlocking(true)
            .setMtu(1500)

        // Configure a low-importance foreground service on Android 8+
        // because system VPNs must run in the foreground.
        startForegroundCompat(buildNotification())

        vpnInterface = builder.establish() ?: run {
            Log.e(TAG, "VpnService.Builder.establish() returned null — no VPN permission?")
            stopSelf(); return
        }

        // Refresh the DNS override snapshot before serving.
        InwebDnsResolver.rebuild(this)

        // 2. Start the read/write loop.
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        readerJob = scope!!.launch { pumpPackets() }
    }

    private fun stopVpn() {
        Log.i(TAG, "Stopping INWEB DNS VPN")
        readerJob?.cancel(); readerJob = null
        scope?.cancel(); scope = null
        runCatching { vpnInterface?.close() }
        vpnInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() { stopVpn(); super.onDestroy() }

    override fun onRevoke() { stopVpn(); super.onRevoke() }

    /* ================================================================ */
    /*  Packet pump                                                     */
    /* ================================================================ */

    private suspend fun pumpPackets() {
        val fd = vpnInterface ?: return
        val input  = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val upstream = DatagramSocket().apply { protect(this) }

        val buffer = ByteArray(2048)
        try {
            while (scope?.isActive == true) {
                val n = input.read(buffer)
                if (n <= 0) continue

                // Parse IPv4 header quickly.
                val ipVersion = (buffer[0].toInt() ushr 4) and 0xF
                if (ipVersion != 4) continue
                val ihl = (buffer[0].toInt() and 0xF) * 4
                if (ihl < 20 || n < ihl + 8) continue
                val proto = buffer[9].toInt() and 0xFF
                if (proto != 17) continue     // UDP only

                val srcIp = InetAddress.getByAddress(buffer.copyOfRange(12, 16))
                val dstIp = InetAddress.getByAddress(buffer.copyOfRange(16, 20))
                val srcPort = ((buffer[ihl].toInt() and 0xFF) shl 8) or (buffer[ihl + 1].toInt() and 0xFF)
                val dstPort = ((buffer[ihl + 2].toInt() and 0xFF) shl 8) or (buffer[ihl + 3].toInt() and 0xFF)
                val udpLen  = ((buffer[ihl + 4].toInt() and 0xFF) shl 8) or (buffer[ihl + 5].toInt() and 0xFF)
                val dnsPayload = buffer.copyOfRange(ihl + 8, ihl + udpLen)

                val query = DnsPacket.parse(dnsPayload) ?: continue
                val question = query.questions.firstOrNull() ?: continue

                val override = InwebDnsResolver.resolveOverride(question.name)
                val respBytes: ByteArray = when {
                    override != null && question.type == DnsPacket.TYPE_A -> {
                        Log.i(TAG, "↩︎ ${question.name} → $override  (override)")
                        DnsPacket.buildAResponse(query, override)
                    }
                    override != null && question.type == DnsPacket.TYPE_AAAA -> {
                        // We don't ship AAAA overrides — respond NXDOMAIN so
                        // clients quickly fall back to the A record.
                        DnsPacket.buildNxDomain(query)
                    }
                    else -> forwardUpstream(upstream, dnsPayload) ?: continue
                }

                // Wrap the DNS response back in UDP + IPv4 and inject it.
                val ipPacket = buildIpv4Udp(
                    srcIp = dstIp.address, srcPort = dstPort,
                    dstIp = srcIp.address, dstPort = srcPort,
                    payload = respBytes
                )
                output.write(ipPacket)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Packet pump ended: ${t.message}")
        } finally {
            runCatching { upstream.close() }
            runCatching { input.close() }
            runCatching { output.close() }
        }
    }

    /**
     * Send the DNS query to the *real* upstream and return its response.
     * Timeout kept short (2 s) so a slow upstream doesn't stall others.
     */
    private fun forwardUpstream(socket: DatagramSocket, dnsPayload: ByteArray): ByteArray? {
        return runCatching {
            socket.soTimeout = 2_000
            val target = InetSocketAddress(InetAddress.getByName(UPSTREAM_DNS), 53)
            val out = DatagramPacket(dnsPayload, dnsPayload.size, target)
            socket.send(out)
            val reply = DatagramPacket(ByteArray(2048), 2048)
            socket.receive(reply)
            reply.data.copyOf(reply.length)
        }.getOrNull()
    }

    /* ---------------------------------------------------------------- */
    /*  IPv4 + UDP packet builder                                        */
    /* ---------------------------------------------------------------- */

    private fun buildIpv4Udp(
        srcIp: ByteArray, srcPort: Int,
        dstIp: ByteArray, dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val udpLen = 8 + payload.size
        val ipLen  = 20 + udpLen
        val buf = ByteBuffer.allocate(ipLen).order(ByteOrder.BIG_ENDIAN)

        // IPv4 header (20 bytes, no options)
        buf.put(0x45)                       // version=4, IHL=5
        buf.put(0)                          // DSCP + ECN
        buf.putShort(ipLen.toShort())
        buf.putShort(0)                     // identification
        buf.putShort(0x4000)                // flags=DF, no fragment
        buf.put(64)                         // TTL
        buf.put(17)                         // protocol = UDP
        buf.putShort(0)                     // checksum placeholder
        buf.put(srcIp); buf.put(dstIp)

        // Compute IPv4 header checksum.
        val ipHeader = buf.array().copyOfRange(0, 20)
        val ipChecksum = internetChecksum(ipHeader, 0, 20)
        buf.putShort(10, ipChecksum.toShort())

        // UDP header + payload
        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putShort(udpLen.toShort())
        buf.putShort(0)                     // UDP checksum optional over IPv4
        buf.put(payload)

        return buf.array()
    }

    private fun internetChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        val end = offset + length
        while (i < end - 1) {
            val word = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            sum += word
            if (sum and 0xFFFF0000.toInt() != 0) {
                sum = (sum and 0xFFFF) + 1
            }
            i += 2
        }
        if (i < end) {
            sum += (data[i].toInt() and 0xFF) shl 8
            if (sum and 0xFFFF0000.toInt() != 0) sum = (sum and 0xFFFF) + 1
        }
        return sum.inv() and 0xFFFF
    }

    /* ---------------------------------------------------------------- */
    /*  Notification                                                    */
    /* ---------------------------------------------------------------- */

    private fun buildNotification(): Notification {
        val openPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, InwebVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, Constants.NOTIF_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_server)
            .setContentTitle(getString(R.string.dns_vpn_notif_title))
            .setContentText(getString(R.string.dns_vpn_notif_text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPi)
            .addAction(0, getString(R.string.stop_server), stopPi)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    /* ================================================================ */

    companion object {
        private const val TAG = "InwebVpn"
        private const val NOTIF_ID = 4242
        private const val ACTION_STOP = "com.inweb.app.action.VPN_STOP"

        /** In-tunnel address INWEB claims for itself. */
        private const val TUN_ADDRESS      = "10.99.99.2"
        /** Address advertised to the system as the DNS server. */
        const val FAKE_DNS_ADDRESS         = "10.99.99.53"
        /** Where we forward queries we don't have overrides for. */
        private const val UPSTREAM_DNS     = "1.1.1.1"

        /** Prepare and (if permitted) start the VPN. Returns true if the caller
         *  is expected to launch [VpnService.prepare] first. */
        fun start(context: Context) {
            val i = Intent(context, InwebVpnService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }
        fun stop(context: Context) {
            context.startService(
                Intent(context, InwebVpnService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
