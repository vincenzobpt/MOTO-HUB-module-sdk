// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// The screens' arithmetic, kept out of the composables so it can be tested without a phone.
package io.motohub.android.routesim.ui

import io.motohub.android.module.ModuleRouteError
import io.motohub.android.routesim.sim.DrivingProfile
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/** The longest route the module plans; the same figure the generator enforces. */
internal const val MAX_ROUTE_KM = 500

/** Start day and time, as the rider steps or types them. All in the phone's zone, wall-clock. */
internal object WhenMath {

    fun shiftDays(millis: Long, zone: ZoneId, days: Int): Long =
        fromLocal(local(millis, zone).plusDays(days.toLong()), zone)

    /** Wall-clock minutes: a step of 60 from 10:00 is 11:00 even across a clock change. */
    fun shiftMinutes(millis: Long, zone: ZoneId, minutes: Int): Long =
        fromLocal(local(millis, zone).plusMinutes(minutes.toLong()), zone)

    fun formatDate(millis: Long, zone: ZoneId): String {
        val d = local(millis, zone)
        return String.format(Locale.US, "%04d-%02d-%02d", d.year, d.monthValue, d.dayOfMonth)
    }

    fun formatTime(millis: Long, zone: ZoneId): String {
        val t = local(millis, zone)
        return String.format(Locale.US, "%02d:%02d", t.hour, t.minute)
    }

    /**
     * [text] as a date, keeping [millis]' time of day; null when it is not one. Accepts
     * 2026-10-03, 2026/10/3 and 3.10.2026 (day first when the year is last).
     */
    fun parseDate(text: String, millis: Long, zone: ZoneId): Long? {
        val parts = numbers(text) ?: return null
        if (parts.size != 3) return null
        val year: Int
        val month: Int
        val day: Int
        if (parts[0].length == 4) {
            year = parts[0].toInt(); month = parts[1].toInt(); day = parts[2].toInt()
        } else if (parts[2].length == 4) {
            day = parts[0].toInt(); month = parts[1].toInt(); year = parts[2].toInt()
        } else {
            return null
        }
        if (year < 1990 || year > 2100) return null
        val date = try {
            LocalDate.of(year, month, day)
        } catch (_: DateTimeException) {
            return null
        }
        return fromLocal(date.atTime(local(millis, zone).toLocalTime()), zone)
    }

    /** [text] as a time of day on [millis]' date: 9:30, 09.30, 0930, 9. Null when it is not one. */
    fun parseTime(text: String, millis: Long, zone: ZoneId): Long? {
        val parts = numbers(text) ?: return null
        val hour: Int
        val minute: Int
        if (parts.size == 1) {
            val digits = parts[0]
            if (digits.length <= 2) {
                hour = digits.toInt(); minute = 0
            } else if (digits.length <= 4) {
                hour = digits.substring(0, digits.length - 2).toInt()
                minute = digits.substring(digits.length - 2).toInt()
            } else {
                return null
            }
        } else if (parts.size == 2) {
            if (parts[0].length > 2 || parts[1].length > 2) return null
            hour = parts[0].toInt(); minute = parts[1].toInt()
        } else {
            return null
        }
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return null
        val day = local(millis, zone).toLocalDate()
        return fromLocal(day.atTime(hour, minute), zone)
    }

    private fun local(millis: Long, zone: ZoneId): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zone)

    private fun fromLocal(local: LocalDateTime, zone: ZoneId): Long =
        local.atZone(zone).toInstant().toEpochMilli()

    /** The runs of digits in [text], or null when anything else than a separator is in it. */
    private fun numbers(text: String): List<String>? {
        val out = ArrayList<String>()
        val current = StringBuilder()
        for (c in text.trim()) {
            if (c >= '0' && c <= '9') {
                current.append(c)
            } else if (c == '-' || c == '/' || c == '.' || c == ':' || c == ' ' || c == 'h' || c == 'H') {
                if (current.length > 0) {
                    out.add(current.toString())
                    current.setLength(0)
                }
            } else {
                return null
            }
        }
        if (current.length > 0) out.add(current.toString())
        if (out.isEmpty() || out.size > 3) return null
        for (s in out) if (s.length > 9) return null
        return out
    }
}

