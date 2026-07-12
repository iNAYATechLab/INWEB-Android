package com.inweb.app

object Constants {

    /* ---------- Ports & bindings ---------- */
    const val DEFAULT_HTTP_PORT  = 8080
    const val DEFAULT_MYSQL_PORT = 3306
    const val PHP_FPM_ADDR       = "127.0.0.1:9000"

    /** listen bind addresses (used in template placeholders). */
    const val BIND_LOCAL = "127.0.0.1"
    const val BIND_LAN   = "0.0.0.0"

    /* ---------- Service actions (sent to ServerService) ---------- */
    // Legacy actions kept for backwards-compat with foreground start intents.
    const val ACTION_START = "com.inweb.app.action.START"       // start ALL
    const val ACTION_STOP  = "com.inweb.app.action.STOP"        // stop ALL

    // Per-service granular actions used by the Services dashboard.
    const val ACTION_START_ONE = "com.inweb.app.action.START_ONE"
    const val ACTION_STOP_ONE  = "com.inweb.app.action.STOP_ONE"
    const val EXTRA_SERVICE_ID = "service_id"

    /* ---------- Broadcasts ---------- */
    /** Legacy "any service running" state (kept for MainActivity toggle). */
    const val ACTION_STATE_CHANGED = "com.inweb.app.STATE_CHANGED"
    const val EXTRA_RUNNING        = "running"
    const val EXTRA_MESSAGE        = "message"

    /** Per-service state change. Extras: service_id, status, message. */
    const val ACTION_SERVICE_STATE = "com.inweb.app.SERVICE_STATE"
    const val EXTRA_STATUS         = "status"
    const val EXTRA_PID            = "pid"

    /* ---------- Notification ---------- */
    const val NOTIF_CHANNEL_ID   = "local_server_channel"
    const val NOTIF_CHANNEL_NAME = "INWEB Server"
    const val NOTIF_ID           = 1042

    /* ---------- Asset layout inside assets/server_env/ ---------- */
    const val ASSET_ROOT       = "server_env"
    const val ASSET_BIN_DIR    = "bin"
    const val ASSET_CONF_DIR   = "conf"
    const val ASSET_PHP_DIR    = "php"
    const val ASSET_MYSQL_DIR  = "mysql"
    const val ASSET_PMA_DIR    = "phpmyadmin"

    /* ---------- Files that must be marked executable ---------- */
    val EXECUTABLES = listOf(
        // Web servers (user picks one at runtime via Settings)
        "nginx",
        "httpd", "apachectl",       // Apache HTTP Server
        "lshttpd", "litespeed",     // OpenLiteSpeed (binary name varies by build)
        "caddy",                    // Caddy
        "node", "npm",              // Node.js
        // PHP
        "php", "php-fpm",
        // Database
        "mariadbd", "mysqld", "mysql", "mysql_install_db", "mysqladmin"
    )

    /* ---------- Web root ---------- */
    const val WWW_DIR = "www"
    /** phpMyAdmin is extracted under www/phpmyadmin so it's served over HTTP. */
    const val PMA_SUBDIR = "phpmyadmin"
}
