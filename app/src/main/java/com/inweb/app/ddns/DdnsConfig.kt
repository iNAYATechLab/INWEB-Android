package com.inweb.app.ddns

/**
 * All DDNS settings the user has configured. Persisted via
 * [com.inweb.app.util.Prefs.ddns].
 */
data class DdnsConfig(
    val enabled: Boolean          = false,
    val provider: DdnsProvider    = DdnsProvider.DUCKDNS,

    /** Sub-domain the user wants, e.g. "myapp" → "myapp.duckdns.org". */
    val hostname: String          = "",

    /** Token / password / API key — treated as secret. */
    val credential: String        = "",

    /** For providers that need a second field (Cloudflare zone id, No-IP username). */
    val username: String          = "",

    /** How often to refresh the DNS record (minutes). */
    val intervalMinutes: Int      = 5,

    /** True to also update whenever Wi-Fi connects. */
    val updateOnNetworkChange: Boolean = true
) {
    /** e.g. "myapp.duckdns.org" — the URL the user should share. */
    val fullDomain: String
        get() = if (hostname.isBlank()) "" else hostname + provider.domainSuffix

    /** True if all required fields are populated. */
    val isValid: Boolean
        get() = hostname.isNotBlank() && credential.isNotBlank() &&
                (!provider.needsUsername || username.isNotBlank())
}
