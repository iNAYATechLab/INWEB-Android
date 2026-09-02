package com.inweb.app.util

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.inweb.app.BuildConfig
import java.io.File
import java.util.Date

/**
 * 🩺 INWEB Crash Logger
 * =====================
 * কোনো crash হলে পুরো stack trace `files/crash/last_crash.txt`-এ লেখা হয়।
 * পরের launch-এ MainActivity একটি dialog দেখায় + copy করতে দেয় —
 * ফলে আমরা অনুমান না করে আসল কারণটাই দেখি।
 */
object CrashLogger {

    private const val DIR = "crash"
    private const val FILE = "last_crash.txt"

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val dir = File(app.filesDir, DIR).apply { mkdirs() }
                File(dir, FILE).writeText(buildString {
                    appendLine("═══ INWEB CRASH REPORT ═══")
                    appendLine("Time    : ${Date()}")
                    appendLine("Version : ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    appendLine("Package : ${BuildConfig.APPLICATION_ID}")
                    appendLine("Device  : ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("Android : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    appendLine("ABI     : ${Build.SUPPORTED_ABIS.joinToString()}")
                    appendLine("Thread  : ${thread.name}")
                    appendLine()
                    appendLine(Log.getStackTraceString(throwable))
                })
            }
            // Hand over to the default handler so the app still dies properly.
            if (previous != null) previous.uncaughtException(thread, throwable)
            else android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    /** Reads & DELETES the pending crash report (null when none). */
    fun consume(context: Context): String? {
        val f = File(File(context.filesDir, DIR), FILE)
        if (!f.exists()) return null
        val txt = runCatching { f.readText() }.getOrNull()
        runCatching { f.delete() }
        return txt?.takeIf { it.isNotBlank() }
    }

    /** MainActivity shows this once, right after launch. */
    fun showPendingIfAny(activity: Activity) {
        val report = consume(activity) ?: return
        val view = android.widget.TextView(activity).apply {
            text = report
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(32, 24, 32, 8)
            setTextIsSelectable(true)
        }
        AlertDialog.Builder(activity)
            .setTitle("😞 আগের crash-এর রিপোর্ট")
            .setView(android.widget.ScrollView(activity).apply { addView(view) })
            .setPositiveButton("📋 Copy") { _, _ ->
                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("INWEB crash report", report))
            }
            .setNegativeButton("ওকে", null)
            .show()
    }
}
