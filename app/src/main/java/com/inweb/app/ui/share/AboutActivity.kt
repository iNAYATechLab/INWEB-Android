package com.inweb.app.ui.share

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.inweb.app.runtime.RuntimeModuleManager
import com.inweb.app.AssetInstaller
import com.inweb.app.BuildConfig
import com.inweb.app.R
import com.inweb.app.ui.common.BottomNavHelper
import com.inweb.app.ui.common.PageScaffold
import com.inweb.app.util.UpdateChecker
import java.io.File

/**
 * About INWEB — অ্যাপের পরিচিতি + version info + update check + 🩺 Diagnostics।
 *
 * Diagnostics অংশটা প্রতিটা server binary চালিয়ে আসল output দেখায় —
 * "server চালু হচ্ছে না"-ধরনের সমস্যায় এখানের copy করা text-ই আমাদের কাজে লাগে।
 */
class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        PageScaffold.setup(this, getString(R.string.about_title)) {
            onBackPressedDispatcher.onBackPressed()
        }
        BottomNavHelper.attach(this, BottomNavHelper.Tab.MORE)

        // সব wiring runCatching-এ — কোনো একটা fail হলেও পেজ খোলা থাকবে,
        // আর CrashLogger আসল কারণটা সংরক্ষণ করবে।
        runCatching {
            findViewById<TextView>(R.id.aboutVersion)?.text =
                getString(R.string.about_version_line, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
            findViewById<TextView>(R.id.aboutPackage)?.text = BuildConfig.APPLICATION_ID
        }.onFailure { reportWiringFailure("version card", it) }

        runCatching {
            findViewById<View>(R.id.aboutUpdateBtn)?.setOnClickListener {
                UpdateChecker.manualCheck(this)
            }
        }.onFailure { reportWiringFailure("update button", it) }

        runCatching {
            findViewById<View>(R.id.aboutGithubRow)?.setOnClickListener {
                openUrl("https://github.com/${UpdateChecker.REPO}")
            }
            findViewById<View>(R.id.aboutIssuesRow)?.setOnClickListener {
                openUrl("https://github.com/${UpdateChecker.REPO}/issues")
            }
            findViewById<View>(R.id.aboutPrivacyRow)?.setOnClickListener {
                openUrl("https://github.com/${UpdateChecker.REPO}#readme")
            }
        }.onFailure { reportWiringFailure("links", it) }

        // ── Diagnostics ──────────────────────────────────────
        runCatching {
            val diagOut = findViewById<TextView>(R.id.diagOutput)
            findViewById<View>(R.id.diagRunBtn)?.setOnClickListener {
                diagOut?.text = getString(R.string.diag_running)
                Thread {
                    val report = runDiagnostics()
                    runOnUiThread { diagOut?.text = report }
                }.start()
            }
            findViewById<View>(R.id.diagCopyBtn)?.setOnClickListener {
                val txt = diagOut?.text?.toString().orEmpty()
                if (txt.isBlank()) return@setOnClickListener
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("INWEB diagnostics", txt))
                Toast.makeText(this, getString(R.string.diag_copied), Toast.LENGTH_SHORT).show()
            }
        }.onFailure { reportWiringFailure("diagnostics", it) }
    }

    private fun reportWiringFailure(section: String, t: Throwable) {
        Log.e(TAG, "About wiring failed in '$section'", t)
        Toast.makeText(this, "⚠️ $section: ${t.message}", Toast.LENGTH_LONG).show()
    }

    private companion object { const val TAG = "AboutActivity" }

    private fun openUrl(u: String) =
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) }

    /* ------------------------------------------------------------ */
    /*  Diagnostics — run every binary with --version/-v             */
    /* ------------------------------------------------------------ */

    private fun runDiagnostics(): String {
        val sb = StringBuilder()
        sb.appendLine("═══ INWEB DIAGNOSTICS ═══")
        sb.appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        sb.appendLine("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        sb.appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        sb.appendLine("ABI: ${android.os.Build.SUPPORTED_ABIS.firstOrNull()}")
        sb.appendLine()

        val layout = runCatching { AssetInstaller.install(this) }.getOrElse {
            sb.appendLine("❌ AssetInstaller failed: ${it.message}")
            return sb.toString()
        }

        fun binaryTest(name: String, args: List<String>) {
            // binaries live in the native lib dir (exec-allowed) as libexec_*.so;
            // না পেলে ঐ binary-র runtime module (আলাদা ইনস্টলড APK) থেকে খোঁজা হয়
            val fileName = "libexec_$name.so".replace("-", "_")
            val bin = RuntimeModuleManager.resolveExecutable(this, layout.libDir, fileName)
                      ?: File(layout.libDir, fileName)
            sb.appendLine("── $name ${args.joinToString(" ")} ──")
            when {
                !bin.exists() -> sb.appendLine("   ❌ MISSING: ${bin.absolutePath}")
                else -> try {
                    val pb = ProcessBuilder(listOf(bin.absolutePath) + args)
                        .directory(layout.prefixDir)
                        .redirectErrorStream(true)
                    val env = pb.environment()
                    env["LD_LIBRARY_PATH"] = listOfNotNull(
                        layout.libDir.absolutePath,
                        layout.binDir.absolutePath
                    ).joinToString(":")
                    env["PATH"]   = layout.binDir.absolutePath + ":/system/bin"
                    env["PREFIX"] = layout.prefixDir.absolutePath
                    env["HOME"]   = layout.prefixDir.absolutePath
                    env["TMPDIR"] = layout.tmpDir.absolutePath
                    val p = pb.start()
                    val out = p.inputStream.bufferedReader().readText().trim()
                    val done = p.waitFor(12, java.util.concurrent.TimeUnit.SECONDS)
                    if (!done) { p.destroyForcibly(); sb.appendLine("   ⏱ TIMEOUT (12s)") }
                    sb.appendLine(if (out.isBlank()) "   (no output, exit=${if(done) p.exitValue() else "?"})"
                                  else out.lines().take(6).joinToString("\n   "))
                } catch (t: Throwable) {
                    sb.appendLine("   ❌ ${t.javaClass.simpleName}: ${t.message}")
                }
            }
            sb.appendLine()
        }

        binaryTest("nginx",     listOf("-v"))
        binaryTest("httpd",     listOf("-v"))
        binaryTest("caddy",     listOf("version"))
        binaryTest("php",       listOf("-v"))
        binaryTest("php-fpm",   listOf("-v"))
        binaryTest("mariadbd",  listOf("--version"))
        binaryTest("mysql",     listOf("--version"))
        binaryTest("node",      listOf("-v"))

        // Runtime modules (optional downloads) — core ✅ / module ইনস্টলড কিনা
        sb.append(RuntimeModuleManager.statusReport(this, layout.libDir))
        sb.appendLine()

        // 🔐 Permission status (notification / Doze exemption / unknown sources)
        sb.append(com.inweb.app.util.PermissionCenter.statusReport(this))
        sb.appendLine()

        // Filesystem sanity
        sb.appendLine("── Filesystem ──")
        sb.appendLine("nativeLibDir: ${layout.libDir.absolutePath}")
        sb.appendLine("  .so files : ${layout.libDir.listFiles()?.size ?: 0}")
        sb.appendLine("scripts dir : ${layout.binDir.absolutePath} (${layout.binDir.listFiles()?.size ?: 0} files)")

        return sb.toString()
    }
}
