package com.inweb.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Utilities for discovering the device's LAN-side IPv4 address(es) so we
 * can display "http://192.168.x.x:8080" and generate a QR code that other
 * devices on the same Wi-Fi can scan.
 *
 * Strategy (in order):
 *   1. ConnectivityManager active network's LinkProperties (Android 9+)
 *   2. NetworkInterface enumeration (works on all Androids, filters out
 *      loopback, virtual, and IPv6-link-local addresses)
 *   3. WifiManager fallback (legacy)
 */
object NetworkUtils {

    data class NetInfo(
        val hasNetwork: Boolean,
        val transport: Transport,
        val ipv4: String?,      // e.g. "192.168.1.42"  (or null)
        val ssid: String?       // e.g. "MyWiFi"        (or null; may be redacted)
    )

    enum class Transport { WIFI, ETHERNET, CELLULAR, HOTSPOT, OTHER, NONE }

    fun snapshot(context: Context): NetInfo {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val transport = detectTransport(cm)
        val ipv4 = pickBestIpv4(cm)
        val ssid = if (transport == Transport.WIFI) currentSsid(context) else null
        return NetInfo(
            hasNetwork = ipv4 != null,
            transport  = transport,
            ipv4       = ipv4,
            ssid       = ssid
        )
    }

    /* --------------------------------------------------------------- */

    private fun detectTransport(cm: ConnectivityManager): Transport {
        val net: Network = cm.activeNetwork ?: return Transport.NONE
        val caps: NetworkCapabilities = cm.getNetworkCapabilities(net) ?: return Transport.NONE
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)     -> Transport.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Transport.ETHERNET
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.CELLULAR
            else                                                      -> Transport.OTHER
        }
    }

    private fun pickBestIpv4(cm: ConnectivityManager): String? {
        // 1) Preferred: active network's LinkProperties.
        cm.activeNetwork?.let { net ->
            val lp: LinkProperties? = cm.getLinkProperties(net)
            lp?.linkAddresses?.forEach { la ->
                val addr = la.address
                if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isAnyLocalAddress) {
                    return addr.hostAddress
                }
            }
        }
        // 2) Fallback: enumerate all interfaces (catches Wi-Fi hotspot,
        //    USB tether, ethernet dongles, etc.)
        return enumerateInterfaceIpv4()
    }

    private fun enumerateInterfaceIpv4(): String? {
        return try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
                for (addr: InetAddress in iface.inetAddresses) {
                    if (addr is Inet4Address &&
                        !addr.isLoopbackAddress &&
                        !addr.isLinkLocalAddress &&
                        !addr.isAnyLocalAddress
                    ) return addr.hostAddress
                }
            }
            null
        } catch (_: Throwable) { null }
    }

    @Suppress("DEPRECATION")
    private fun currentSsid(context: Context): String? {
        return try {
            val wm = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
            val info = wm.connectionInfo ?: return null
            val raw = info.ssid ?: return null
            // WifiManager returns quoted SSID; strip quotes.
            val clean = raw.trim('"').ifBlank { null }
            // Android 10+ redacts SSID to "<unknown ssid>" without location perm –
            // that's fine, we just fall back to null.
            if (clean.equals("<unknown ssid>", ignoreCase = true)) null else clean
        } catch (_: Throwable) { null }
    }

    /* --------------------------------------------------------------- */
    /*  Hotspot detection – best-effort                                */
    /* --------------------------------------------------------------- */

    /**
     * When the device itself is the Wi-Fi hotspot, the AP interface is
     * typically named "ap0" / "wlan1" and holds 192.168.43.1 (Android
     * default). Detect that so we can label the URL appropriately.
     */
    fun isActingAsHotspot(): Boolean = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().any { iface ->
            iface.isUp && (iface.name.startsWith("ap") || iface.name == "wlan1") &&
                iface.inetAddresses.toList().any { it is Inet4Address && !it.isLoopbackAddress }
        }
    } catch (_: Throwable) { false }
}
