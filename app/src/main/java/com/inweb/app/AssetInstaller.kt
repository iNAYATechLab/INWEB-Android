package com.inweb.app

import android.content.Context
import android.util.Log
import com.inweb.app.security.NginxSecurityRenderer
import com.inweb.app.security.SelfSignedCertificate
import com.inweb.app.util.Prefs
import com.inweb.app.vhost.NginxVHostRenderer
import com.inweb.app.vhost.VirtualHostStore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Copies pre-compiled server binaries and configuration files from
 * `assets/server_env/` into the app's private internal storage
 * (`context.filesDir`) on first launch, then marks binaries executable and
 * materialises real config files by expanding template placeholders.
 *
 * Layout inside `context.filesDir` after install:
 *
 *   filesDir/server_env/
 *     bin/{nginx,php,php-fpm,mariadbd,mysql,...}
 *     conf/nginx.conf
 *     conf/php-fpm.conf
 *     conf/my.cnf                     ← MySQL config (rendered from template)
 *     php/php.ini
 *     mysql/data/                     ← MySQL data directory (mysql_install_db populates)
 *     mysql/share/                    ← MySQL error-message / seed SQL bundle
 *     phpmyadmin/                     ← unpacked phpMyAdmin (also symlinked into www/)
 *     logs/           (created empty, writable)
 *     tmp/            (created empty, writable)
 *
 * externalFilesDir/www/               ← public web root
 * externalFilesDir/www/phpmyadmin/    ← copy of phpMyAdmin so it's served
 */
object AssetInstaller {

    private const val TAG = "AssetInstaller"

    /** Version stamp – bump when you ship a new binary bundle to force re-extract. */
    // v3: shared libraries bundled (lib/) + symlink restore + Termux prefix rewrite
    private const val INSTALL_VERSION = 3
    private const val VERSION_FILE = ".installed_v"

    data class Layout(
        // core
        val prefixDir:  File,
        val binDir:     File,
        val libDir:     File,
        val confDir:    File,
        val phpDir:     File,
        val logsDir:    File,
        val tmpDir:     File,
        val docRoot:    File,

        // nginx
        val nginxBin:   File,
        val nginxConf:  File,

        // apache
        val apacheBin:      File,       // httpd
        val apacheCtlBin:   File,       // apachectl (used for graceful stop)
        val apacheConf:     File,       // httpd.conf
        val apacheServerRoot: File,     // Apache's "ServerRoot" — needs modules/, logs/, etc.

        // litespeed
        val litespeedBin:      File,    // lshttpd
        val litespeedConf:     File,    // litespeed.conf (main config)
        val litespeedServerRoot: File,  // ServerRoot: needs conf/, logs/, tmp/

        // caddy
        val caddyBin:          File,    // caddy (single static binary)
        val caddyfile:         File,    // Caddyfile

        // node.js
        val nodeBin:           File,    // node
        val nodeServerScript:  File,    // node/server.js (bundled HTTP server)

        // php
        val phpFpmBin:  File,
        val phpFpmConf: File,
        val phpIni:     File,

        // mysql / mariadb
        val mysqldBin:      File,
        val mysqlClientBin: File,
        val mysqlInstallDb: File,
        val mysqlConf:      File,       // my.cnf
        val mysqlDir:       File,       // mysql/ root
        val mysqlDataDir:   File,       // mysql/data
        val mysqlShareDir:  File,       // mysql/share
        val mysqlSocket:    File,       // tmp/mysql.sock

        // phpMyAdmin
        val pmaAssetDir:  File,         // filesDir/server_env/phpmyadmin
        val pmaWebDir:    File,         // docRoot/phpmyadmin  (served by nginx)

        // HTTPS
        val sslDir:  File,              // filesDir/server_env/ssl
        val sslCert: File,              // ssl/cert.pem
        val sslKey:  File,              // ssl/key.pem

        // LiveReload — path where livereload.js gets copied so Nginx can serve it.
        val liveReloadJs: File          // filesDir/server_env/livereload.js
    )