/** One value of the advanced section: its bounds, its step and how it reads out. */
internal enum class AdvancedParam(val min: Double, val max: Double, val step: Double) {
    ACCEL(0.5, 6.0, 0.1),
    BRAKE(1.0, 8.0, 0.1),
    LATERAL(1.0, 8.0, 0.1),
    OVER_LIMIT(0.60, 1.40, 0.01),
    MAX_LEAN(15.0, 60.0, 1.0),
    MAX_RPM(6000.0, 14000.0, 100.0),
    SHIFT_UP_RPM(3000.0, 13000.0, 100.0);

    fun read(p: DrivingProfile): Double = when (this) {
        ACCEL -> p.accelMs2
        BRAKE -> p.brakeMs2
        LATERAL -> p.lateralAccelMs2
        OVER_LIMIT -> p.overLimitFactor
        MAX_LEAN -> p.maxLeanDeg
        MAX_RPM -> p.maxRpm
        SHIFT_UP_RPM -> p.shiftUpRpm
    }

    /** The rev limiter stays above the shift point, and the shift point under the limiter. */
    fun lowerBound(p: DrivingProfile): Double =
        if (this == MAX_RPM) Math.max(min, p.shiftUpRpm + RPM_GAP) else min

    fun upperBound(p: DrivingProfile): Double =
        if (this == SHIFT_UP_RPM) Math.min(max, p.maxRpm - RPM_GAP) else max

    /** A copy of [p] with this value set to [value], snapped to the step and held inside the bounds. */
    fun write(p: DrivingProfile, value: Double): DrivingProfile {
        val lo = lowerBound(p)
        val hi = upperBound(p)
        var v = Math.max(lo, Math.min(hi, value))
        v = Math.round(v / step) * step
        v = Math.round(v * 10000.0) / 10000.0
        v = Math.max(lo, Math.min(hi, v))
        return when (this) {
            ACCEL -> p.copy(accelMs2 = v)
            BRAKE -> p.copy(brakeMs2 = v)
            LATERAL -> p.copy(lateralAccelMs2 = v)
            OVER_LIMIT -> p.copy(overLimitFactor = v)
            MAX_LEAN -> p.copy(maxLeanDeg = v)
            MAX_RPM -> p.copy(maxRpm = v)
            SHIFT_UP_RPM -> p.copy(shiftUpRpm = v)
        }
    }

    /** One step up (+1) or down (-1). */
    fun nudge(p: DrivingProfile, direction: Int): DrivingProfile = write(p, read(p) + direction * step)

    fun canNudge(p: DrivingProfile, direction: Int): Boolean =
        if (direction > 0) read(p) < upperBound(p) - step / 2 else read(p) > lowerBound(p) + step / 2

    fun format(p: DrivingProfile): String {
        val v = read(p)
        return when (this) {
            ACCEL, BRAKE, LATERAL -> String.format(Locale.US, "%.1f m/s²", v)
            OVER_LIMIT -> String.format(Locale.US, "%d %%", Math.round(v * 100.0))
            MAX_LEAN -> String.format(Locale.US, "%d°", Math.round(v))
            MAX_RPM, SHIFT_UP_RPM -> String.format(Locale.US, "%d rpm", Math.round(v))
        }
    }

    companion object {
        const val RPM_GAP = 500.0

        /** Whether the advanced values still are the style's own, so there is nothing to reset. */
        fun isUntouched(p: DrivingProfile): Boolean = isUntouched(p, DrivingProfile.generic(p.style))

        /** The same, against [base]: the style's profile, with the rider's learned numbers when there are any. */
        fun isUntouched(p: DrivingProfile, base: DrivingProfile): Boolean {
            for (param in values()) {
                if (Math.abs(param.read(p) - param.read(base)) > 1e-9) return false
            }
            return true
        }
    }
}

internal class LatLon(val lat: Double, val lon: Double)

/** What the place field does with what the rider types. */
internal object SearchInput {
    const val MIN_CHARS = 3
    const val DEBOUNCE_MS = 700L

    fun isSearchable(text: String): Boolean = text.trim().length >= MIN_CHARS

