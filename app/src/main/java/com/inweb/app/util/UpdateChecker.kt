package com.inweb.app.util

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.inweb.app.BuildConfig
import com.inweb.app.R
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * INWEB ইন-অ্যাপ আপডেট সিস্টেম
 * ============================
 * GitHub Releases-এ নতুন version (beta/rc/stable সবই) পাবলিশ হলেই —
 *   1. অ্যাপ চালুর সময় সাইলেন্টলি চেক হয় (১২ ঘণ্টা throttle)
 *   2. নতুন version পেলে dialog দেখায় changelog সহ
 *   3. এক ট্যাপে APK download → আরেক ট্যাপে system installer
 *   4. আলাদা করে GitHub-এ গিয়ে download করতে হয় না 🎉
 *
 * Same-signature (আমাদের release keystore) হওয়ায় seamless update হয়।
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    const val REPO = "iNAYATechLab/INWEB-Android"
    private const val API = "https://api.github.com/repos/$REPO/releases"

    data class Release(
        val tag: String,
        val version: String,          // normalized, e.g. "1.0.0-beta.3"
        val name: String,
        val notes: String,
        val apkUrl: String,
        val sizeBytes: Long,
        val prerelease: Boolean
    )

    sealed class Result {
        data class UpdateAvailable(val release: Release) : Result()
        object UpToDate : Result()
        data class Error(val message: String) : Result()
    }

    /* ------------------------------------------------------------ */
    /*  Version compare — supports "1.0.0-beta.2" style tags         */
    /* ------------------------------------------------------------ */

    private data class V(val major: Int, val minor: Int, val patch: Int,
                         val stage: Int, val stageNo: Int)
    // stage rank: beta=0 < rc=1 < stable=2

    fun parseVersion(raw: String): V? {
        val m = Regex("""v?(\d+)\.(\d+)\.(\d+)(?:-(beta|rc)\.(\d+))?""")
            .find(raw.trim()) ?: return null
        val (maj, min, pat) = Triple(
            m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        val stage = when (m.groupValues[4]) {
            "beta" -> 0; "rc" -> 1; else -> 2
        }
        val stageNo = m.groupValues[5].toIntOrNull() ?: 0
        return V(maj, min, pat, stage, stageNo)
    }

    /** @return positive if a > b */
    fun compare(a: V, b: V): Int {
        if (a.major != b.major) return a.major - b.major
        if (a.minor != b.minor) return a.minor - b.minor
        if (a.patch != b.patch) return a.patch - b.patch
        if (a.stage != b.stage) return a.stage - b.stage
        return a.stageNo - b.stageNo
    }

    fun isNewer(latestRaw: String, currentRaw: String): Boolean {
        val l = parseVersion(latestRaw) ?: return false
        val c = parseVersion(currentRaw) ?: return false
        return compare(l, c) > 0
    }

    /* ------------------------------------------------------------ */
    /*  GitHub check                                                 */
    /* ------------------------------------------------------------ */

    /** Blocking — call from background thread. */
    fun fetchLatest(): Result {
        return try {
            val conn = URL(API).openConnection() as HttpURLConnection
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "INWEB-Updater/${BuildConfig.VERSION_NAME}")
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            if (conn.responseCode != 200) return Result.Error("HTTP ${conn.responseCode}")

            val arr = JSONArray(conn.inputStream.bufferedReader().readText())
            // First *parseable* release with an .apk asset wins
            // (list is sorted newest-first; includes pre-releases).
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                val tag = r.optString("tag_name")
                if (parseVersion(tag) == null) continue   // skip "Old_Version" etc.
                val assets = r.optJSONArray("assets") ?: continue
                var apkUrl: String? = null
                var size = 0L
                for (j in 0 until assets.length()) {
                    val a = assets.getJSONObject(j)
                    val n = a.optString("name")
                    if (n.endsWith(".apk")) {
                        apkUrl = a.optString("browser_download_url")
                        size = a.optLong("size")
                        break
                    }
                }
                if (apkUrl != null) {
                    return Result.UpdateAvailable(Release(
                        tag = tag,
                        version = tag.removePrefix("v"),
                        name = r.optString("name", tag),
                        notes = r.optString("body", ""),
                        apkUrl = apkUrl,
                        sizeBytes = size,
                        prerelease = r.optBoolean("prerelease")
                    ))
                }
            }
            Result.UpToDate
        } catch (t: Throwable) {
            Log.w(TAG, "update check failed", t)
            Result.Error(t.message ?: "network error")
        }
    }

    /* ------------------------------------------------------------ */
    /*  Silent auto-check (called from MainActivity once per launch) */
    /* ------------------------------------------------------------ */

    fun autoCheck(activity: Activity) {
        val prefs = Prefs(activity)
        if (!prefs.autoUpdateCheck) return
        val now = System.currentTimeMillis()
        if (now - prefs.lastUpdateCheck < 12L * 60 * 60 * 1000) return
        prefs.lastUpdateCheck = now
        Thread {
            when (val res = fetchLatest()) {
                is Result.UpdateAvailable ->
                    if (isNewer(res.release.tag, BuildConfig.VERSION_NAME)) {
                        activity.runOnUiThread { showUpdateDialog(activity, res.release) }
                    }
                else -> Unit
            }
        }.start()
    }

    fun manualCheck(activity: Activity) {
        Toast.makeText(activity, activity.getString(R.string.update_checking), Toast.LENGTH_SHORT).show()
        Prefs(activity).lastUpdateCheck = System.currentTimeMillis()
        Thread {
            val res = fetchLatest()
            activity.runOnUiThread {
                when (res) {
                    is Result.UpdateAvailable ->
                        if (isNewer(res.release.tag, BuildConfig.VERSION_NAME))
                            showUpdateDialog(activity, res.release)
                        else
                            Toast.makeText(activity,
                                activity.getString(R.string.update_up_to_date, BuildConfig.VERSION_NAME),
                                Toast.LENGTH_LONG).show()
                    is Result.UpToDate ->
                        Toast.makeText(activity,
                            activity.getString(R.string.update_up_to_date, BuildConfig.VERSION_NAME),
                            Toast.LENGTH_LONG).show()
                    is Result.Error ->
                        Toast.makeText(activity,
                            activity.getString(R.string.update_failed, res.message),
                            Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /* ------------------------------------------------------------ */
    /*  Dialog + download                                            */
    /* ------------------------------------------------------------ */

    fun showUpdateDialog(activity: Activity, rel: Release) {
        val sizeMb = "%.1f".format(rel.sizeBytes / 1048576.0)
        val notesPreview = rel.notes.trim().lines()
            .filter { it.isNotBlank() }
            .take(12)
            .joinToString("\n")
            .take(900)

        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_available_title, rel.version))
            .setMessage(activity.getString(R.string.update_available_msg,
                BuildConfig.VERSION_NAME, sizeMb) + "\n\n" + notesPreview)
            .setPositiveButton(activity.getString(R.string.update_download)) { _, _ ->
                startDownload(activity, rel)
            }
            .setNegativeButton(activity.getString(R.string.update_later), null)
            .setNeutralButton(activity.getString(R.string.update_view_release)) { _, _ ->
                activity.startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/$REPO/releases/tag/${rel.tag}")))
            }
            .show()
    }

    private fun startDownload(activity: Activity, rel: Release) {
        // "Install unknown apps" permission gate (API 26+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(activity)
                .setTitle(R.string.update_permission_title)
                .setMessage(R.string.update_permission_msg)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    activity.startActivity(Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}")))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }

        val destDir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: activity.filesDir
        val apkFile = File(destDir, "inweb-update-${rel.version}.apk")
        runCatching { apkFile.delete() }

        val req = DownloadManager.Request(Uri.parse(rel.apkUrl)).apply {
            setTitle("INWEB ${rel.version}")
            setDescription(activity.getString(R.string.update_downloading_desc))
            setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationUri(Uri.fromFile(apkFile))
            setAllowedOverMetered(true)
        }
        val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = dm.enqueue(req)

        // Receive-side hand-off for [ApkDownloadReceiver]
        Prefs(activity).pendingApkPath = apkFile.absolutePath
        Log.i(TAG, "Download enqueued id=$id → ${apkFile.absolutePath}")
        Toast.makeText(activity,
            activity.getString(R.string.update_download_started), Toast.LENGTH_LONG).show()
    }
}
