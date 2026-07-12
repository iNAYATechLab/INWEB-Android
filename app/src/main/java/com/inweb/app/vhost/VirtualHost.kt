package com.inweb.app.vhost

import java.util.UUID

/**
 * A single virtual host — a named site that Nginx serves from its own
 * document root.
 *
 * Users configure these in the Sites screen. On save, INWEB regenerates
 * the Nginx config with one `server { … }` block per host, so the same
 * INWEB installation can host `wordpress.local`, `laravel.local`, and
 * `api.local` at the same time from a single port.
 */
data class VirtualHost(
    val id: String = UUID.randomUUID().toString(),

    /** Server name used in Nginx `server_name` — the domain the user types. */
    val serverName: String,

    /** Absolute path on-disk that Nginx serves from. Usually www/<slug>/. */
    val documentRoot: String,

    /** Optional path aliasing (e.g. /api/ → different folder). */
    val aliases: List<Alias> = emptyList(),

    /**
     * PHP behaviour:
     *  - AUTO      → serves .php via PHP-FPM (default)
     *  - STATIC    → 404 on .php requests (safer for static sites)
     */
    val phpMode: PhpMode = PhpMode.AUTO,

    /** True if this host should be part of the next reload. */
    val enabled: Boolean = true,

    /** Nice-to-have metadata for the UI. */
    val label: String = "",
    val createdAt: Long = System.currentTimeMillis(),
) {
    data class Alias(val urlPath: String, val fsPath: String)

    enum class PhpMode { AUTO, STATIC }

    val displayLabel: String
        get() = label.ifBlank { serverName }
}
