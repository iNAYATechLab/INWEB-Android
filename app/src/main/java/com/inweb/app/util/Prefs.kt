package com.inweb.app.util

import android.content.Context
import android.content.SharedPreferences
import com.inweb.app.Constants
import com.inweb.app.ddns.DdnsConfig
import com.inweb.app.ddns.DdnsProvider
import com.inweb.app.security.SecurityConfig
import com.inweb.app.services.WebServerEngine

/**
 * Tiny wrapper around SharedPreferences. Keeps INWEB dependency-light.
 * Used for per-user settings that persist across launches.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Bind Nginx to LAN (0.0.0.0) or localhost-only (127.0.0.1)? */
    var bindLan: Boolean
        get() = sp.getBoolean(KEY_BIND_LAN, false)
        set(v) = sp.edit().putBoolean(KEY_BIND_LAN, v).apply()

    /** HTTP port for the chosen web server (defaults to 8080). */
    var httpPort: Int
        get() = sp.getInt(KEY_HTTP_PORT, Constants.DEFAULT_HTTP_PORT).coerceIn(1024, 65535)
        set(v) = sp.edit().putInt(KEY_HTTP_PORT, v.coerceIn(1024, 65535)).apply()

    /** Which HTTP engine to run: Nginx or Apache. */
    var webServer: WebServerEngine
        get() = WebServerEngine.fromId(sp.getString(KEY_WEB_SERVER, WebServerEngine.NGINX.id))
        set(v) = sp.edit().putString(KEY_WEB_SERVER, v.id).apply()

    /** MySQL / MariaDB port. Defaults to 3306. */
    var mysqlPort: Int
        get() = sp.getInt(KEY_MYSQL_PORT, Constants.DEFAULT_MYSQL_PORT).coerceIn(1024, 65535)
        set(v) = sp.edit().putInt(KEY_MYSQL_PORT, v.coerceIn(1024, 65535)).apply()

    /**
     * MySQL root password. Set once during first-run bootstrap.
     * Persisted in plain SharedPreferences (private to app UID); if this
     * ever becomes user-visible you should migrate to EncryptedSharedPreferences.
     */
    var mysqlRootPassword: String
        get() = sp.getString(KEY_MYSQL_PW, "")!!
        set(v) = sp.edit().putString(KEY_MYSQL_PW, v).apply()

    /** True once mysql_install_db has been run against filesDir/mysql/data. */
    var mysqlInitialised: Boolean
        get() = sp.getBoolean(KEY_MYSQL_INIT, false)
        set(v) = sp.edit().putBoolean(KEY_MYSQL_INIT, v).apply()

    /** Auto-start MySQL when Start-All is pressed? Some users don't need it. */
    var mysqlEnabled: Boolean
        get() = sp.getBoolean(KEY_MYSQL_ENABLED, true)
        set(v) = sp.edit().putBoolean(KEY_MYSQL_ENABLED, v).apply()

    /** Auto-start every service when the device boots. */
    var autoStartOnBoot: Boolean
        get() = sp.getBoolean(KEY_AUTOSTART, false)
        set(v) = sp.edit().putBoolean(KEY_AUTOSTART, v).apply()

    /** UI theme: system / light / dark. */
    var themeMode: ThemeMode
        get() = ThemeMode.fromId(sp.getString(KEY_THEME, ThemeMode.SYSTEM.id))
        set(v) = sp.edit().putString(KEY_THEME, v.id).apply()

    /** Code-editor colour theme id (see EditorTheme.ALL). */
    var editorThemeId: String
        get() = sp.getString(KEY_EDITOR_THEME, "inweb_dark") ?: "inweb_dark"
        set(v) = sp.edit().putString(KEY_EDITOR_THEME, v).apply()

    /** Editor font size in sp (10..24). */
    var editorFontSize: Int
        get() = sp.getInt(KEY_EDITOR_FONT, 13).coerceIn(10, 24)
        set(v) = sp.edit().putInt(KEY_EDITOR_FONT, v.coerceIn(10, 24)).apply()

    /** Enable HTTPS on the Nginx side (needs self-signed cert). */
    var httpsEnabled: Boolean
        get() = sp.getBoolean(KEY_HTTPS_ENABLED, false)
        set(v) = sp.edit().putBoolean(KEY_HTTPS_ENABLED, v).apply()

    /** HTTPS listen port (default 8443 — Android forbids <1024). */
    var httpsPort: Int
        get() = sp.getInt(KEY_HTTPS_PORT, 8443).coerceIn(1024, 65535)
        set(v) = sp.edit().putInt(KEY_HTTPS_PORT, v.coerceIn(1024, 65535)).apply()

    /** SHA-256 fingerprint of the currently active certificate. */
    var httpsFingerprint: String
        get() = sp.getString(KEY_HTTPS_FP, "")!!
        set(v) = sp.edit().putString(KEY_HTTPS_FP, v).apply()

    /** LiveReload: auto-refresh browser on file save. */
    var liveReloadEnabled: Boolean
        get() = sp.getBoolean(KEY_LR_ENABLED, false)
        set(v) = sp.edit().putBoolean(KEY_LR_ENABLED, v).apply()

    /** Standalone LAN DNS server (Stage 3). */
    var dnsServerEnabled: Boolean
        get() = sp.getBoolean(KEY_DNS_SRV_ENABLED, false)
        set(v) = sp.edit().putBoolean(KEY_DNS_SRV_ENABLED, v).apply()

    /** DNS server port (5353 default; 53 needs root). */
    var dnsServerPort: Int
        get() = sp.getInt(KEY_DNS_SRV_PORT, 5353).coerceIn(53, 65535)
        set(v) = sp.edit().putInt(KEY_DNS_SRV_PORT, v.coerceIn(53, 65535)).apply()

    /** REST control API for iOS/Web PWA clients. */
    var apiEnabled: Boolean
        get() = sp.getBoolean(KEY_API_ENABLED, false)
        set(v) = sp.edit().putBoolean(KEY_API_ENABLED, v).apply()

    var apiPort: Int
        get() = sp.getInt(KEY_API_PORT, 8181).coerceIn(1024, 65535)
        set(v) = sp.edit().putInt(KEY_API_PORT, v.coerceIn(1024, 65535)).apply()

    /** Bearer token for the REST API. Blank until first generation. */
    var apiToken: String
        get() = sp.getString(KEY_API_TOKEN, "")!!
        set(v) = sp.edit().putString(KEY_API_TOKEN, v).apply()

    /** Dynamic DNS configuration snapshot. */
    var ddns: DdnsConfig
        get() = DdnsConfig(
            enabled          = sp.getBoolean(KEY_DDNS_ENABLED, false),
            provider         = DdnsProvider.fromId(sp.getString(KEY_DDNS_PROVIDER, "duckdns")),
            hostname         = sp.getString(KEY_DDNS_HOSTNAME, "")!!,
            credential       = sp.getString(KEY_DDNS_CREDENTIAL, "")!!,
            username         = sp.getString(KEY_DDNS_USERNAME, "")!!,
            intervalMinutes  = sp.getInt(KEY_DDNS_INTERVAL, 5).coerceIn(1, 1440),
            updateOnNetworkChange = sp.getBoolean(KEY_DDNS_ON_NET, true),
        )
        set(v) {
            sp.edit()
                .putBoolean(KEY_DDNS_ENABLED,   v.enabled)
                .putString(KEY_DDNS_PROVIDER,   v.provider.id)
                .putString(KEY_DDNS_HOSTNAME,   v.hostname)
                .putString(KEY_DDNS_CREDENTIAL, v.credential)
                .putString(KEY_DDNS_USERNAME,   v.username)
                .putInt(KEY_DDNS_INTERVAL,      v.intervalMinutes)
                .putBoolean(KEY_DDNS_ON_NET,    v.updateOnNetworkChange)
                .apply()
        }

    /** Timestamp (ms) + result of the last DDNS push. Human-readable status. */
    var ddnsLastPushMs: Long
        get() = sp.getLong(KEY_DDNS_LAST_MS, 0L)
        set(v) = sp.edit().putLong(KEY_DDNS_LAST_MS, v).apply()

    var ddnsLastResult: String
        get() = sp.getString(KEY_DDNS_LAST_RESULT, "")!!
        set(v) = sp.edit().putString(KEY_DDNS_LAST_RESULT, v).apply()

    var ddnsLastIp: String
        get() = sp.getString(KEY_DDNS_LAST_IP, "")!!
        set(v) = sp.edit().putString(KEY_DDNS_LAST_IP, v).apply()

    /**
     * Full security configuration (basic auth, IP rules, rate limit).
     * Getter returns an immutable snapshot; use setter to save changes.
     * Lists are stored as newline-separated strings for simplicity.
     */
    var security: SecurityConfig
        get() = SecurityConfig(
            basicAuthEnabled = sp.getBoolean(KEY_BA_ENABLED, false),
            basicAuthUser    = sp.getString(KEY_BA_USER, "admin")!!,
            basicAuthPass    = sp.getString(KEY_BA_PASS, "")!!,
            basicAuthPaths   = splitLines(sp.getString(KEY_BA_PATHS, "")),
            ipMode           = SecurityConfig.IpMode.fromId(sp.getString(KEY_IP_MODE, "open")),
            ipAllowList      = splitLines(sp.getString(KEY_IP_ALLOW, "")),
            ipBlockList      = splitLines(sp.getString(KEY_IP_BLOCK, "")),
            rateLimitEnabled = sp.getBoolean(KEY_RL_ENABLED, false),
            rateLimitRps     = sp.getInt(KEY_RL_RPS, 10).coerceIn(1, 1000),
            rateLimitBurst   = sp.getInt(KEY_RL_BURST, 30).coerceIn(1, 10_000),
        )
        set(v) {
            sp.edit()
                .putBoolean(KEY_BA_ENABLED, v.basicAuthEnabled)
                .putString(KEY_BA_USER,     v.basicAuthUser)
                .putString(KEY_BA_PASS,     v.basicAuthPass)
                .putString(KEY_BA_PATHS,    v.basicAuthPaths.joinToString("\n"))
                .putString(KEY_IP_MODE,     v.ipMode.id)
                .putString(KEY_IP_ALLOW,    v.ipAllowList.joinToString("\n"))
                .putString(KEY_IP_BLOCK,    v.ipBlockList.joinToString("\n"))
                .putBoolean(KEY_RL_ENABLED, v.rateLimitEnabled)
                .putInt(KEY_RL_RPS,         v.rateLimitRps)
                .putInt(KEY_RL_BURST,       v.rateLimitBurst)
                .apply()
        }

    private fun splitLines(raw: String?): List<String> =
        raw.orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    /** Was setup completed? (Reserved for future onboarding.) */
    var onboarded: Boolean
        get() = sp.getBoolean(KEY_ONBOARDED, false)
        set(v) = sp.edit().putBoolean(KEY_ONBOARDED, v).apply()

    /* ------------------------------------------------------- */
    /*  In-app updates (GitHub Releases)                        */
    /* ------------------------------------------------------- */

    /** Auto-check for a new release every app start (throttled 12h). Default ON. */
    var autoUpdateCheck: Boolean
        get() = sp.getBoolean(KEY_AUTO_UPDATE, true)
        set(v) = sp.edit().putBoolean(KEY_AUTO_UPDATE, v).apply()

    /** Epoch ms of the last update check. */
    var lastUpdateCheck: Long
        get() = sp.getLong(KEY_UPDATE_LAST, 0L)
        set(v) = sp.edit().putLong(KEY_UPDATE_LAST, v).apply()

    /** Absolute path of the APK currently being downloaded ("" = none). */
    var pendingApkPath: String
        get() = sp.getString(KEY_PENDING_APK, "") ?: ""
        set(v) = sp.edit().putString(KEY_PENDING_APK, v).apply()


    /** কোন versionCode-এর জন্য পারমিশন ফ্লো চালানো হয়েছে — প্রতি আপডেটে একবার নudge */
    var permsAskedVersionCode: Int
        get() = sp.getInt(KEY_PERMS_ASKED_VC, -1)
        set(v) = sp.edit().putInt(KEY_PERMS_ASKED_VC, v).apply()

    companion object {
        private const val FILE               = "inweb_prefs"
        private const val KEY_BIND_LAN       = "bind_lan"
        private const val KEY_HTTP_PORT      = "http_port"
        private const val KEY_WEB_SERVER     = "web_server_engine"
        private const val KEY_MYSQL_PORT     = "mysql_port"
        private const val KEY_MYSQL_PW       = "mysql_root_pw"
        private const val KEY_MYSQL_INIT     = "mysql_initialised"
        private const val KEY_MYSQL_ENABLED  = "mysql_enabled"
        private const val KEY_AUTOSTART      = "autostart_on_boot"
        private const val KEY_THEME          = "theme_mode"
        private const val KEY_EDITOR_THEME   = "editor_theme"
        private const val KEY_EDITOR_FONT    = "editor_font_size"
        private const val KEY_HTTPS_ENABLED  = "https_enabled"
        private const val KEY_HTTPS_PORT     = "https_port"
        private const val KEY_HTTPS_FP       = "https_fingerprint"
        private const val KEY_LR_ENABLED     = "live_reload_enabled"
        private const val KEY_DNS_SRV_ENABLED = "dns_server_enabled"
        private const val KEY_DNS_SRV_PORT    = "dns_server_port"
        private const val KEY_API_ENABLED     = "api_enabled"
        private const val KEY_API_PORT        = "api_port"
        private const val KEY_API_TOKEN       = "api_token"

        // DDNS
        private const val KEY_DDNS_ENABLED     = "ddns_enabled"
        private const val KEY_DDNS_PROVIDER    = "ddns_provider"
        private const val KEY_DDNS_HOSTNAME    = "ddns_hostname"
        private const val KEY_DDNS_CREDENTIAL  = "ddns_credential"
        private const val KEY_DDNS_USERNAME    = "ddns_username"
        private const val KEY_DDNS_INTERVAL    = "ddns_interval_min"
        private const val KEY_DDNS_ON_NET      = "ddns_on_net_change"
        private const val KEY_DDNS_LAST_MS     = "ddns_last_push_ms"
        private const val KEY_DDNS_LAST_RESULT = "ddns_last_result"
        private const val KEY_DDNS_LAST_IP     = "ddns_last_ip"

        // Security
        private const val KEY_BA_ENABLED     = "sec_ba_enabled"
        private const val KEY_BA_USER        = "sec_ba_user"
        private const val KEY_BA_PASS        = "sec_ba_pass"
        private const val KEY_BA_PATHS       = "sec_ba_paths"
        private const val KEY_IP_MODE        = "sec_ip_mode"
        private const val KEY_IP_ALLOW       = "sec_ip_allow"
        private const val KEY_IP_BLOCK       = "sec_ip_block"
        private const val KEY_RL_ENABLED     = "sec_rl_enabled"
        private const val KEY_RL_RPS         = "sec_rl_rps"
        private const val KEY_RL_BURST       = "sec_rl_burst"

        private const val KEY_ONBOARDED      = "onboarded"
        private const val KEY_PERMS_ASKED_VC = "perms_asked_version_code"

    // In-app updates
        private const val KEY_AUTO_UPDATE    = "update_auto_check"
        private const val KEY_UPDATE_LAST    = "update_last_check_ms"
        private const val KEY_PENDING_APK    = "update_pending_apk"
    }
}