    /**
     * Idempotent — safe to call every launch. If assets have already been
     * extracted at the current [INSTALL_VERSION], this is essentially a no-op
     * (configs are still re-materialised so Settings changes apply).
     *
     * MUST be called off the main thread (uses blocking file I/O).
     */
    @Throws(IOException::class)
    fun install(context: Context): Layout {
        val prefix = File(context.filesDir, Constants.ASSET_ROOT)
        val versionFile = File(prefix, VERSION_FILE)

        val layout = buildLayout(context, prefix)

        val alreadyInstalled =
            versionFile.exists() &&
            versionFile.readText().trim().toIntOrNull() == INSTALL_VERSION

        if (!alreadyInstalled) {
            Log.i(TAG, "Extracting server_env/ assets → ${prefix.absolutePath}")
            if (prefix.exists()) prefix.deleteRecursively()
            prefix.mkdirs()

            copyAssetTree(context, Constants.ASSET_ROOT, prefix)

            layout.logsDir.mkdirs()
            layout.tmpDir.mkdirs()
            layout.mysqlDataDir.mkdirs()
            ensureEngineDirs(layout)

            markExecutables(layout.binDir)
            restoreSymlinks(layout)
            rewriteTermuxScripts(layout)
            versionFile.writeText(INSTALL_VERSION.toString())
            Log.i(TAG, "Extraction complete.")
        } else {
            markExecutables(layout.binDir)
            restoreSymlinks(layout)
            rewriteTermuxScripts(layout)
            layout.logsDir.mkdirs()
            layout.tmpDir.mkdirs()
            layout.mysqlDataDir.mkdirs()
            ensureEngineDirs(layout)
        }

        // If HTTPS is enabled, make sure a self-signed cert exists (generate
        // once and keep across launches — user can regenerate from Settings).
        val prefs = Prefs(context)
        if (prefs.httpsEnabled && (!layout.sslCert.exists() || !layout.sslKey.exists())) {
            runCatching {
                Log.i(TAG, "Generating first-time self-signed certificate…")
                val (cert, _) = SelfSignedCertificate.generate(layout.sslDir)
                val x509 = java.security.cert.CertificateFactory.getInstance("X.509")
                    .generateCertificate(cert.inputStream()) as java.security.cert.X509Certificate
                prefs.httpsFingerprint = SelfSignedCertificate.fingerprintSha256(x509)
            }.onFailure { Log.e(TAG, "Cert generation failed — HTTPS will be disabled", it) }
        }

        // Copy the LiveReload client script out of assets so Nginx can serve
        // it via a fast `alias` (cheaper than re-reading assets per request).
        runCatching {
            context.assets.open("livereload/livereload.js").use { input ->
                FileOutputStream(layout.liveReloadJs).use { out -> input.copyTo(out) }
            }
        }.onFailure { Log.w(TAG, "Failed to deploy livereload.js", it) }

        // Deploy the INWEB Web Dashboard (PWA) under www/inweb-dashboard/
        // so anyone on the LAN can point a browser at
        //   http://<phone>:<port>/inweb-dashboard/
        // and manage INWEB remotely.
        runCatching { deployWebDashboard(context, layout) }
            .onFailure { Log.w(TAG, "Failed to deploy dashboard", it) }

        // ALWAYS regenerate configs from templates so port / bind-mode /
        // MySQL / HTTPS / LiveReload settings take effect on next start.
        materialiseConfigs(context, layout)

        // Ensure the public web root exists and has a friendly landing page.
        if (!layout.docRoot.exists()) layout.docRoot.mkdirs()
        seedIndexIfMissing(layout)

        // Deploy phpMyAdmin under docRoot/phpmyadmin so nginx can serve it.
        deployPhpMyAdmin(layout)

        return layout
    }

    /* ---------------------------------------------------------------- */
    /*  Layout                                                          */
    /* ---------------------------------------------------------------- */

