package com.inweb.app.dashboard

import java.util.Calendar
import java.util.Date
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * Same astronomical prayer-time algorithm as our PHP API (`prayer-times.php`),
 * ported to Kotlin so the dashboard can show the next prayer + a live
 * countdown *without* needing the local server to be running.
 *
 * Location: caller supplies lat/lng; if unknown, we fall back to Dhaka.
 */
object PrayerTimeCalculator {

    // Fajr / Isha angles for the Karachi (University of Islamic Sciences) method.
    private const val FAJR_ANGLE = 18.0
    private const val ISHA_ANGLE = 18.0

    enum class Prayer(val labelEn: String, val labelBn: String) {
        FAJR   ("Fajr",    "ফজর"),
        SUNRISE("Sunrise", "সূর্যোদয়"),
        DHUHR  ("Dhuhr",   "যোহর"),
        ASR    ("Asr",     "আসর"),
        MAGHRIB("Maghrib", "মাগরিব"),
        ISHA   ("Isha",    "ইশা"),
    }

    data class Timings(val timings: Map<Prayer, Long>) {
        /** Returns the next prayer after [now] (wraps to next-day Fajr if all passed). */
        fun nextAfter(now: Long): Pair<Prayer, Long> {
            val upcoming = timings.entries.filter { it.value > now && it.key != Prayer.SUNRISE }
                .minByOrNull { it.value }
            if (upcoming != null) return upcoming.key to upcoming.value
            // All prayers past — next is tomorrow's Fajr (add 24h to today's).
            return Prayer.FAJR to (timings[Prayer.FAJR]!! + 24L * 3600 * 1000)
        }
    }

    /**
     * Compute today's six timings (in device local time, ms since epoch).
     * Falls back to Dhaka coordinates if the caller passes null.
     */
    fun computeToday(latOrNull: Double?, lngOrNull: Double?): Timings {
        val lat = latOrNull ?: 23.8103   // Dhaka
        val lng = lngOrNull ?: 90.4125

        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 12)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val noonMillis = cal.timeInMillis

        val jd = julianDay(noonMillis)
        val (decl, eqt) = sunPosition(jd)

        val tzHours = TimeZone.getDefault().getOffset(noonMillis) / 3_600_000.0
        val noon = 12.0 - eqt - lng / 15.0 + tzHours   // solar noon (hours)

        val fajr    = noon - hourAngleForAltitude(-FAJR_ANGLE, lat, decl)
        val sunrise = noon - hourAngleForAltitude(-0.833, lat, decl)
        val dhuhr   = noon + 2.0 / 60.0
        val asr     = noon + asrHourAngle(lat, decl)
        val maghrib = noon + hourAngleForAltitude(-0.833, lat, decl)
        val isha    = noon + hourAngleForAltitude(-ISHA_ANGLE, lat, decl)

        val map = mapOf(
            Prayer.FAJR    to hourToEpoch(fajr),
            Prayer.SUNRISE to hourToEpoch(sunrise),
            Prayer.DHUHR   to hourToEpoch(dhuhr),
            Prayer.ASR     to hourToEpoch(asr),
            Prayer.MAGHRIB to hourToEpoch(maghrib),
            Prayer.ISHA    to hourToEpoch(isha),
        )
        return Timings(map)
    }

    /* ------------------------------------------------------------- */
    /*  Astronomy helpers                                             */
    /* ------------------------------------------------------------- */

    private fun julianDay(ms: Long): Double = ms / 86_400_000.0 + 2440587.5

    private fun sunPosition(jd: Double): Pair<Double, Double> {
        val d  = jd - 2451545.0
        val g  = (357.529 + 0.98560028 * d).mod(360.0)
        val q  = (280.459 + 0.98564736 * d).mod(360.0)
        val ll = (q + 1.915 * sin(g.toRad()) + 0.020 * sin((2 * g).toRad())).mod(360.0)
        val e  = 23.439 - 0.00000036 * d
        val ra = atan2(cos(e.toRad()) * sin(ll.toRad()), cos(ll.toRad())).toDeg() / 15.0
        val decl = asin(sin(e.toRad()) * sin(ll.toRad())).toDeg()
        var eqt = q / 15.0 - ra
        if (eqt > 12) eqt -= 24
        if (eqt < -12) eqt += 24
        return decl to eqt
    }

    private fun hourAngleForAltitude(alt: Double, lat: Double, decl: Double): Double {
        val cosH = (sin(alt.toRad()) - sin(lat.toRad()) * sin(decl.toRad())) /
                   (cos(lat.toRad()) * cos(decl.toRad()))
        if (cosH > 1) return Double.NaN
        if (cosH < -1) return Double.NaN
        return acos(cosH).toDeg() / 15.0
    }

    private fun asrHourAngle(lat: Double, decl: Double): Double {
        // Shafi (shadow factor 1). Standard for most of South Asia.
        val a = atan(1.0 / (1.0 + tan(abs(lat - decl).toRad())))
        val altitude = atan(1.0 / tan(a)).toDeg()
        return hourAngleForAltitude(-altitude, lat, decl)
    }

    private fun hourToEpoch(h: Double): Long {
        if (h.isNaN()) return -1
        val cal = Calendar.getInstance()
        val whole = floor(h).toInt().coerceIn(0, 23)
        val mins  = ((h - whole) * 60).toInt().coerceIn(0, 59)
        cal.set(Calendar.HOUR_OF_DAY, whole)
        cal.set(Calendar.MINUTE, mins)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /* ------------------------------------------------------------- */
    private fun Double.toRad(): Double = this * PI / 180.0
    private fun Double.toDeg(): Double = this * 180.0 / PI
}
