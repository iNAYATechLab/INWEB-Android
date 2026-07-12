package com.inweb.app.data

import android.app.ActivityManager
import android.content.Context
import android.net.TrafficStats
import android.os.Environment
import android.os.StatFs
import java.io.RandomAccessFile

/**
 * Snapshots device runtime metrics for the INWEB dashboard.
 *
 * Every method here is cheap (< 5 ms) so the UI can poll every second
 * without any performance impact. Values are computed on-demand — no
 * background threads, no cached state.
 */
object SystemStats {

    /* ---------------------------------------------------------------- */
    /*  CPU                                                              */
    /* ---------------------------------------------------------------- */

    /** Prior /proc/stat samples for the differential CPU calculation. */
    private var lastTotal: Long = 0
    private var lastIdle:  Long = 0

    /** Returns CPU usage as an int 0..100 (percentage). Sampled across all cores. */
    fun cpuPercent(): Int {
        return try {
            RandomAccessFile("/proc/stat", "r").use { raf ->
                val line = raf.readLine() ?: return -1
                // Format:  "cpu  user nice system idle iowait irq softirq steal ..."
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size < 5 || parts[0] != "cpu") return -1
                val fields = parts.drop(1).mapNotNull { it.toLongOrNull() }
                val idle = fields.getOrNull(3) ?: return -1
                val total = fields.sum()

                val diffTotal = total - lastTotal
                val diffIdle  = idle  - lastIdle
                lastTotal = total
                lastIdle  = idle

                if (diffTotal <= 0) return 0
                val pct = ((diffTotal - diffIdle) * 100 / diffTotal).toInt()
                pct.coerceIn(0, 100)
            }
        } catch (_: Throwable) { -1 }
    }

    /* ---------------------------------------------------------------- */
    /*  RAM                                                              */
    /* ---------------------------------------------------------------- */

    data class Ram(val usedBytes: Long, val totalBytes: Long) {
        val percent: Int get() = if (totalBytes == 0L) 0
                                 else ((usedBytes * 100) / totalBytes).toInt()
    }

    fun ram(context: Context): Ram {
        val mi = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
            .getMemoryInfo(mi)
        return Ram(usedBytes = mi.totalMem - mi.availMem, totalBytes = mi.totalMem)
    }

    /* ---------------------------------------------------------------- */
    /*  Storage                                                          */
    /* ---------------------------------------------------------------- */

    data class Storage(val freeBytes: Long, val totalBytes: Long) {
        val usedBytes: Long get() = totalBytes - freeBytes
        val percent:   Int  get() = if (totalBytes == 0L) 0
                                    else ((usedBytes * 100) / totalBytes).toInt()
    }

    fun storage(): Storage {
        val path = Environment.getDataDirectory()
        val stat = StatFs(path.absolutePath)
        val total = stat.blockCountLong * stat.blockSizeLong
        val free  = stat.availableBlocksLong * stat.blockSizeLong
        return Storage(freeBytes = free, totalBytes = total)
    }

    /* ---------------------------------------------------------------- */
    /*  Network I/O                                                      */
    /* ---------------------------------------------------------------- */

    private var lastRxBytes = 0L
    private var lastTxBytes = 0L
    private var lastNetTime = 0L

    data class NetIo(val rxBytesPerSec: Long, val txBytesPerSec: Long)

    /** Delta-based bytes-per-second since the previous call. */
    fun networkIo(): NetIo {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        val now = System.currentTimeMillis()
        if (rx == TrafficStats.UNSUPPORTED.toLong() ||
            tx == TrafficStats.UNSUPPORTED.toLong()) {
            return NetIo(0, 0)
        }
        val bootstrap = lastNetTime == 0L
        val dtSec = ((now - lastNetTime).coerceAtLeast(1L)) / 1000.0
        val rxRate = if (bootstrap) 0 else ((rx - lastRxBytes) / dtSec).toLong().coerceAtLeast(0)
        val txRate = if (bootstrap) 0 else ((tx - lastTxBytes) / dtSec).toLong().coerceAtLeast(0)
        lastRxBytes = rx; lastTxBytes = tx; lastNetTime = now
        return NetIo(rxRate, txRate)
    }

    /* ---------------------------------------------------------------- */
    /*  Formatters                                                      */
    /* ---------------------------------------------------------------- */

    fun humanBytes(b: Long): String {
        if (b <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = b.toDouble(); var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return if (v >= 100) "%.0f %s".format(v, units[i])
               else "%.1f %s".format(v, units[i])
    }

    fun humanBytesPerSec(b: Long): String {
        if (b <= 0) return "0 B/s"
        val units = arrayOf("B/s", "KB/s", "MB/s", "GB/s")
        var v = b.toDouble(); var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return "%.1f %s".format(v, units[i])
    }
}