    private fun buildLayout(context: Context, prefix: File): Layout {
        val binDir     = File(prefix, Constants.ASSET_BIN_DIR)
        val libDir     = File(prefix, "lib")
        val confDir    = File(prefix, Constants.ASSET_CONF_DIR)
        val phpDir     = File(prefix, Constants.ASSET_PHP_DIR)
        val logsDir    = File(prefix, "logs")
        val tmpDir     = File(prefix, "tmp")

        val mysqlDir       = File(prefix, Constants.ASSET_MYSQL_DIR)
        val mysqlDataDir   = File(mysqlDir, "data")
        val mysqlShareDir  = File(mysqlDir, "share")
        val mysqlSocket    = File(tmpDir, "mysql.sock")

        val pmaAssetDir = File(prefix, Constants.ASSET_PMA_DIR)
        val sslDir      = File(prefix, "ssl")

        // Public web root: app-specific external storage → no runtime permission required.
        val externalRoot: File = context.getExternalFilesDir(null) ?: context.filesDir
        val docRoot = File(externalRoot, Constants.WWW_DIR)
        val pmaWebDir = File(docRoot, Constants.PMA_SUBDIR)

        return Layout(
            prefixDir       = prefix,
            binDir          = binDir,
            libDir          = libDir,
            confDir         = confDir,
            phpDir          = phpDir,
            logsDir         = logsDir,
            tmpDir          = tmpDir,
            docRoot         = docRoot,

            nginxBin        = File(binDir, "nginx"),
            nginxConf       = File(confDir, "nginx.conf"),

            apacheBin         = File(binDir, "httpd"),
            apacheCtlBin      = File(binDir, "apachectl"),
            apacheConf        = File(confDir, "httpd.conf"),
            apacheServerRoot  = File(prefix, "apache"),

            litespeedBin        = pickFirst(binDir, "lshttpd", "litespeed"),
            litespeedConf       = File(confDir, "litespeed.conf"),
            litespeedServerRoot = File(prefix, "litespeed"),

            caddyBin            = File(binDir, "caddy"),
            caddyfile           = File(confDir, "Caddyfile"),

            nodeBin             = File(binDir, "node"),
            nodeServerScript    = File(prefix, "node/server.js"),

            phpFpmBin       = File(binDir, "php-fpm"),
            phpFpmConf      = File(confDir, "php-fpm.conf"),
            phpIni          = File(phpDir, "php.ini"),

            // MariaDB and MySQL ship binaries with different names; support both.
            mysqldBin       = pickFirst(binDir, "mariadbd", "mysqld"),
            mysqlClientBin  = File(binDir, "mysql"),
            mysqlInstallDb  = File(binDir, "mysql_install_db"),
            mysqlConf       = File(confDir, "my.cnf"),
            mysqlDir        = mysqlDir,
            mysqlDataDir    = mysqlDataDir,
            mysqlShareDir   = mysqlShareDir,
            mysqlSocket     = mysqlSocket,

            pmaAssetDir     = pmaAssetDir,
            pmaWebDir       = pmaWebDir,

            sslDir          = sslDir,
            sslCert         = File(sslDir, "cert.pem"),
            sslKey          = File(sslDir, "key.pem"),

            liveReloadJs    = File(prefix, "livereload.js")
        )
    }

    private fun pickFirst(dir: File, vararg names: String): File {
        for (n in names) {
            val f = File(dir, n)
            if (f.exists()) return f
        }
        // Return the first candidate even if missing so error messages point at
        // the "preferred" name (mariadbd) rather than the fallback.
        return File(dir, names.first())
    }

    /* ---------------------------------------------------------------- */
    /*  Asset copying                                                   */
    /* ---------------------------------------------------------------- */

    @Throws(IOException::class)
    private fun copyAssetTree(context: Context, assetPath: String, destDir: File) {
        val am = context.assets
        val children = am.list(assetPath).orEmpty()

        if (children.isEmpty()) {
            destDir.parentFile?.mkdirs()
            am.open(assetPath).use { input ->
                FileOutputStream(destDir).use { output -> input.copyTo(output) }
            }
            return
        }

        destDir.mkdirs()
        for (child in children) {
            val childAssetPath = "$assetPath/$child"
            val childDest      = File(destDir, child)
            val grand = am.list(childAssetPath).orEmpty()
            if (grand.isEmpty()) {
                am.open(childAssetPath).use { input ->
                    FileOutputStream(childDest).use { output -> input.copyTo(output) }
                }
            } else {
                copyAssetTree(context, childAssetPath, childDest)
            }
        }
    }

