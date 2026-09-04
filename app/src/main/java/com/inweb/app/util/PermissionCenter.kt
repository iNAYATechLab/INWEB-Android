package com.inweb.app.util

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.inweb.app.R

/*
 * ════════════════════════════════════════════════════════════════
 *  INWEB — Permission Center
 * ════════════════════════════════════════════════════════════════
 *  অ্যাপের সব পারমিশনের **বাস্তব স্ট্যাটাস** এক জায়গায় + যেগুলি রানটাইমে
 *  চাই সেগুলোর গ্রান্ট ফ্লো। Settings → "পারমিশন ও ব্যাটারি" থেকে খোলে।
 *
 *  ⚠️ গুরুত্বপূর্ণ (কেন এটা দরকার হয়েছিল):
 *   • POST_NOTIFICATIONS → Android 13+ এ রানটাইম পারমিশন, না নিলে foreground
 *     service-এর "server running" নোটিফিকেশন দেখায় না (onboarding-এ চাওয়া হয়,
 *     কিন্তু ইউজার ডিনাই করলে আর জিজ্ঞেস করা হতো না)।
 *   • ব্যাটারি অপ্টিমাইজেশন (Doze) → এই অ্যাপের জন্য সবচেয়ে বড় নীরব কিলার:
 *     স্ক্রিন অফ করলে nginx/php-fpm/mariadbd সাসপেন্ড/কিল হয়ে যেতে পারে।
 *     Android 10+ এ এটার জন্য কোনো রানটাইম "পপআপ পারমিশন" নেই — সিস্টেম
 *     ডায়ালগ (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) দিয়ে এক্সেম্পশন
 *     নিতে হয়, আর সেটার জন্য manifest-এ REQUEST_IGNORE_BATTERY_OPTIMIZATIONS লাগে।
 *   • REQUEST_INSTALL_PACKAGES → OTA আপডেট + runtime module ইনস্টলের জন্য
 *     "unknown sources" টগল দরকার (সব রোম-এ আলাদা স্ক্রিন)।
 *   • INTERNET / FOREGROUND_SERVICE / WAKE_LOCK → normal permission, ইনস্টলেই
 *     গ্রান্টেড, কখনো জিজ্ঞেস করতে হয় না (অনেকে ভাবেন "পারমিশন নাই" — আসলে
 *     এগুলো অটো)।
 *   • স্টোরেজ পারমিশন **লাগে না**: site folder SAF (`OpenDocumentTree`) দিয়ে
 *     সিলেক্ট হয়, আর server runtime অ্যাপ-স্কোপড ডিরে থাকে।
 */
object PermissionCenter {

    private const val REQ_NOTIFICATION = 41

    /** একটা পারমিশন/সেটিং-এর স্ন্যাপশট */
    data class Item(
        val key: Key,
        val granted: Boolean,
        /** গ্রান্ট ফ্লো আছে কিনা (না হলে শুধু স্ট্যাটাস দেখানো হয়) */
        val actionable: Boolean
    )

    enum class Key { NOTIFICATIONS, BATTERY, INSTALL_UNKNOWN, NORMAL }

    fun audit(ctx: Context): List<Item> = listOf(
        Item(
            key = Key.NOTIFICATIONS,
            granted = notificationsGranted(ctx),
            actionable = true
        ),
        Item(
            key = Key.BATTERY,
            granted = ignoringBatteryOptimizations(ctx),
            actionable = true
        ),
        Item(
            key = Key.INSTALL_UNKNOWN,
            granted = canInstallPackages(ctx),
            actionable = true
        ),
        Item(
            key = Key.NORMAL,
            granted = true,          // install-time permissions — always granted
            actionable = false
        )
    )

    /** যা যা নেই সেগুলোর সংখ্যা (Settings-এ ব্যাজ দেখানোর জন্য) */
    fun missingCount(ctx: Context): Int =
        audit(ctx).count { it.actionable && !it.granted }

    /* ─────────────────────────── status helpers ─────────────────────────── */

    fun notificationsGranted(ctx: Context): Boolean =
        NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    private fun needsNotifPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun ignoringBatteryOptimizations(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return runCatching { pm.isIgnoringBatteryOptimizations(ctx.packageName) }.getOrDefault(false)
    }

    fun canInstallPackages(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return runCatching { ctx.packageManager.canRequestPackageInstalls() }.getOrDefault(true)
    }

    /* ───────────────────────────── UI ───────────────────────────── */

