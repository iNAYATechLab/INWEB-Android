package com.inweb.app

import android.content.Context
import android.util.Log
import com.inweb.app.services.MysqlManager
import com.inweb.app.services.ServiceStatus
import com.inweb.app.services.ServiceType
import com.inweb.app.services.WebServerEngine
import com.inweb.app.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * High-level orchestrator that owns the three long-running native processes
 * INWEB supervises:
 *
 *   – Nginx      (HTTP server)
 *   – PHP-FPM    (FastCGI backend)
 *   – MariaDB    (delegated to [MysqlManager] because it needs bootstrapping)
 *
 * All mutations are guarded by a monitor. Every state transition invokes the
 * provided [onStateChange] callback so [ServerService] can broadcast per-service
 * updates to the UI.
 *
 * All calls must originate from a background thread; [ServerService] uses
 * Dispatchers.IO throughout.
 */
class ServerManager(
    private val context: Context,
    private val layout: AssetInstaller.Layout,
    private val onStateChange: (ServiceType, ServiceStatus, String?, Long?) -> Unit = { _, _, _, _ -> }
) {

    private val lock = Any()

    private var phpFpm: Process? = null
    private var nginx:  Process? = null
    private val mysql = MysqlManager(context, layout)

    private val ioScope = CoroutineScope(Dispatchers.IO)
    private val readers = mutableListOf<Job>()

    /** True if *any* supervised service is currently running. */
    val isAnyRunning: Boolean
        get() = synchronized(lock) {
            phpFpm.alive() || nginx.alive() || mysql.isRunning
        }

    fun isRunning(type: ServiceType): Boolean = synchronized(lock) {
        when (type) {
            ServiceType.NGINX   -> nginx.alive()
            ServiceType.PHP_FPM -> phpFpm.alive()
            ServiceType.MYSQL   -> mysql.isRunning
        }
    }

    fun pidOf(type: ServiceType): Long? = synchronized(lock) {
        val p = when (type) {
            ServiceType.NGINX   -> nginx
            ServiceType.PHP_FPM -> phpFpm
            ServiceType.MYSQL   -> return mysql.pid
        }
        p?.safePid()
    }

    /* ---------------------------------------------------------------- */
    /*  Public API                                                       */
    /* ---------------------------------------------------------------- */

    /** Starts one service. Web server implicitly depends on PHP-FPM (unless Node). */
    @Throws(Exception::class)
    fun start(type: ServiceType) {
        // Web server needs PHP-FPM first — unless the chosen engine is
        // static-only (Node), in which case we skip PHP entirely.
        if (type == ServiceType.NGINX && !isRunning(ServiceType.PHP_FPM)) {
            val engine = Prefs(context).webServer
            if (engine.supportsPhp) start(ServiceType.PHP_FPM)
        }
        emit(type, ServiceStatus.STARTING)
        try {
            when (type) {
                ServiceType.PHP_FPM -> startPhpFpm()
                ServiceType.NGINX   -> startNginx()
                ServiceType.MYSQL   -> mysql.start()
            }
            emit(type, ServiceStatus.RUNNING, pid = pidOf(type))
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start ${type.id}", t)
            emit(type, ServiceStatus.ERROR, message = t.message)
            // Roll back on failure so we don't leak partially-started state.
            runCatching { stopSingle(type) }
            throw t
        }
    }

    /** Stops one service. Idempotent. */
    fun stop(type: ServiceType) {
        emit(type, ServiceStatus.STOPPING)
        try { stopSingle(type) } finally {
            emit(type, ServiceStatus.STOPPED)
        }
    }

    /** Starts every service the user has enabled. */
    fun startAll() {
        val prefs = com.inweb.app.util.Prefs(context)
        // Order matters: PHP-FPM before web server (unless engine is Node),
        // then web server, then MySQL (independent).
        if (prefs.webServer.supportsPhp) start(ServiceType.PHP_FPM)
        start(ServiceType.NGINX)
        if (prefs.mysqlEnabled) {
            runCatching { start(ServiceType.MYSQL) }
                .onFailure { Log.w(TAG, "MySQL start failed (continuing without it)", it) }
        }
    }

    /** Stops every service. Nginx first so it stops accepting requests. */
    fun stopAll() = synchronized(lock) {
        stop(ServiceType.NGINX)
        stop(ServiceType.PHP_FPM)
        stop(ServiceType.MYSQL)
        readers.forEach { it.cancel() }
        readers.clear()
    }

    /* ---------------------------------------------------------------- */
    /*  Per-service implementations                                     */
    /* ---------------------------------------------------------------- */

    private fun startPhpFpm() = synchronized(lock) {
        if (phpFpm.alive()) return@synchronized
        require(layout.phpFpmBin.exists() && layout.phpFpmBin.canExecute()) {
            "php-fpm binary not found or not executable at ${layout.phpFpmBin.absolutePath}."
        }
        require(layout.phpFpmConf.exists()) {
            "php-fpm.conf missing at ${layout.phpFpmConf.absolutePath}"
        }
        phpFpm = spawn(
            tag = "php-fpm",
            command = listOf(
                layout.phpFpmBin.absolutePath,
                "--nodaemonize",
                "--fpm-config", layout.phpFpmConf.absolutePath,
                "-c", layout.phpIni.absolutePath
            ),
            workingDir = layout.prefixDir
        )
    }

    /**
     * Starts whichever web server the user picked in Settings (Nginx by default).
     * Kept under the historical `startNginx` field name (`nginx: Process?`)
     * because both engines are mutually exclusive front-ends to the same
     * PHP-FPM backend.
     */
    private fun startNginx() = synchronized(lock) {
        if (nginx.alive()) return@synchronized
        when (Prefs(context).webServer) {
            WebServerEngine.NGINX     -> spawnNginx()
            WebServerEngine.APACHE    -> spawnApache()
            WebServerEngine.LITESPEED -> spawnLiteSpeed()
            WebServerEngine.CADDY     -> spawnCaddy()
            WebServerEngine.NODE      -> spawnNode()
        }
    }

    private fun spawnNginx() {
        require(layout.nginxBin.exists() && layout.nginxBin.canExecute()) {
            "nginx binary not found or not executable at ${layout.nginxBin.absolutePath}."
        }
        require(layout.nginxConf.exists()) {
            "nginx.conf missing at ${layout.nginxConf.absolutePath}"
        }
        nginx = spawn(
            tag = "nginx",
            command = listOf(
                layout.nginxBin.absolutePath,
                "-p", layout.prefixDir.absolutePath + "/",
                "-c", layout.nginxConf.absolutePath,
                "-g", "daemon off;"
            ),
            workingDir = layout.prefixDir
        )
    }

    private fun spawnApache() {
        require(layout.apacheBin.exists() && layout.apacheBin.canExecute()) {
            "Apache (httpd) binary not found or not executable at " +
            "${layout.apacheBin.absolutePath}. Drop httpd + apachectl into " +
            "assets/server_env/bin/ and Apache modules into apache/modules/."
        }
        require(layout.apacheConf.exists()) {
            "httpd.conf missing at ${layout.apacheConf.absolutePath}"
        }
        // Ensure Apache's runtime dir exists (mod_unixd needs it).
        File(layout.tmpDir, "apache").mkdirs()

        nginx = spawn(
            tag = "httpd",
            command = listOf(
                layout.apacheBin.absolutePath,
                "-f", layout.apacheConf.absolutePath,
                "-DFOREGROUND"
            ),
            workingDir = layout.apacheServerRoot
        )
    }

    /* ---------------------------------------------------------------- */
    /*  OpenLiteSpeed                                                    */
    /* ---------------------------------------------------------------- */

    private fun spawnLiteSpeed() {
        require(layout.litespeedBin.exists() && layout.litespeedBin.canExecute()) {
            "OpenLiteSpeed binary (lshttpd) not found or not executable at " +
            "${layout.litespeedBin.absolutePath}. Drop lshttpd into " +
            "assets/server_env/bin/."
        }
        require(layout.litespeedConf.exists()) {
            "litespeed.conf missing at ${layout.litespeedConf.absolutePath}"
        }

        nginx = spawn(
            tag = "lshttpd",
            command = listOf(
                layout.litespeedBin.absolutePath,
                "-f", layout.litespeedConf.absolutePath,
                "-n"          // -n = don't fork; foreground
            ),
            workingDir = layout.litespeedServerRoot
        )
    }

    /* ---------------------------------------------------------------- */
    /*  Caddy                                                            */
    /* ---------------------------------------------------------------- */

    private fun spawnCaddy() {
        require(layout.caddyBin.exists() && layout.caddyBin.canExecute()) {
            "Caddy binary not found or not executable at " +
            "${layout.caddyBin.absolutePath}. Download the single-file " +
            "Caddy binary for linux/arm64 from https://caddyserver.com/download " +
            "and drop it into assets/server_env/bin/."
        }
        require(layout.caddyfile.exists()) {
            "Caddyfile missing at ${layout.caddyfile.absolutePath}"
        }

        nginx = spawn(
            tag = "caddy",
            command = listOf(
                layout.caddyBin.absolutePath,
                "run",
                "--config", layout.caddyfile.absolutePath,
                "--adapter", "caddyfile"
            ),
            workingDir = layout.prefixDir
        )
    }

    /* ---------------------------------------------------------------- */
    /*  Node.js (bundled http-server)                                    */
    /* ---------------------------------------------------------------- */

    private fun spawnNode() {
        require(layout.nodeBin.exists() && layout.nodeBin.canExecute()) {
            "Node.js binary not found or not executable at " +
            "${layout.nodeBin.absolutePath}. Drop a static ARM64 `node` " +
            "binary into assets/server_env/bin/ — see " +
            "https://unofficial-builds.nodejs.org/download/release/ for " +
            "musl-linked builds that work on Android."
        }
        require(layout.nodeServerScript.exists()) {
            "server.js missing at ${layout.nodeServerScript.absolutePath}"
        }

        val prefs = Prefs(context)
        val bindAddr = if (prefs.bindLan) Constants.BIND_LAN else Constants.BIND_LOCAL

        // The node script reads config from env vars — no CLI parsing needed.
        nginx = spawn(
            tag = "node",
            command = listOf(
                layout.nodeBin.absolutePath,
                layout.nodeServerScript.absolutePath
            ),
            workingDir = layout.prefixDir,
            extraEnv = mapOf(
                "INWEB_ROOT" to layout.docRoot.absolutePath,
                "INWEB_PORT" to prefs.httpPort.toString(),
                "INWEB_BIND" to bindAddr,
            )
        )
    }

    private fun stopSingle(type: ServiceType) = synchronized(lock) {
        when (type) {
            ServiceType.NGINX -> {
                nginx?.destroyGracefully("nginx"); nginx = null
            }
            ServiceType.PHP_FPM -> {
                phpFpm?.destroyGracefully("php-fpm"); phpFpm = null
            }
            ServiceType.MYSQL -> {
                mysql.stop()
            }
        }
    }

    /* ---------------------------------------------------------------- */
    /*  Process helpers                                                 */
    /* ---------------------------------------------------------------- */

    private fun spawn(
        tag: String,
        command: List<String>,
        workingDir: File,
        extraEnv: Map<String, String> = emptyMap()
    ): Process {
        Log.i(TAG, "Spawning [$tag]: ${command.joinToString(" ")}")
        val pb = ProcessBuilder(command).directory(workingDir).redirectErrorStream(true)

        val env = pb.environment()
        // ⚠️ CRITICAL: our libs live in lib/, binaries in bin/. Without lib/
        // on LD_LIBRARY_PATH every Termux binary dies with "library not found".
        env["LD_LIBRARY_PATH"] = listOfNotNull(
            layout.libDir.absolutePath,
            layout.binDir.absolutePath,
            env["LD_LIBRARY_PATH"]
        ).joinToString(":")
        env["PATH"]   = layout.binDir.absolutePath + ":" + (env["PATH"] ?: "/system/bin")
        env["PREFIX"] = layout.prefixDir.absolutePath
        env["TMPDIR"] = layout.tmpDir.absolutePath
        env["HOME"]   = layout.prefixDir.absolutePath
        // CA bundle bundled from ca-certificates — needed by curl/cloudflared/openssl s_client
        val caBundle = File(layout.prefixDir, "etc/tls/cert.pem")
        if (caBundle.exists()) env["SSL_CERT_FILE"] = caBundle.absolutePath
        for ((k, v) in extraEnv) env[k] = v

        val proc = pb.start()
        readers += ioScope.launch { drainToLog(tag, proc) }
        return proc
    }

    private fun drainToLog(tag: String, proc: Process) {
        try {
            BufferedReader(InputStreamReader(proc.inputStream)).use { r ->
                var line: String? = r.readLine()
                while (line != null) {
                    Log.i("$TAG/$tag", line!!)
                    line = r.readLine()
                }
            }
        } catch (_: Throwable) { /* stream closed on shutdown */ }
    }

    private fun Process?.alive(): Boolean = this != null && this.isAlive

    private fun Process.destroyGracefully(tag: String) {
        if (!isAlive) return
        Log.i(TAG, "Stopping $tag (pid=${pidOrNull()})")
        try {
            destroy()
            if (!waitForQuietly(GRACE_MS)) {
                Log.w(TAG, "$tag did not exit in ${GRACE_MS}ms, killing forcibly.")
                destroyForcibly()
                waitForQuietly(GRACE_MS)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Error stopping $tag", t)
        }
    }

    private fun Process.pidOrNull(): Long? = safePid()

    /**
     * Reflection-based pid extraction. Works on all Android API levels
     * including where java.lang.Process.pid() isn't available directly
     * (e.g. some AOSP forks with stripped stdlib annotations).
     */
    private fun Process.safePid(): Long? = try {
        val m = this::class.java.getMethod("pid")
        (m.invoke(this) as? Long) ?: (m.invoke(this) as? Int)?.toLong()
    } catch (_: Throwable) {
        // Fallback: parse the toString() which usually contains pid=N
        val s = this.toString()
        Regex("pid=(\\d+)").find(s)?.groupValues?.get(1)?.toLongOrNull()
    }

    private fun Process.waitForQuietly(millis: Long): Boolean = try {
        val start = System.currentTimeMillis()
        while (isAlive && System.currentTimeMillis() - start < millis) Thread.sleep(50)
        !isAlive
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt(); false
    }

    private fun emit(type: ServiceType, status: ServiceStatus, message: String? = null, pid: Long? = null) {
        try { onStateChange(type, status, message, pid) } catch (_: Throwable) {}
    }

    companion object {
        private const val TAG = "ServerManager"
        private const val GRACE_MS = 3_000L
    }
}