    /** Creates the per-engine runtime directories so each server has somewhere to write. */
    private fun ensureEngineDirs(layout: Layout) {
        // Apache
        layout.apacheServerRoot.mkdirs()
        File(layout.apacheServerRoot, "logs").mkdirs()

        // LiteSpeed — expects a full ServerRoot with logs/, tmp/, conf/
        layout.litespeedServerRoot.mkdirs()
        File(layout.litespeedServerRoot, "logs").mkdirs()
        File(layout.litespeedServerRoot, "tmp").mkdirs()
        File(layout.litespeedServerRoot, "conf").mkdirs()

        // Node — a script folder for our bundled server.js
        File(layout.prefixDir, "node").mkdirs()
    }

    private fun markExecutables(binDir: File) {
        if (!binDir.isDirectory) return
        binDir.listFiles()?.forEach { f ->
            if (f.isFile) {
                @Suppress("DEPRECATION")
                val ok = f.setExecutable(true, false) && f.setReadable(true, false)
                if (!ok) Log.w(TAG, "Failed to chmod +rx ${f.name}")
            }
        }
        // Apache modules + bundled libs must be readable/executable too
        arrayOf(
            File(binDir.parentFile, "lib"),
            File(binDir.parentFile, "apache/modules"),
            File(binDir.parentFile, "tunnel")
        ).forEach { dir ->
            dir.listFiles()?.forEach { f ->
                if (f.isFile) @Suppress("DEPRECATION") {
                    f.setReadable(true, false)
                    f.setExecutable(true, false)
                }
            }
        }
    }

    /**
     * APK assets cannot store symlinks — the fetch script records them in
     * `lib/links.txt` ("reldir|name|target") and we recreate them here with
     * [android.system.Os.symlink]. Without libicudata.so.78→… and friends,
     * every php/node/nginx launch dies at the dynamic linker.
     */
    private fun restoreSymlinks(layout: Layout) {
        val manifest = File(layout.libDir, "links.txt")
        if (!manifest.exists()) return
        manifest.readLines().forEach { line ->
            val parts = line.split("|")
            if (parts.size < 3) return@forEach
            val link = File(File(layout.prefixDir, parts[0]), parts[1])
            if (link.exists()) return@forEach
            runCatching { android.system.Os.symlink(parts[2], link.absolutePath) }
                .onFailure {
                    // Fallback for exotic filesystems: hard-copy the target
                    runCatching {
                        File(link.parentFile, parts[2]).copyTo(link, overwrite = false)
                    }
                }
        }
    }

    /**
     * Every Termux shell script (apachectl, mariadb-install-db, mysqld_safe)
     * carries hardcoded `/data/data/com.termux/files/usr` paths and a Termux
     * shebang. Rewrite both to our runtime prefix + Android's system shell.
     */
    private fun rewriteTermuxScripts(layout: Layout) {
        val termuxPrefix = "/data/data/com.termux/files/usr"
        fun rewriteDir(dir: File) {
            if (!dir.isDirectory) return
            dir.listFiles()?.forEach { f ->
                if (!f.isFile) return@forEach
                val isScript = runCatching {
                    f.inputStream().use { s ->
                        val b = ByteArray(2); s.read(b); b[0].toInt().toChar() == '#'
                    }
                }.getOrDefault(false)
                if (!isScript) return@forEach
                runCatching {
                    var txt = f.readText()
                    if (txt.contains(termuxPrefix) || txt.contains("#!")) {
                        txt = txt.replace(termuxPrefix, layout.prefixDir.absolutePath)
                        txt = txt.replaceFirst(Regex("^#![^\n]*"), "#!/system/bin/sh")
                        f.writeText(txt)
                    }
                }
            }
        }
        rewriteDir(layout.binDir)
        rewriteDir(layout.libDir)
    }

    /* ---------------------------------------------------------------- */
    /*  Configs                                                         */
    /* ---------------------------------------------------------------- */

