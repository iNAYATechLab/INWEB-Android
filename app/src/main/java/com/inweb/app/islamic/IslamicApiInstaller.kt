package com.inweb.app.islamic

import android.content.Context
import android.util.Log
import com.inweb.app.Constants
import java.io.File
import java.io.FileOutputStream

/**
 * Copies the pre-shipped Islamic API PHP files from
 * `assets/islamic_apis/` into the user's web root under `www/api/`.
 *
 * Once installed, all endpoints are reachable at
 *   http://localhost:8080/api/prayer-times.php
 *   http://localhost:8080/api/qibla.php
 *   http://localhost:8080/api/hijri-date.php
 *   http://localhost:8080/api/zakat.php
 *   http://localhost:8080/api/index.html         ← landing page
 *
 * Idempotent — safe to call every time.
 */
object IslamicApiInstaller {

    private const val TAG = "IslamicApiInstaller"
    private const val ASSET_DIR = "islamic_apis"
    private const val SUBDIR    = "api"

    /** True if the API bundle has been extracted to the web root. */
    fun isInstalled(context: Context): Boolean =
        apiDir(context).let { it.exists() && File(it, "prayer-times.php").exists() }

    fun apiDir(context: Context): File {
        val extRoot = context.getExternalFilesDir(null) ?: context.filesDir
        return File(File(extRoot, Constants.WWW_DIR), SUBDIR)
    }

    /**
     * (Re)install the API bundle. Overwrites any existing files.
     */
    fun install(context: Context) {
        val dir = apiDir(context)
        dir.mkdirs()
        val am = context.assets
        val files = am.list(ASSET_DIR).orEmpty()
        for (name in files) {
            am.open("$ASSET_DIR/$name").use { input ->
                FileOutputStream(File(dir, name)).use { output ->
                    input.copyTo(output)
                }
            }
        }
        Log.i(TAG, "Installed ${files.size} Islamic API files → ${dir.absolutePath}")
    }

    fun uninstall(context: Context) {
        val dir = apiDir(context)
        if (dir.exists()) dir.deleteRecursively()
    }
}
