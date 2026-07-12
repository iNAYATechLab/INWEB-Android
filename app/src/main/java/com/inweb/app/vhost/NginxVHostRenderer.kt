package com.inweb.app.vhost

import com.inweb.app.AssetInstaller
import com.inweb.app.util.Prefs

/**
 * Turns the user's [VirtualHost] list into Nginx `server { … }` blocks
 * that get injected into `nginx.conf` via the `__VHOSTS__` placeholder.
 *
 * Rules:
 *   – The **default** server (server_name localhost, catch-all) is always
 *     emitted from the base template.
 *   – Each enabled vhost gets its own server block on the same port with a
 *     specific `server_name` — Nginx routes by the Host header.
 *   – PHP-FPM is wired in per-vhost according to [VirtualHost.PhpMode].
 */
object NginxVHostRenderer {

    /**
     * @return the string to substitute for the `__VHOSTS__` placeholder.
     *         Empty string if no vhosts are enabled.
     */
    fun render(prefs: Prefs, layout: AssetInstaller.Layout, hosts: List<VirtualHost>): String {
        val enabled = hosts.filter { it.enabled }
        if (enabled.isEmpty()) return "# no virtual hosts configured"

        val bindAddr = if (prefs.bindLan) com.inweb.app.Constants.BIND_LAN
                       else com.inweb.app.Constants.BIND_LOCAL

        val sb = StringBuilder()
        for (vh in enabled) sb.appendLine(renderOne(vh, bindAddr, prefs.httpPort))
        return sb.toString()
    }

    private fun renderOne(vh: VirtualHost, bindAddr: String, port: Int): String = """
        # ─── Virtual host: ${vh.serverName} ───
        server {
            listen       $bindAddr:$port;
            server_name  ${vh.serverName};

            root  ${vh.documentRoot};
            index index.php index.html index.htm;

            access_log  __LOGDIR__/${vh.safeSlug()}_access.log;
            error_log   __LOGDIR__/${vh.safeSlug()}_error.log;

            ${renderAliases(vh)}

            location / {
                try_files ${'$'}uri ${'$'}uri/ /index.php?${'$'}query_string;
            }

            ${renderPhp(vh)}
        }
    """.trimIndent()

    private fun renderAliases(vh: VirtualHost): String = vh.aliases.joinToString("\n            ") {
        """
        location ${it.urlPath} {
            alias ${it.fsPath};
            autoindex off;
        }
        """.trimIndent()
    }

    private fun renderPhp(vh: VirtualHost): String = when (vh.phpMode) {
        VirtualHost.PhpMode.STATIC ->
            "location ~ \\.php\$ { return 404; }"
        VirtualHost.PhpMode.AUTO -> """
            location ~ \.php$ {
                fastcgi_pass   __PHP_FPM__;
                fastcgi_index  index.php;
                fastcgi_param  SCRIPT_FILENAME  ${'$'}document_root${'$'}fastcgi_script_name;
                fastcgi_param  QUERY_STRING     ${'$'}query_string;
                fastcgi_param  REQUEST_METHOD   ${'$'}request_method;
                fastcgi_param  CONTENT_TYPE     ${'$'}content_type;
                fastcgi_param  CONTENT_LENGTH   ${'$'}content_length;
                fastcgi_param  REQUEST_URI      ${'$'}request_uri;
                fastcgi_param  DOCUMENT_ROOT    ${'$'}document_root;
            }
        """.trimIndent()
    }

    /** Filesystem-safe slug for log filenames. */
    private fun VirtualHost.safeSlug(): String =
        serverName.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "vh" }
}
