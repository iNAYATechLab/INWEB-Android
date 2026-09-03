package com.inweb.app.runtime

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.inweb.app.R
import com.inweb.app.util.Prefs
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/*
 * ════════════════════════════════════════════════════════════════
 *  INWEB — Runtime Module Manager
 * ════════════════════════════════════════════════════════════════
 *  ভারী optional বাইনারি (node / caddy / cloudflared) আলাদা **runtime APK**
 *  হিসেবে GitHub Release থেকে নামে, ইনস্টল হয়, তারপর তার `nativeLibraryDir`
 *  থেকে exec করা হয় — Android 10+ এ exec শুধু ওই ডিরেক্টরিতেই legal
 *  (বিস্তারিত: [RuntimeModule] KDoc)।
 *
 *  মূল নীতি: **core-এ বান্ডলড থাকলে core-ই ব্যবহার হবে**, module শুধু fallback।
 *  তাই module ছাড়া/পুরনো বিল্ডে আচরণ বিন্দুমাত্র বদলায় না (zero regression)।
 */
object RuntimeModuleManager {

    private const val TAG = "RuntimeModule"
    private const val REPO = "iNAYATechLab/INWEB-Android"
    private const val API = "https://api.github.com/repos/$REPO/releases"

    /** ইনস্টলড module-র তথ্য */
    data class Installed(
        val module: RuntimeModule,
        val versionName: String,
        val versionCode: Long,
        val nativeLibDir: File
    ) {
        fun executable(fileName: String): File = File(nativeLibDir, fileName)
    }

    /* ---------------------------------------------------------------- */
    /*  Discovery                                                        */
    /* ---------------------------------------------------------------- */

    /** নোট: Android 11+ package visibility-র জন্য manifest-এ `<queries>` বাধ্যতামূলক */
    fun installed(context: Context, module: RuntimeModule): Installed? = try {
        val pkg = context.packageManager.getPackageInfo(module.packageName, 0)
        val libDir = pkg.applicationInfo?.nativeLibraryDir?.let(::File)
        if (libDir == null || !libDir.isDirectory) null
        else Installed(
            module = module,
            versionName = pkg.versionName ?: "?",
            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                pkg.longVersionCode
            else
                @Suppress("DEPRECATION") pkg.versionCode.toLong(),
            nativeLibDir = libDir
        )
    } catch (_: PackageManager.NameNotFoundException) {
        null
    } catch (t: Throwable) {
        Log.w(TAG, "installed(${module.id}) failed", t); null
    }

    /** ইনস্টলড সব module-এর native lib ডির — spawn() এ LD_LIBRARY_PATH-তে যোগ হয় */
    fun installedLibDirs(context: Context): List<File> =
        RuntimeModule.entries.mapNotNull { installed(context, it)?.nativeLibDir }

    /**
     * executable resolve: প্রথমে [coreDir]-এর [fileName] (মূল অ্যাপের native lib dir),
     * না পেলে সেটা যে module-এর তা খুঁজে সেই module-এর ডির।
     *
     * @return resolve হলে executable File, না হলে null → কলার UI-তে
     *         “এই ফিচারের জন্য module ডাউনলোড করুন” দেখাবে
     */
    fun resolveExecutable(context: Context, coreDir: File, fileName: String): File? {
        val inCore = File(coreDir, fileName)
        if (inCore.canExecute()) return inCore
        val module = RuntimeModule.owning(fileName) ?: return null
        return installed(context, module)?.executable(fileName)?.takeIf { it.canExecute() }
    }

    /* ---------------------------------------------------------------- */
    /*  Download + install                                               */
    /* ---------------------------------------------------------------- */

    /**
     * Background-এ release asset খুঁজে DownloadManager-এ পাঠায়;
     * [com.inweb.app.receiver.ApkDownloadReceiver] নামা মাত্রই installer খুলে দেয়
     * (সেটা generic — `Prefs.pendingApkPath` যে APK-nya দেখায় সেটাই ইনস্টল করবে)।
     */
    fun downloadAndInstall(activity: Activity, module: RuntimeModule) {
        Thread {
            val res = runCatching { findAssetUrl(module) }
            res.onFailure { Log.w(TAG, "asset lookup failed", it) }
            activity.runOnUiThread {
                val url = res.getOrNull()
                if (url == null) {
                    AlertDialog.Builder(activity)
                        .setTitle(module.displayName)
                        .setMessage(R.string.module_asset_missing)
                        .setPositiveButton(android.R.string.ok, null)
                        .setNegativeButton(R.string.module_open_releases) { _, _ ->
                            activity.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/$REPO/releases"))
                            )
                        }
                        .show()
                } else {
                    startDownload(activity, module, url)
                }
            }
        }.start()
    }

    /** GitHub releases (prerelease সমেত) থেকে module-এর asset download URL */
    private fun findAssetUrl(module: RuntimeModule): String? {
        val conn = (URL("$API?per_page=10").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "INWEB-app")
        }
        if (conn.responseCode != 200) {
            Log.w(TAG, "releases API → ${conn.responseCode}")
            return null
        }
        val releases = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
        val needle = module.assetPattern.substringBefore("{ver}")   // "INWEB-runtime-node-"
        for (i in 0 until releases.length()) {
            val assets = releases.getJSONObject(i).optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val a = assets.getJSONObject(j)
                val name = a.optString("name")
                if (name.startsWith(needle) && name.endsWith(".apk")) return a.getString("browser_download_url")
            }
        }
        return null
    }

    private fun startDownload(activity: Activity, module: RuntimeModule, url: String) {
        // "Install unknown apps" গেট (API 26+) — অ্যাপ আপডেটারের মতোই
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(activity)
                .setTitle(R.string.update_permission_title)
                .setMessage(R.string.module_permission_msg)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    activity.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${activity.packageName}")))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }

        val destDir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: activity.filesDir
        val apk = File(destDir, "inweb-module-${module.id}.apk")
        runCatching { apk.delete() }

        val req = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle("INWEB · ${module.displayName}")
            setDescription("≈${module.approxMb} MB runtime module")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationUri(Uri.fromFile(apk))
            setAllowedOverMetered(true)
        }
        (activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
        Prefs(activity).pendingApkPath = apk.absolutePath
        Toast.makeText(activity, R.string.module_download_started, Toast.LENGTH_LONG).show()
    }

    /* ---------------------------------------------------------------- */
    /*  Diagnostics (About → Run diagnostics এই লাইনগুলো ছাপে)            */
    /* ---------------------------------------------------------------- */

    fun statusReport(context: Context, coreLibDir: File): String = buildString {
        append("── runtime modules ──\n")
        for (m in RuntimeModule.entries) {
            val inst = installed(context, m)
            val inCore = m.executables.any { File(coreLibDir, it).canExecute() }
            append("  ${m.id.padEnd(8)} core=${if (inCore) "✅" else "—"}  ")
            append(
                if (inst != null) "module=${inst.versionName} (${inst.versionCode}) ${inst.nativeLibDir}"
                else "module=not installed"
            )
            append('\n')
        }
    }
}
