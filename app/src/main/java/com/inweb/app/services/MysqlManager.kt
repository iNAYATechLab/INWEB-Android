package com.inweb.app.services

import android.content.Context
import android.util.Log
import com.inweb.app.AssetInstaller
import com.inweb.app.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Manages the MariaDB (aka mysqld) process lifecycle.
 *
 * First-run flow:
 *   1. If mysql/data/ is empty, call `mysql_install_db --datadir=…` (or
 *      `mysqld --initialize-insecure` for newer MariaDB) to populate the
 *      system tables.
 *   2. Generate & save a random root password in [Prefs].
 *   3. Start `mariadbd` bound to the configured port + socket.
 *   4. Once ready, run `mysqladmin password '<pw>'` to seal the account.
 *
 * Subsequent runs simply spawn `mariadbd` with the already-initialised
 * data directory.
 */
class MysqlManager(
    private val context: Context,
    private val layout: AssetInstaller.Layout
) {
    private val lock = Any()
    private var process: Process? = null
    private val ioScope = CoroutineScope(Dispatchers.IO)
    private var logJob: Job? = null

    val isRunning: Boolean get() = synchronized(lock) { process?.isAlive == true }

    val pid: Long? get() = synchronized(lock) {
        val p = process ?: return@synchronized null
        try {
            val m = p::class.java.getMethod("pid")
            (m.invoke(p) as? Long) ?: (m.invoke(p) as? Int)?.toLong()
        } catch (_: Throwable) {
            Regex("pid=(\\d+)").find(p.toString())?.groupValues?.get(1)?.toLongOrNull()
        }
    }

    /**
     * Blocks until mysqld reports it's listening (or throws on failure).
     * MUST be called from a background thread.
     */
    @Throws(Exception::class)
    fun start() = synchronized(lock) {
        if (isRunning) {
            Log.i(TAG, "start() called but mysqld already running.")
            return@synchronized
        }

        require(layout.mysqldBin.exists() && layout.mysqldBin.canExecute()) {
            "MariaDB binary not found or not executable at " +
            "${layout.mysqldBin.absolutePath}. Place mariadbd (or mysqld) in " +
            "assets/server_env/bin/."
        }
        require(layout.mysqlConf.exists()) {
            "my.cnf missing at ${layout.mysqlConf.absolutePath}"
        }

        val prefs = Prefs(context)
        if (!prefs.mysqlInitialised || isDataDirEmpty()) {
            initialiseDataDir(prefs)
        }

        // Spawn the server.
        val cmd = listOf(
            layout.mysqldBin.absolutePath,
            "--defaults-file=${layout.mysqlConf.absolutePath}",
            "--basedir=${layout.prefixDir.absolutePath}",
            "--datadir=${layout.mysqlDataDir.absolutePath}",
            "--socket=${layout.mysqlSocket.absolutePath}"
        )
        Log.i(TAG, "Spawning MariaDB: ${cmd.joinToString(" ")}")
        val pb = ProcessBuilder(cmd)
            .directory(layout.prefixDir)
            .redirectErrorStream(true)
        buildEnv(pb.environment())

        val proc = pb.start()
        process = proc
        logJob = ioScope.launch { drainToLog("mariadbd", proc) }

        // Wait for the socket to appear as a readiness signal.
        val ready = waitForSocket(timeoutMs = 15_000L)
        if (!ready) {
            Log.w(TAG, "MariaDB did not open socket within timeout; check logs/mysql.err")
        }

        // On first run, seal the root account with the freshly-saved password.
        if (prefs.mysqlRootPassword.isNotEmpty() && !prefs.mysqlInitialised) {
            runCatching { setRootPassword(prefs.mysqlRootPassword) }
                .onSuccess { prefs.mysqlInitialised = true; Log.i(TAG, "Root password sealed.") }
                .onFailure { Log.w(TAG, "Could not seal root password automatically", it) }
        }
    }

    /** Idempotent shutdown. */
    fun stop() = synchronized(lock) {
        val p = process ?: return@synchronized
        if (!p.isAlive) { process = null; return@synchronized }

        Log.i(TAG, "Stopping mariadbd (pid=${pid ?: -1})")

        // Prefer graceful shutdown via mysqladmin if available.
        val prefs = Prefs(context)
        val pw = prefs.mysqlRootPassword
        val admin = File(layout.binDir, "mysqladmin")
        if (admin.exists() && admin.canExecute() && pw.isNotEmpty()) {
            runCatching {
                val pb = ProcessBuilder(
                    admin.absolutePath,
                    "--socket=${layout.mysqlSocket.absolutePath}",
                    "-u", "root",
                    "-p$pw",
                    "shutdown"
                ).redirectErrorStream(true)
                buildEnv(pb.environment())
                pb.start().waitFor()
            }.onFailure { Log.w(TAG, "mysqladmin shutdown failed, falling back to SIGTERM", it) }
        }

        // Fall back to SIGTERM then SIGKILL.
        if (p.isAlive) p.destroy()
        if (!p.waitForQuietly(GRACE_MS)) {
            Log.w(TAG, "mariadbd did not exit in ${GRACE_MS}ms, killing forcibly.")
            p.destroyForcibly()
            p.waitForQuietly(GRACE_MS)
        }
        process = null
        logJob?.cancel()
        logJob = null
        // Clean stale socket to avoid confusing subsequent starts.
        runCatching { if (layout.mysqlSocket.exists()) layout.mysqlSocket.delete() }
    }

    /* ------------------------------------------------------------- */
    /*  First-run initialisation                                     */
    /* ------------------------------------------------------------- */

    private fun isDataDirEmpty(): Boolean {
        val listing = layout.mysqlDataDir.list()?.filter { it != "lost+found" }.orEmpty()
        return listing.isEmpty() || !File(layout.mysqlDataDir, "mysql").exists()
    }

    /**
     * Initialise the data directory using whichever bootstrap tool is present:
     *   – `mysql_install_db` (traditional)
     *   – `mysqld --initialize-insecure` (newer MariaDB / MySQL)
     */
    private fun initialiseDataDir(prefs: Prefs) {
        Log.i(TAG, "Initialising MySQL data directory at ${layout.mysqlDataDir.absolutePath}")
        layout.mysqlDataDir.mkdirs()

        // Generate root password up front so both bootstrap paths converge.
        if (prefs.mysqlRootPassword.isEmpty()) {
            prefs.mysqlRootPassword = generateRandomPassword()
            Log.i(TAG, "Generated random MySQL root password.")
        }

        val cmd: List<String> = when {
            layout.mysqlInstallDb.exists() && layout.mysqlInstallDb.canExecute() -> listOf(
                layout.mysqlInstallDb.absolutePath,
                "--basedir=${layout.prefixDir.absolutePath}",
                "--datadir=${layout.mysqlDataDir.absolutePath}",
                "--auth-root-authentication-method=normal"
            )
            else -> listOf(
                layout.mysqldBin.absolutePath,
                "--initialize-insecure",
                "--basedir=${layout.prefixDir.absolutePath}",
                "--datadir=${layout.mysqlDataDir.absolutePath}"
            )
        }

        val pb = ProcessBuilder(cmd)
            .directory(layout.prefixDir)
            .redirectErrorStream(true)
        buildEnv(pb.environment())
        Log.i(TAG, "bootstrap: ${cmd.joinToString(" ")}")
        val proc = pb.start()

        // Drain output to logcat so failures are visible.
        BufferedReader(InputStreamReader(proc.inputStream)).use { r ->
            var line = r.readLine()
            while (line != null) {
                Log.i("$TAG/bootstrap", line!!)
                line = r.readLine()
            }
        }
        val exit = proc.waitFor()
        if (exit != 0) {
            error("MySQL bootstrap failed (exit $exit). See logs/mysql.err.")
        }
    }

    /**
     * After first successful boot, run:
     *   ALTER USER 'root'@'localhost' IDENTIFIED BY '<pw>'; FLUSH PRIVILEGES;
     * via the mysql client, piping the SQL into stdin. Works for both
     * MariaDB (bootstrapped without password) and MySQL 8.
     */
    private fun setRootPassword(password: String) {
        val client = layout.mysqlClientBin
        if (!client.exists() || !client.canExecute()) {
            Log.w(TAG, "mysql client not shipped — cannot seal root password.")
            return
        }
        val pb = ProcessBuilder(
            client.absolutePath,
            "--socket=${layout.mysqlSocket.absolutePath}",
            "-u", "root"
        ).redirectErrorStream(true)
        buildEnv(pb.environment())
        val proc = pb.start()
        proc.outputStream.bufferedWriter().use { w ->
            w.write("SET PASSWORD FOR 'root'@'localhost' = PASSWORD('$password');\n")
            w.write("FLUSH PRIVILEGES;\n")
        }
        proc.inputStream.bufferedReader().use { r ->
            r.forEachLine { Log.i("$TAG/seal", it) }
        }
        val exit = proc.waitFor()
        if (exit != 0) error("SET PASSWORD failed (exit $exit)")
    }

    /* ------------------------------------------------------------- */
    /*  Helpers                                                      */
    /* ------------------------------------------------------------- */

    private fun buildEnv(env: MutableMap<String, String>) {
        // ⚠️ lib/ FIRST — mariadbd/mysql link libssl/libcrypto/libpcre2/libc++_shared
        env["LD_LIBRARY_PATH"] = listOfNotNull(
            layout.libDir.absolutePath,
            File(layout.libDir, "plugin").absolutePath,
            layout.binDir.absolutePath,
            env["LD_LIBRARY_PATH"]
        ).joinToString(":")
        env["PATH"]   = layout.binDir.absolutePath + ":" + (env["PATH"] ?: "/system/bin")
        env["PREFIX"] = layout.prefixDir.absolutePath
        env["TMPDIR"] = layout.tmpDir.absolutePath
        env["HOME"]   = layout.prefixDir.absolutePath
        val caBundle = File(layout.prefixDir, "etc/tls/cert.pem")
        if (caBundle.exists()) env["SSL_CERT_FILE"] = caBundle.absolutePath
    }

    private fun drainToLog(tag: String, proc: Process) {
        val logFile = File(layout.logsDir, "$tag.log").apply {
            parentFile?.mkdirs(); writeText("")
        }
        try {
            BufferedReader(InputStreamReader(proc.inputStream)).use { r ->
                var line = r.readLine()
                while (line != null) {
                    Log.i("$TAG/$tag", line!!)
                    runCatching { logFile.appendText(line + "\n") }
                    line = r.readLine()
                }
            }
        } catch (_: Throwable) { /* stream closed on shutdown */ }
    }

    private fun waitForSocket(timeoutMs: Long): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (layout.mysqlSocket.exists()) return true
            try { Thread.sleep(200) } catch (_: InterruptedException) { return false }
        }
        return false
    }

    private fun Process.waitForQuietly(millis: Long): Boolean = try {
        val start = System.currentTimeMillis()
        while (isAlive && System.currentTimeMillis() - start < millis) Thread.sleep(50)
        !isAlive
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt(); false
    }

    private fun generateRandomPassword(): String {
        val alphabet = ("ABCDEFGHJKLMNPQRSTUVWXYZ" +
                        "abcdefghjkmnpqrstuvwxyz" +
                        "23456789").toCharArray()
        val rnd = java.security.SecureRandom()
        return CharArray(20) { alphabet[rnd.nextInt(alphabet.size)] }.concatToString()
    }

    companion object {
        private const val TAG = "MysqlManager"
        private const val GRACE_MS = 5_000L
    }
}