    /**
     * "45.0703, 7.6869" and its cousins: a comma, a semicolon or a space between the two, a decimal
     * comma ("45,0703 7,6869"). Null when it is not a point on Earth.
     */
    fun parseLatLon(text: String): LatLon? {
        val t = text.trim()
        if (t.isEmpty()) return null
        // Nothing but digits, signs, dots, commas, semicolons and blanks can be a coordinate.
        for (c in t) {
            val ok = (c >= '0' && c <= '9') || c == '.' || c == ',' || c == ';' || c == '-' || c == '+' || c == ' ' || c == '\t'
            if (!ok) return null
        }
        val candidates = ArrayList<List<String>>()
        if (t.indexOf(';') >= 0) candidates.add(splitOn(t, ";"))
        if (countOf(t, ',') == 1) candidates.add(splitOn(t, ","))
        if (t.indexOf(", ") >= 0) candidates.add(splitOn(t, ", "))
        candidates.add(blankSeparated(t))
        for (parts in candidates) {
            if (parts.size != 2) continue
            val lat = number(parts[0]) ?: continue
            val lon = number(parts[1]) ?: continue
            if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) continue
            return LatLon(lat, lon)
        }
        return null
    }

    private fun number(s: String): Double? {
        val v = s.trim().replace(',', '.').toDoubleOrNull() ?: return null
        return if (v.isNaN() || v.isInfinite()) null else v
    }

    private fun countOf(s: String, c: Char): Int {
        var n = 0
        for (x in s) if (x == c) n++
        return n
    }

    private fun splitOn(s: String, sep: String): List<String> {
        val out = ArrayList<String>()
        var from = 0
        while (true) {
            val at = s.indexOf(sep, from)
            if (at < 0) {
                out.add(s.substring(from))
                return out
            }
            out.add(s.substring(from, at))
            from = at + sep.length
        }
    }

    private fun blankSeparated(s: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (c in s) {
            if (c == ' ' || c == '\t') {
                if (cur.length > 0) {
                    out.add(cur.toString())
                    cur.setLength(0)
                }
            } else {
                cur.append(c)
            }
        }
        if (cur.length > 0) out.add(cur.toString())
        return out
    }
}

/** The four charts of the preview. */
internal enum class ChartKind { SPEED, ALTITUDE, LEAN, RPM }

internal class AxisRange(val min: Float, val max: Float) {
    val span: Float get() = max - min
}

/** Scaling and cursor arithmetic of the preview charts. */
internal object ChartMath {

    /** The next "round" number at or above [v]: 1, 2, 2.5, 5 times a power of ten. */
    fun niceCeil(v: Float): Float {
        if (v.isNaN() || v <= 0f) return 1f
        val exp = Math.floor(Math.log10(v.toDouble()))
        val base = Math.pow(10.0, exp)
        val frac = v / base
        val nice = if (frac <= 1.0 + 1e-9) 1.0 else if (frac <= 2.0 + 1e-9) 2.0 else if (frac <= 2.5 + 1e-9) 2.5 else if (frac <= 5.0 + 1e-9) 5.0 else 10.0
        return (nice * base).toFloat()
    }

    /** The vertical range for [kind], from the finite values only; null when there are none. */
    fun axis(values: FloatArray, kind: ChartKind): AxisRange? {
        var lo = Float.POSITIVE_INFINITY
        var hi = Float.NEGATIVE_INFINITY
        var peak = 0f
        for (v in values) {
            if (v.isNaN() || v.isInfinite()) continue
            if (v < lo) lo = v
            if (v > hi) hi = v
            val a = Math.abs(v)
            if (a > peak) peak = a
        }
        if (lo > hi) return null
        return when (kind) {
            ChartKind.SPEED -> AxisRange(0f, niceCeil(Math.max(hi, 20f)))
            ChartKind.RPM -> AxisRange(0f, niceCeil(Math.max(hi, 1000f)))
            ChartKind.LEAN -> {
                val m = niceCeil(Math.max(peak, 10f))
                AxisRange(-m, m)
            }
            ChartKind.ALTITUDE -> {
                val span = Math.max(hi - lo, 20f)
                val mid = (hi + lo) / 2f
                val pad = span * 0.08f
                AxisRange(mid - span / 2f - pad, mid + span / 2f + pad)
            }
        }
    }

