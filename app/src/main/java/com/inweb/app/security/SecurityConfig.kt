package com.inweb.app.security

/**
 * Immutable snapshot of all security rules the user has configured.
 * Read by [AssetInstaller] when materialising nginx.conf / httpd.conf so
 * the rules become real server-level directives on the next restart.
 *
 * Persisted via SharedPreferences (see [com.inweb.app.util.Prefs]).
 */
data class SecurityConfig(
    val basicAuthEnabled: Boolean = false,
    val basicAuthUser: String     = "admin",
    val basicAuthPass: String     = "",           // stored plain in app-private prefs
    /** Only these paths require auth. If empty, whole site. */
    val basicAuthPaths: List<String> = emptyList(),

    val ipMode: IpMode = IpMode.OPEN,
    /** CIDR notation supported: "192.168.1.0/24" or "1.2.3.4". */
    val ipAllowList: List<String> = emptyList(),
    val ipBlockList: List<String> = emptyList(),

    val rateLimitEnabled: Boolean = false,
    /** Requests per second per client IP. */
    val rateLimitRps: Int         = 10,
    val rateLimitBurst: Int       = 30,
) {
    enum class IpMode(val id: String, val displayName: String) {
        OPEN     ("open",     "Open — anyone can connect"),
        WHITELIST("whitelist","Whitelist — only listed IPs allowed"),
        BLACKLIST("blacklist","Blacklist — listed IPs blocked");

        companion object {
            fun fromId(id: String?): IpMode = entries.firstOrNull { it.id == id } ?: OPEN
        }
    }

    /** True if any security layer is active. */
    val hasAnyRule: Boolean get() =
        basicAuthEnabled || ipMode != IpMode.OPEN || rateLimitEnabled
}
