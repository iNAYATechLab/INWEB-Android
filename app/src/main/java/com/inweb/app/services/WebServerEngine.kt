package com.inweb.app.services

/**
 * Which HTTP server INWEB should run in front of PHP-FPM.
 *
 * User picks in **Settings → Web Server**. Each engine has different
 * strengths:
 *
 *   NGINX     — battle-tested, event-driven, fast (default)
 *   APACHE    — .htaccess support, WordPress-friendly
 *   LITESPEED — OpenLiteSpeed — extremely fast, LSAPI, LSCache
 *   CADDY     — tiny binary, zero-config, automatic HTTPS
 *   NODE      — Node.js http-server for static + JS devs
 *
 * All five proxy PHP requests to the same PHP-FPM socket on
 * 127.0.0.1:9000 (except NODE, which is static-only).
 */
enum class WebServerEngine(
    val id: String,
    val displayName: String,
    val tagline: String,
    /** Filename of the primary binary that must exist in assets/server_env/bin/. */
    val binaryName: String,
    /** Filename of the rendered config file inside conf/ (blank for engines with no config file). */
    val configName: String,
    /** True if this engine forwards *.php to PHP-FPM. False = static-only (Node http-server). */
    val supportsPhp: Boolean = true
) {
    NGINX(
        id = "nginx", displayName = "Nginx",
        tagline = "Battle-tested, event-driven, fast",
        binaryName = "nginx", configName = "nginx.conf"
    ),

    APACHE(
        id = "apache", displayName = "Apache",
        tagline = ".htaccess support · WordPress-friendly",
        binaryName = "httpd", configName = "httpd.conf"
    ),

    LITESPEED(
        id = "litespeed", displayName = "OpenLiteSpeed",
        tagline = "Fastest for high traffic · LSCache",
        binaryName = "lshttpd", configName = "litespeed.conf"
    ),

    CADDY(
        id = "caddy", displayName = "Caddy",
        tagline = "Tiny · zero-config · auto HTTPS",
        binaryName = "caddy", configName = "Caddyfile"
    ),

    NODE(
        id = "node", displayName = "Node.js (http-server)",
        tagline = "Perfect for JS devs · static files",
        binaryName = "node", configName = "",
        supportsPhp = false
    );

    companion object {
        fun fromId(id: String?): WebServerEngine =
            entries.firstOrNull { it.id == id } ?: NGINX
    }
}