    /** The sample nearest to [fraction] (0..1) of the ride's time. */
    fun cursorIndex(times: FloatArray, fraction: Float): Int {
        val n = times.size
        if (n <= 1) return 0
        val f = if (fraction.isNaN()) 0f else Math.max(0f, Math.min(1f, fraction))
        val target = times[0] + f * (times[n - 1] - times[0])
        var lo = 0
        var hi = n - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (times[mid] <= target) lo = mid else hi = mid
        }
        return if (target - times[lo] <= times[hi] - target) lo else hi
    }

    /** The time fraction of sample [index]. */
    fun fractionOfIndex(times: FloatArray, index: Int): Float {
        val n = times.size
        if (n <= 1) return 0f
        val total = times[n - 1] - times[0]
        if (total <= 0f) return 0f
        return Math.max(0f, Math.min(1f, (times[Math.max(0, Math.min(n - 1, index))] - times[0]) / total))
    }

    /** A touch at [x] in a chart [width] wide with [padLeft]/[padRight] of margin, as a fraction of its time. */
    fun fractionOfX(x: Float, width: Float, padLeft: Float, padRight: Float): Float {
        val inner = width - padLeft - padRight
        if (inner <= 0f) return 0f
        return Math.max(0f, Math.min(1f, (x - padLeft) / inner))
    }

    fun xOfFraction(fraction: Float, width: Float, padLeft: Float, padRight: Float): Float =
        padLeft + fraction * (width - padLeft - padRight)

    /** Pixel y of [value] when [range] spans [top]..[top]+[height] from the bottom up. */
    fun yOfValue(value: Float, range: AxisRange, top: Float, height: Float): Float {
        val span = if (range.span <= 0f) 1f else range.span
        return top + height - (value - range.min) / span * height
    }
}

/** The ride's line on the map, coloured by speed in a handful of runs. */
internal object SpeedRamp {
    const val BUCKETS = 8

    private val STOPS = intArrayOf(
        0xFF3D7BFF.toInt(), 0xFF22C3A6.toInt(), 0xFF7BD34A.toInt(),
        0xFFF2D43C.toInt(), 0xFFF28C28.toInt(), 0xFFE5393B.toInt()
    )

    /** A run of the route: points [first]..[last] inclusive, all drawn in one colour. */
    class Run(val first: Int, val last: Int, val bucket: Int)

    fun bucketOf(kph: Float, topKph: Float): Int {
        if (kph.isNaN() || topKph <= 0f) return 0
        val f = Math.max(0f, Math.min(1f, kph / topKph))
        return Math.min(BUCKETS - 1, (f * BUCKETS).toInt())
    }

    fun colorOf(bucket: Int): Int {
        val t = Math.max(0, Math.min(BUCKETS - 1, bucket)).toFloat() / (BUCKETS - 1)
        val scaled = t * (STOPS.size - 1)
        val i = Math.min(STOPS.size - 2, scaled.toInt())
        val f = scaled - i
        val a = STOPS[i]
        val b = STOPS[i + 1]
        return (0xFF shl 24) or
            (mix((a shr 16) and 0xFF, (b shr 16) and 0xFF, f) shl 16) or
            (mix((a shr 8) and 0xFF, (b shr 8) and 0xFF, f) shl 8) or
            mix(a and 0xFF, b and 0xFF, f)
    }

    private fun mix(a: Int, b: Int, f: Float): Int = Math.round(a + (b - a) * f)

    /**
     * Splits the route into at most [maxRuns] runs of one colour each. Neighbouring runs share
     * their joining point so the line has no gaps. Speeds are averaged over blocks of segments,
     * so a noisy series cannot make thousands of lines.
     */
    fun runs(speeds: FloatArray, topKph: Float, maxRuns: Int): List<Run> {
        val n = speeds.size
        if (n < 2 || maxRuns < 1) return emptyList()
        val segments = n - 1
        val block = Math.max(1, (segments + maxRuns - 1) / maxRuns)
        val out = ArrayList<Run>()
        var s = 0
        while (s < segments) {
            val e = Math.min(segments, s + block)
            var sum = 0.0
            var count = 0
            for (i in s..e) {
                val v = speeds[i]
                if (!v.isNaN() && !v.isInfinite()) {
                    sum += v
                    count++
                }
            }
            val bucket = if (count == 0) 0 else bucketOf((sum / count).toFloat(), topKph)
            if (out.isNotEmpty() && out[out.size - 1].bucket == bucket) {
                val prev = out[out.size - 1]
                out[out.size - 1] = Run(prev.first, e, bucket)
            } else {
                out.add(Run(s, e, bucket))
            }
            s = e
        }
        return out
    }
}

/** Numbers as the screens print them. */
internal object Fmt {

    fun distance(meters: Double): String =
        if (meters >= 100_000.0) String.format(Locale.US, "%.0f km", meters / 1000.0)
        else String.format(Locale.US, "%.1f km", meters / 1000.0)