    /** Settings → পারমিশন রো → এই ডায়ালগ */
    fun show(activity: android.app.Activity) {
        val items = audit(activity)
        val labels = items.map { row ->
            val title = activity.getString(labelOf(row.key))
            val mark = when {
                !row.actionable -> "  " + activity.getString(R.string.perm_auto)
                row.granted -> "  ✅"
                else -> "  ⚠️ " + activity.getString(R.string.perm_missing)
            }
            title + mark
        }.toTypedArray()

        AlertDialog.Builder(activity)
            .setTitle(R.string.perm_title)
            .setMessage(R.string.perm_summary)
            .setItems(labels) { _, which -> onRowChosen(activity, items[which]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun labelOf(k: Key): Int = when (k) {
        Key.NOTIFICATIONS   -> R.string.perm_notifications
        Key.BATTERY         -> R.string.perm_battery
        Key.INSTALL_UNKNOWN -> R.string.perm_install
        Key.NORMAL          -> R.string.perm_normal
    }

    private fun descOf(k: Key): Int = when (k) {
        Key.NOTIFICATIONS   -> R.string.perm_notifications_desc
        Key.BATTERY         -> R.string.perm_battery_desc
        Key.INSTALL_UNKNOWN -> R.string.perm_install_desc
        Key.NORMAL          -> R.string.perm_normal_desc
    }

    private fun onRowChosen(activity: android.app.Activity, item: Item) {
        // প্রথমে ব্যাখ্যা, তারপর অ্যাকশন
        AlertDialog.Builder(activity)
            .setTitle(labelOf(item.key))
            .setMessage(activity.getString(descOf(item.key)) + "\n\n" +
                (if (!item.actionable) activity.getString(R.string.perm_auto)
                 else if (item.granted) "✅ " + activity.getString(R.string.perm_granted)
                 else "⚠️ " + activity.getString(R.string.perm_missing)))
            .apply {
                if (item.actionable && !item.granted) {
                    setPositiveButton(R.string.perm_grant) { _, _ -> grant(activity, item.key) }
                }
                if (item.actionable) {
                    setNeutralButton(R.string.perm_settings) { _, _ -> openAppSettings(activity) }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun grant(activity: android.app.Activity, key: Key) = when (key) {
        Key.NOTIFICATIONS ->
            if (needsNotifPermission()) {
                androidx.core.app.ActivityCompat.requestPermissions(
                    activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATION
                )
            } else openNotificationSettings(activity)

        Key.BATTERY         -> requestBatteryExemption(activity)
        Key.INSTALL_UNKNOWN -> requestInstallPermission(activity)
        Key.NORMAL          -> Unit
    }

    /* ───────────────────────── grant flows (OEM-safe) ───────────────────────── */

    private fun openNotificationSettings(activity: android.app.Activity) {
        val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
        startOrToast(activity, i)
    }

    private fun requestInstallPermission(activity: android.app.Activity) {
        val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:" + activity.packageName))
        startOrToast(activity, i)
    }

    /**
     * Doze exclusion চাই — বেশিরভাগ রোমে সিস্টেম ডায়ালগ আসে, কিছু রোমে
     * (Xiaomi/Huawei-তে রিপোর্ট করা) সরাসরি সেটিংস স্ক্রিনে ফেলে দেয় → তাই ফলব্যাক।
     */
    private fun requestBatteryExemption(activity: android.app.Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val direct = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + activity.packageName)
            )
            try {
                activity.startActivity(direct)
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
                // কিছু রোম ACTION_REQUEST_* ব্লক করে → ফলব্যাক স্ক্রিনে যাক
            }
        }
        startOrToast(activity, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    private fun openAppSettings(activity: android.app.Activity) {
        startOrToast(
            activity,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + activity.packageName))
        )
    }

    private fun startOrToast(activity: android.app.Activity, i: Intent) {
        try {
            activity.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            android.widget.Toast.makeText(activity, R.string.perm_settings_unavailable,
                android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /* ───────────────── Diagnostics (About → Run diagnostics) ───────────────── */

    fun statusReport(ctx: Context): String = buildString {
        append("── permissions ──\n")
        for (it in audit(ctx)) {
            val s = if (!it.actionable) "auto" else if (it.granted) "granted" else "MISSING"
            append("  ${it.key.name.lowercase().padEnd(16)} $s\n")
        }
        // API < 33 এ POST_NOTIFICATIONS বলে কিছু নেই (নোটিফিকেশন ইনস্টলেই চলবে)
        if (needsNotifPermission()) {
            val ok = ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            append("  POST_NOTIFICATIONS  : ").append(if (ok) "granted" else "denied").append('\n')
        }
    }
}
