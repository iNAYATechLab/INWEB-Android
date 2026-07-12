package com.inweb.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.inweb.app.ServerService
import com.inweb.app.util.Prefs

/**
 * Auto-starts INWEB after the device finishes booting, but only when the
 * user has opted in via **Settings → Auto-start on boot**.
 *
 * Notes on modern Android:
 *   – On Android 8+, background service starts from BOOT_COMPLETED are
 *     restricted, but foreground services promoted via startForegroundService()
 *     are allowed.
 *   – Some OEMs (Xiaomi, Oppo, Huawei) require the user to grant an
 *     "auto-start" permission separately — we can't work around that from code.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) return

        val prefs = Prefs(context)
        if (!prefs.autoStartOnBoot) {
            Log.i(TAG, "Boot completed but auto-start is off — skipping.")
            return
        }

        Log.i(TAG, "Boot completed → auto-starting INWEB services.")
        try {
            ServerService.start(context)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to auto-start on boot", t)
        }
    }

    companion object { private const val TAG = "BootReceiver" }
}
