package com.inweb.app.receiver

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.inweb.app.util.Prefs
import java.io.File

/**
 * DownloadManager-এ APK নামা শেষ হলে সরাসরি package installer-এ পাঠায়।
 * User শুধু "Install" চাপে — GitHub-এ যাওয়া লাগে না। 🎉
 */
class ApkDownloadReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return

        val path = Prefs(context).pendingApkPath
        if (path.isEmpty()) return

        val apk = File(path)
        if (!apk.exists() || apk.length() < 1024 * 1024) {
            Log.w(TAG, "APK missing/too small at $path — ignoring")
            return
        }
        Prefs(context).pendingApkPath = ""

        val uri: Uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        } catch (t: Throwable) {
            Log.e(TAG, "FileProvider failed", t); return
        }

        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        Log.i(TAG, "Launching installer for ${apk.name}")
    }

    private companion object { const val TAG = "ApkDownloadReceiver" }
}
