package com.inweb.app.security

import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * Turns a [SecurityConfig] into the four Nginx snippets that get injected
 * into `nginx.conf` via placeholders:
 *
 *   __SEC_HTTP_BLOCK__      → goes inside the top-level `http { … }` (rate limit zone)
 *   __SEC_SERVER_TOP__      → goes at the top of `server { … }` (IP allow/deny)
 *   __SEC_LOCATION_ROOT__   → goes inside `location / { … }` (basic auth trigger)
 *   __SEC_AUTH_FILE__       → path to the .htpasswd (or empty comment)
 *
 * We also (re)write the `.htpasswd` file with the current user/pass.
 *
 * Design notes:
 *  - We use APR1 / MD5-crypt-compatible hashing that both Nginx and Apache
 *    understand. The implementation is a manual APR1 port (no external
 *    dependencies) — small enough to stay inline here.
 *  - IP ranges are validated only for structure; malformed entries are
 *    dropped with a warning rather than breaking the whole config.
 */
object NginxSecurityRenderer {

    private const val TAG = "NginxSecurity"

    /**
     * @return Map of placeholder → replacement string, ready to feed into
     *         AssetInstaller.materialiseConfigs's `replacements` map.
     */
    fun render(config: SecurityConfig, confDir: File): Map<String, String> {
        val htpasswd = File(confDir, ".htpasswd")

        val httpBlock    = renderHttpBlock(config)
        val serverTop    = renderServerTop(config)
        val locationRoot = renderLocationRoot(config, htpasswd)
        val authFile     = if (config.basicAuthEnabled && config.basicAuthPass.isNotEmpty()) {
            writeHtpasswd(htpasswd, config.basicAuthUser, config.basicAuthPass)
            htpasswd.absolutePath
        } else {
            "# basic auth disabled"
        }

        return mapOf(
            "__SEC_HTTP_BLOCK__"    to httpBlock,
            "__SEC_SERVER_TOP__"    to serverTop,
            "__SEC_LOCATION_ROOT__" to locationRoot,
            "__SEC_AUTH_FILE__"     to authFile,
        )
    }

    /* ---------------------------------------------------------------- */
    /*  Snippet renderers                                                */
    /* ---------------------------------------------------------------- */

    private fun renderHttpBlock(c: SecurityConfig): String {
        if (!c.rateLimitEnabled) return "# rate limit disabled"
        return """
            # Rate limit zone: ${c.rateLimitRps} req/s per client IP
            limit_req_zone \$binary_remote_addr zone=inweb_rl:10m rate=${c.rateLimitRps}r/s;
        """.trimIndent()
    }

    private fun renderServerTop(c: SecurityConfig): String {
        val lines = mutableListOf<String>()

        when (c.ipMode) {
            SecurityConfig.IpMode.OPEN -> {
                lines += "# firewall: open"
            }
            SecurityConfig.IpMode.WHITELIST -> {
                lines += "# firewall: whitelist only"
                validCidrs(c.ipAllowList).forEach { lines += "allow $it;" }
                lines += "deny all;"
            }
            SecurityConfig.IpMode.BLACKLIST -> {
                lines += "# firewall: blacklist"
                validCidrs(c.ipBlockList).forEach { lines += "deny $it;" }
                lines += "allow all;"
            }
        }

        if (c.rateLimitEnabled) {
            lines += "limit_req zone=inweb_rl burst=${c.rateLimitBurst} nodelay;"
        }
        return lines.joinToString("\n            ")
    }

    private fun renderLocationRoot(c: SecurityConfig, htpasswd: File): String {
        if (!c.basicAuthEnabled || c.basicAuthPass.isEmpty()) return "# auth disabled"
        // Empty paths list = whole site.
        if (c.basicAuthPaths.isEmpty()) {
            return """
                auth_basic           "INWEB — Restricted";
                auth_basic_user_file ${htpasswd.absolutePath};
            """.trimIndent()
        }
        // Per-path — emit dedicated location blocks. These will be inserted
        // *after* the default location block so specific paths win.
        val sb = StringBuilder()
        for (path in c.basicAuthPaths) {
            sb.append("""
                location $path {
                    auth_basic           "INWEB — Restricted";
                    auth_basic_user_file ${htpasswd.absolutePath};
                    try_files ${'$'}uri ${'$'}uri/ =404;
                }
            """.trimIndent()).append("\n")
        }
        return sb.toString()
    }