    fun duration(seconds: Double): String {
        val total = Math.round(seconds)
        val h = total / 3600
        val m = (total / 60) % 60
        return if (h > 0) String.format(Locale.US, "%dh %02dm", h, m) else String.format(Locale.US, "%d min", Math.max(1L, m))
    }

    /** m:ss, or h:mm:ss from an hour on. */
    fun clock(seconds: Float): String {
        val s = Math.max(0, Math.round(seconds))
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
        else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    fun speed(kph: Float): String =
        if (kph.isNaN()) "–" else String.format(Locale.US, "%.0f km/h", kph)

    fun coordinates(latitude: Double, longitude: Double): String =
        String.format(Locale.US, "%.5f, %.5f", latitude, longitude)

    /** The first part of a place label ("Torino, Piemonte, Italia" -> "Torino"), kept short. */
    fun shortLabel(label: String): String {
        val cut = label.indexOf(',')
        val head = (if (cut > 0) label.substring(0, cut) else label).trim()
        return if (head.length > 28) head.substring(0, 27).trim() + "…" else head
    }
}

/** A key that changes whenever the stops move, so a route planned for them can be reused. */
internal fun coordinatesKey(latitudes: DoubleArray, longitudes: DoubleArray): String {
    val sb = StringBuilder()
    for (i in latitudes.indices) {
        sb.append(String.format(Locale.US, "%.6f,%.6f;", latitudes[i], longitudes[i]))
    }
    return sb.toString()
}

/** What to tell the rider when planning failed: [kind] is a [ModuleRouteError]; [message] is the service's. */
internal fun failureText(kind: Int, message: String?): String = when (kind) {
    ModuleRouteError.NO_NETWORK -> Strings.OFFLINE
    ModuleRouteError.RATE_LIMITED -> if (message.isNullOrBlank()) Strings.RATE_LIMITED else message
    ModuleRouteError.NO_API_KEY -> Strings.NO_API_KEY
    ModuleRouteError.TOO_LONG -> if (message.isNullOrBlank()) Strings.tooLong(MAX_ROUTE_KM) else message
    else -> if (message.isNullOrBlank()) Strings.GENERIC_FAILURE else message
}

/** What the preview's summary row says about a generated ride. */
internal class RideStats(val distanceMeters: Double, val durationSeconds: Double, val averageKph: Float, val topKph: Float)

internal fun rideStats(timesMillis: LongArray, speedsKph: FloatArray, distanceMeters: Double): RideStats {
    val seconds = if (timesMillis.isEmpty()) 0.0 else (timesMillis[timesMillis.size - 1] - timesMillis[0]) / 1000.0
    var top = 0f
    for (v in speedsKph) if (!v.isNaN() && v > top) top = v
    val average = if (seconds > 0.0) (distanceMeters / seconds * 3.6).toFloat() else 0f
    return RideStats(distanceMeters, seconds, average, top)
}

/**
 * Indices of at most [maxPoints] of [count] points, evenly spread, always keeping the first and the
 * last: a long route is drawn on the map from far fewer points than it was planned with.
 */
internal fun thinIndices(count: Int, maxPoints: Int): IntArray {
    if (count <= 0) return IntArray(0)
    if (count <= maxPoints || maxPoints < 2) {
        return if (count <= maxPoints) IntArray(count) { it } else intArrayOf(0, count - 1)
    }
    val out = IntArray(maxPoints)
    for (i in 0 until maxPoints) {
        out[i] = Math.round(i.toDouble() * (count - 1) / (maxPoints - 1)).toInt()
    }
    return out
}

/** The value under the chart's cursor, as the chart's title row prints it. */
internal fun chartReadout(kind: ChartKind, value: Float): String {
    if (value.isNaN() || value.isInfinite()) return "–"
    return when (kind) {
        ChartKind.SPEED -> String.format(Locale.US, "%.0f km/h", value)
        ChartKind.ALTITUDE -> String.format(Locale.US, "%.0f m", value)
        ChartKind.LEAN -> {
            val deg = Math.round(Math.abs(value))
            if (deg == 0) "0°" else String.format(Locale.US, "%d° %s", deg, if (value > 0f) "right" else "left")
        }
        ChartKind.RPM -> String.format(Locale.US, "%.0f rpm", value)
    }
}

/** The number printed at the end of a chart's axis. */
internal fun axisLabel(value: Float): String = String.format(Locale.US, "%.0f", value)
