package com.inweb.app.dns

import java.util.UUID

/**
 * A single host-file style mapping: one hostname → one IP.
 *
 * Managed by [HostEntryStore] and applied by [InwebVpnService] (as well
 * as the [DnsServer] in Stage 3).
 */
data class HostEntry(
    val id: String = UUID.randomUUID().toString(),

    /** Hostname the user types (e.g. "wordpress.local"). Case-insensitive. */
    val hostname: String,

    /** IPv4 the hostname should resolve to. */
    val ip: String,

    /** True if this rule is active — we can save-but-not-apply. */
    val enabled: Boolean = true,

    /** Optional user-friendly note (e.g. "WordPress dev site"). */
    val note: String = "",

    val createdAt: Long = System.currentTimeMillis(),
) {
    val displayLabel: String get() = if (note.isBlank()) hostname else "$hostname · $note"

    companion object {
        private val HOST_RX = Regex(
            "^(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)*" +
                "[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\$"
        )
        private val IPV4_RX = Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}\$")

        fun validHostname(s: String): Boolean = HOST_RX.matches(s)
        fun validIpv4(s: String): Boolean {
            if (!IPV4_RX.matches(s)) return false
            return s.split('.').all { it.toIntOrNull()?.let { n -> n in 0..255 } == true }
        }
    }
}