    /* ---------------------------------------------------------------- */
    /*  .htpasswd writer (APR1)                                          */
    /* ---------------------------------------------------------------- */

    private fun writeHtpasswd(file: File, user: String, password: String) {
        val hash = apr1Md5(password, randomSalt())
        file.writeText("$user:$hash\n")
        // Restrict perms best-effort.
        file.setReadable(false, false); file.setReadable(true, true)
        file.setWritable(false, false); file.setWritable(true, true)
        Log.i(TAG, "Wrote ${file.absolutePath} for user '$user'")
    }

    /** Manual APR1 MD5 crypt — compatible with `htpasswd -m` output. */
    private fun apr1Md5(password: String, salt: String): String {
        val md5 = MessageDigest.getInstance("MD5")
        val pw = password.toByteArray()
        val s  = salt.toByteArray()

        // First digest: password + magic + salt
        md5.update(pw)
        md5.update("\$apr1\$".toByteArray())
        md5.update(s)

        // Second digest: password + salt + password
        val md5b = MessageDigest.getInstance("MD5")
        md5b.update(pw); md5b.update(s); md5b.update(pw)
        var altResult = md5b.digest()

        // Add altResult from the second digest, one byte at a time.
        var cnt = pw.size
        while (cnt > 0) {
            val take = if (cnt > 16) 16 else cnt
            md5.update(altResult, 0, take)
            cnt -= take
        }
        // Weird historical bit: for each 1-bit of pw.size, append 0-byte,
        // for each 0-bit append the first byte of pw.
        cnt = pw.size
        while (cnt > 0) {
            if (cnt and 1 == 1) md5.update(byteArrayOf(0)) else md5.update(pw, 0, 1)
            cnt = cnt shr 1
        }
        var finalDigest = md5.digest()

        // 1000 rounds of hardening.
        for (i in 0 until 1000) {
            val ctx = MessageDigest.getInstance("MD5")
            if (i and 1 == 1) ctx.update(pw) else ctx.update(finalDigest)
            if (i % 3 != 0) ctx.update(s)
            if (i % 7 != 0) ctx.update(pw)
            if (i and 1 == 1) ctx.update(finalDigest) else ctx.update(pw)
            finalDigest = ctx.digest()
        }

        // APR1 custom base64 encoding
        val enc = StringBuilder()
        val order = intArrayOf(0, 6, 12, 1, 7, 13, 2, 8, 14, 3, 9, 15, 4, 10, 5)
        val idx = intArrayOf(0, 6, 12, 1, 7, 13, 2, 8, 14, 3, 9, 15, 4, 10, 5, 11)
        // Encode in groups: (finalDigest[0], [6], [12]), etc.
        val groups = arrayOf(
            Triple(0, 6, 12), Triple(1, 7, 13), Triple(2, 8, 14),
            Triple(3, 9, 15), Triple(4, 10, 5)
        )
        for ((a, b, c) in groups) {
            val v = ((finalDigest[a].toInt() and 0xff) shl 16) or
                    ((finalDigest[b].toInt() and 0xff) shl 8) or
                    (finalDigest[c].toInt() and 0xff)
            enc.append(APR1_ALPHABET[(v shr 0)  and 0x3f])
            enc.append(APR1_ALPHABET[(v shr 6)  and 0x3f])
            enc.append(APR1_ALPHABET[(v shr 12) and 0x3f])
            enc.append(APR1_ALPHABET[(v shr 18) and 0x3f])
        }
        // Final byte.
        val vv = finalDigest[11].toInt() and 0xff
        enc.append(APR1_ALPHABET[vv and 0x3f])
        enc.append(APR1_ALPHABET[(vv shr 6) and 0x3f])

        return "\$apr1\$$salt\$$enc"
    }

    private const val APR1_ALPHABET =
        "./0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

    private fun randomSalt(): String {
        val rnd = java.security.SecureRandom()
        return CharArray(8) { APR1_ALPHABET[rnd.nextInt(APR1_ALPHABET.length)] }
            .concatToString()
    }

    /* ---------------------------------------------------------------- */
    /*  Validation                                                       */
    /* ---------------------------------------------------------------- */

    private val CIDR_RX = Regex("""^\d{1,3}(\.\d{1,3}){3}(/\d{1,2})?$""")

    private fun validCidrs(list: List<String>): List<String> = list.filter { entry ->
        val ok = CIDR_RX.matches(entry)
        if (!ok) Log.w(TAG, "Ignoring malformed IP/CIDR: $entry")
        ok
    }
}
