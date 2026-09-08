package com.inweb.app.github

import android.util.Base64
import android.util.Log
import com.inweb.app.AssetInstaller
import com.inweb.app.util.Prefs
import java.io.File
import java.security.SecureRandom

/*
 * ════════════════════════════════════════════════════════════════
 *  INWEB — বান্ডল-ফ্রি "সাইট চালু" বুটস্ট্র্যাপ
 * ════════════════════════════════════════════════════════════════
 *  GitHub থেকে WordPress নামানোর পরও সাইট উঠত না, কারণ:
 *    1) repo-তে `wp-config.php` থাকে না (সেটআপ উইজার্ড চালাতে হলে ফাইলটা দরকার,
 *       আর অ্যাডমিনের সময় বারবার মাউন্ট/পারমিশন ইস্যু হয়)
 *    2) ডাটাবেজ কেউ তৈরি করে না
 *  এখানে দুটোই করি — কোনো extra binary লাগে না, cause:
 *    • Termux php-তে **mysqli + mysqlnd বিল্ট-ইন** (মাপা: php deb-এ কোনো
 *      `*.so` extension নেই-ই, তাই `extension_dir`-ও লাগে না)
 *    • mariadbd-র socket আমাদের হাতে (layout.mysqlSocket), root পাসওয়ার্ড Prefs-এ
 *  PHP ইঞ্জিন না চললে (beta.12-এর SIGSEGV) এটাও কাজ করবে না — তাই ফলাফল
 *  স্ট্রিং রিটার্ন করি, ইউজারকে কী করতে হবে বলে দেওয়ার জন্য।
 */
object SiteBootstrap {

    private const val TAG = "SiteBootstrap"

    /** @return ইউজারকে দেখানোর সংক্ষিপ্ত বার্তা (null = কিছু করা লাগেনি) */
    fun prepare(root: File, layout: AssetInstaller.Layout, prefs: Prefs): String? {
        val isWordPress = File(root, "wp-settings.php").exists() ||
                          (File(root, "wp-login.php").exists() && File(root, "wp-includes").isDirectory)
        if (!isWordPress) return null

        val db = "wordpress_" + GitHubClient.safeName(root.name, "site").replace('-', '_')
        val sb = StringBuilder()

        // ১) ডাটাবেজ তৈরি (server চলছে না হলে ফেল করবে — সেটাও বলি)
        val dbMsg = runCatching { createDatabase(layout, prefs, db) }
            .fold({ "✅ DB `$db` তৈরি/ready" }, { e ->
                Log.w(TAG, "db create failed", e)
                "⚠️ DB তৈরি করা যায়নি (${e.message?.take(60)}) — Services → MySQL চালু দিয়ে আবার চেষ্টা করো"
            })
        sb.appendLine(dbMsg)

        // ২) wp-config.php
        val cfg = File(root, "wp-config.php")
        if (cfg.exists()) {
            sb.appendLine("ℹ️ wp-config.php আগে থেকেই আছে — ছুঁইনি")
        } else {
            runCatching {
                cfg.writeText(wpConfig(db, prefs.mysqlRootPassword, layout.mysqlSocket.absolutePath))
                sb.appendLine("✅ wp-config.php তৈরি (DB: $db · socket · salts র‍্যান্ডম)")
            }.onFailure { sb.appendLine("⚠️ wp-config.php লেখা যায়নি: ${it.message}") }
        }
        return sb.toString().trim()
    }

    /* ───────────────────────── mysql client ───────────────────────── */

    private fun createDatabase(layout: AssetInstaller.Layout, prefs: Prefs, db: String) {
        val client = layout.mysqlClientBin
        if (!client.exists() || !client.canExecute())
            error("mysql client নেই (${client.name})")
        val safe = db.filter { it.isLetterOrDigit() || it == '_' }
        val pb = ProcessBuilder(
            client.absolutePath,
            "--socket=${layout.mysqlSocket.absolutePath}",
            "-u", "root",
            "-e", "CREATE DATABASE IF NOT EXISTS `$safe` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
        ).redirectErrorStream(true)
        pb.environment().apply {
            put("LD_LIBRARY_PATH", layout.libDir.absolutePath)
            put("PATH", layout.binDir.absolutePath + ":/system/bin")
            put("HOME", layout.prefixDir.absolutePath)
            put("TMPDIR", layout.tmpDir.absolutePath)
            // পাসওয়ার্ড কমান্ড লাইনে দিলে /proc-এ দেখা যায় — env-এ দিই
            if (prefs.mysqlRootPassword.isNotBlank()) put("MYSQL_PWD", prefs.mysqlRootPassword)
        }
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) { p.destroyForcibly(); error("টাইমআউট") }
        if (p.exitValue() != 0) error(out.lines().firstOrNull { it.isNotBlank() } ?: "exit ${p.exitValue()}")
    }

    /* ───────────────────────── wp-config.php ───────────────────────── */

    private fun wpConfig(db: String, password: String, socket: String): String {
        val salts = (1..8).map { randomKey() }
        val (t1, t2, t3, t4, t5, t6, a1, a2) = salts
        return """
<?php
/** INWEB-এর GitHub ইমপোর্ট স্বয়ংক্রিয়ভাবে তৈরি করা wp-config.php */
define('DB_NAME', '$db');
define('DB_USER', 'root');
define('DB_PASSWORD', '${password.replace("'", "\\'")}');
// mysqli সকেট সরাসরি: TCP DNS/resolve ছাড়াই মোবাইলে নিরাপদ
define('DB_HOST', 'localhost:$socket');
define('DB_CHARSET', 'utf8mb4');
define('DB_COLLATE', '');

define('AUTH_KEY',         '$t1');
define('SECURE_AUTH_KEY',  '$t2');
define('LOGGED_IN_KEY',    '$t3');
define('NONCE_KEY',        '$t4');
define('AUTH_SALT',        '$t5');
define('SECURE_AUTH_SALT', '$t6');
define('LOGGED_IN_SALT',   '${randomKey()}');
define('NONCE_SALT',       '${randomKey()}');

${'$'}table_prefix = 'wp_';

define('WP_DEBUG', false);
define('FS_METHOD', 'direct');           // FTP/SSH extension নেই — ডিরেক্ট ফাইল অ্যাক্সেস
define('WP_POST_REVISIONS', 10);
define('EMPTY_TRASH_DAYS', 7);

if (!defined('ABSPATH')) define('ABSPATH', __DIR__ . '/');
require_once ABSPATH . 'wp-settings.php';
        """.trimStart()
    }

    private fun randomKey(): String {
        val b = ByteArray(24)
        SecureRandom().nextBytes(b)
        return Base64.encodeToString(b, Base64.NO_WRAP or Base64.NO_PADDING).take(48)
    }
}