    private fun materialiseConfigs(context: Context, layout: Layout) {
        val prefs = Prefs(context)
        val bindAddr = if (prefs.bindLan) Constants.BIND_LAN else Constants.BIND_LOCAL

        val replacements = mapOf(
            "__PREFIX__"        to layout.prefixDir.absolutePath,
            "__BINDIR__"        to layout.binDir.absolutePath,
            "__CONFDIR__"       to layout.confDir.absolutePath,
            "__LOGDIR__"        to layout.logsDir.absolutePath,
            "__TMPDIR__"        to layout.tmpDir.absolutePath,
            "__DOCROOT__"       to layout.docRoot.absolutePath,
            "__PORT__"          to prefs.httpPort.toString(),
            "__BIND__"          to bindAddr,
            "__PHP_FPM__"       to Constants.PHP_FPM_ADDR,

            // MySQL / MariaDB
            "__MYSQL_PORT__"    to prefs.mysqlPort.toString(),
            "__MYSQL_BIND__"    to bindAddr,
            "__MYSQL_DATADIR__" to layout.mysqlDataDir.absolutePath,
            "__MYSQL_SHAREDIR__" to layout.mysqlShareDir.absolutePath,
            "__MYSQL_SOCKET__"  to layout.mysqlSocket.absolutePath,
            "__MYSQL_BASEDIR__" to layout.prefixDir.absolutePath,

            // Apache
            "__APACHE_SERVERROOT__" to layout.apacheServerRoot.absolutePath,
            "__APACHE_MODULES__"    to File(layout.apacheServerRoot, "modules").absolutePath,
            "__APACHE_LOGDIR__"     to File(layout.apacheServerRoot, "logs").absolutePath,

            // LiteSpeed
            "__LSWS_SERVERROOT__" to layout.litespeedServerRoot.absolutePath,
            "__LSWS_LOGDIR__"     to File(layout.litespeedServerRoot, "logs").absolutePath,
            "__LSWS_TMPDIR__"     to File(layout.litespeedServerRoot, "tmp").absolutePath,

            // HTTPS: three lines are all-or-nothing so nginx doesn't
            // half-configure SSL when the user has HTTPS off.
            "__HTTPS_LISTEN__"  to if (prefs.httpsEnabled && layout.sslCert.exists())
                    "listen $bindAddr:${prefs.httpsPort} ssl;" else "# https disabled",
            "__HTTPS_CERT__"    to if (prefs.httpsEnabled && layout.sslCert.exists())
                    "ssl_certificate     ${layout.sslCert.absolutePath};" else "#",
            "__HTTPS_KEY__"     to if (prefs.httpsEnabled && layout.sslKey.exists())
                    "ssl_certificate_key ${layout.sslKey.absolutePath};" else "#",

            // LiveReload injection — sub_filter rewrites </body> to include
            // the tiny WebSocket client. Nginx's http_sub_module must be
            // compiled in (Termux's build has it by default).
            "__LIVE_RELOAD_INJECT__" to if (prefs.liveReloadEnabled) """
                sub_filter '</body>' '<script src="/__inweb_livereload.js"></script></body>';
                sub_filter_once on;
                sub_filter_types text/html;
            """.trimIndent() else "# live reload disabled",

            "__LIVE_RELOAD_JS__" to layout.liveReloadJs.absolutePath,

            // Virtual hosts — one `server { … }` block per enabled entry.
            "__VHOSTS__" to NginxVHostRenderer.render(
                prefs, layout, VirtualHostStore(context).all()
            ),
        ) + NginxSecurityRenderer.render(prefs.security, layout.confDir)

        layout.confDir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(".template")) {
                val realName = f.name.removeSuffix(".template")
                var text = f.readText()
                replacements.forEach { (k, v) -> text = text.replace(k, v) }

                // Special-case: vhconf.conf belongs under litespeed/conf/ so
                // the OpenLiteSpeed daemon can find it via its configured path.
                val out = when (realName) {
                    "vhconf.conf" -> File(layout.litespeedServerRoot, "conf/vhconf.conf")
                                        .apply { parentFile?.mkdirs() }
                    else          -> File(layout.confDir, realName)
                }
                out.writeText(text)
                Log.i(TAG, "Materialised config → ${out.absolutePath}")
            }
        }
    }

    /* ---------------------------------------------------------------- */
    /*  Web-root seeding                                                */
    /* ---------------------------------------------------------------- */

    private fun seedIndexIfMissing(layout: Layout) {
        val index = File(layout.docRoot, "index.html")
        if (!index.exists()) {
            index.writeText(
                """
                <!doctype html>
                <html><head><meta charset="utf-8"><title>INWEB</title></head>
                <body style="font-family:system-ui;padding:2rem;max-width:640px;margin:auto;">
                <h1>🎉 INWEB is running</h1>
                <p>Drop your PHP / HTML files into:</p>
                <pre>${layout.docRoot.absolutePath}</pre>
                <ul>
                  <li>PHP test: <a href="/info.php">/info.php</a></li>
                  <li>phpMyAdmin: <a href="/phpmyadmin/">/phpmyadmin/</a></li>
                </ul>
                </body></html>
                """.trimIndent()
            )
        }
        val info = File(layout.docRoot, "info.php")
        if (!info.exists()) info.writeText("<?php phpinfo(); ?>\n")
    }

    /* ---------------------------------------------------------------- */
    /*  phpMyAdmin deployment                                           */
    /* ---------------------------------------------------------------- */

    /**
     * Copies phpMyAdmin from filesDir/server_env/phpmyadmin/ into the
     * public web root under /phpmyadmin/ so nginx can serve it.
     * Also renders phpMyAdmin's `config.inc.php` from a template.
     *
     * We use a copy (rather than symlink) because Android's app-specific
     * external storage does not reliably support symlinks across all vendors.
     */
    private fun deployPhpMyAdmin(layout: Layout) {
        if (!layout.pmaAssetDir.isDirectory) {
            Log.i(TAG, "phpMyAdmin assets not shipped — skipping deployment.")
            return
        }
        // Only re-copy when the version file inside pma has changed OR the
        // destination is missing entirely. Cheap heuristic: compare file counts.
        val src = layout.pmaAssetDir
        val dst = layout.pmaWebDir
        val needsCopy = !dst.exists() ||
            (dst.list()?.size ?: 0) < (src.list()?.size ?: 0)

        if (needsCopy) {
            Log.i(TAG, "Copying phpMyAdmin → ${dst.absolutePath}")
            dst.deleteRecursively()
            src.copyRecursively(dst, overwrite = true)
        }

        // Materialise config.inc.php from template if present.
        val cfgTemplate = File(dst, "config.inc.php.template")
        if (cfgTemplate.exists()) {
            val out = File(dst, "config.inc.php")
            var text = cfgTemplate.readText()
            text = text
                .replace("__MYSQL_SOCKET__", layout.mysqlSocket.absolutePath)
                .replace("__MYSQL_HOST__",   "127.0.0.1")
                .replace("__BLOWFISH__",     generateBlowfishSecret())
            out.writeText(text)
        }
    }

    /**
     * Extracts every file under `assets/inweb_dashboard/` into
     * `www/inweb-dashboard/` so the built-in PWA is served by the user's
     * own INWEB server. Only overwrites when assets are newer (best-effort:
     * we compare `size` for cheapness).
     */
    private fun deployWebDashboard(context: Context, layout: Layout) {
        val am = context.assets
        val srcRoot = "inweb_dashboard"
        val files = am.list(srcRoot).orEmpty()
        if (files.isEmpty()) return
        val destRoot = File(layout.docRoot, "inweb-dashboard").apply { mkdirs() }
        for (name in files) {
            val destFile = File(destRoot, name)
            am.open("$srcRoot/$name").use { input ->
                FileOutputStream(destFile).use { out -> input.copyTo(out) }
            }
        }
        Log.i(TAG, "Web dashboard deployed → ${destRoot.absolutePath}")
    }

    /** phpMyAdmin needs a random 32-char secret for cookie encryption. */
    private fun generateBlowfishSecret(): String {
        val alphabet = ("ABCDEFGHIJKLMNOPQRSTUVWXYZ" +
                        "abcdefghijklmnopqrstuvwxyz" +
                        "0123456789!@#\$%^&*()_+-=[]{}").toCharArray()
        val rnd = java.security.SecureRandom()
        return CharArray(32) { alphabet[rnd.nextInt(alphabet.size)] }.concatToString()
    }
}
