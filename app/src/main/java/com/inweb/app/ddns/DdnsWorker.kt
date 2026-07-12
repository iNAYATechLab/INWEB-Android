package com.inweb.app.ddns

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.inweb.app.util.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Periodic WorkManager job that keeps the DDNS record fresh.
 *
 * WorkManager guarantees delivery even after reboots / process death
 * (great for a server-hosting app) and respects Doze / battery saver.
 *
 * The user picks the interval in Settings (min 15 min because that's the
 * lowest WorkManager will schedule periodic work; anything smaller is
 * silently coerced up).
 */
class DdnsWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        val cfg = prefs.ddns
        if (!cfg.enabled || !cfg.isValid) {
            Log.i(TAG, "DDNS disabled or invalid — skipping.")
            return Result.success()
        }

        Log.i(TAG, "Pushing ${cfg.provider.id} → ${cfg.fullDomain}")
        val outcome = DdnsClient.push(cfg)

        val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        prefs.ddnsLastPushMs = System.currentTimeMillis()
        when (outcome) {
            is DdnsClient.Result.Success -> {
                prefs.ddnsLastResult = "✓ $stamp · ${outcome.message}"
                prefs.ddnsLastIp     = outcome.ip
                Log.i(TAG, "OK · ${outcome.message}")
                return Result.success()
            }
            is DdnsClient.Result.Failure -> {
                prefs.ddnsLastResult = "✗ $stamp · ${outcome.message}"
                Log.w(TAG, "FAIL · ${outcome.message}")
                // Retry with WorkManager's exponential backoff.
                return Result.retry()
            }
        }
    }

    companion object {
        private const val TAG = "DdnsWorker"
        const val UNIQUE_NAME = "inweb.ddns.periodic"

        /** Reschedule the periodic worker with the user's chosen interval. */
        fun schedule(context: Context) {
            val prefs = Prefs(context)
            val cfg = prefs.ddns

            val wm = WorkManager.getInstance(context)
            if (!cfg.enabled) {
                wm.cancelUniqueWork(UNIQUE_NAME)
                Log.i(TAG, "Unscheduled (DDNS disabled)")
                return
            }

            val minutes = cfg.intervalMinutes.coerceAtLeast(15).toLong()
            val request = PeriodicWorkRequestBuilder<DdnsWorker>(minutes, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            wm.enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
            Log.i(TAG, "Scheduled every $minutes min")
        }

        /** Fire a single immediate update (used from the "Update Now" button). */
        fun pushNow(context: Context) {
            val req = androidx.work.OneTimeWorkRequestBuilder<DdnsWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueue(req)
            Log.i(TAG, "One-shot DDNS push enqueued")
        }
    }
}
