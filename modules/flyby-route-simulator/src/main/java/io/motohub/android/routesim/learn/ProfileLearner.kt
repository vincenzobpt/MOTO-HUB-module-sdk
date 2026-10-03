// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// The calibration maths: the rider's recorded rides in, a LearnedProfile out. Pure, deterministic,
// no Android, no randomness. Every number it learns is in the terms the engine models it in:
// lean as LeanModel makes it (atan(a/g) times a 0.92 body factor), rpm as Drivetrain makes it
// (speed in m/s times a per-gear ratio).
//
// Constants (all in [ProfileLearner]'s companion-style private block below):
//   lean      points with speed >= 10 km/h and |lean| <= 65 deg; need >= 500 points
//             maxLean = p99.5 of |lean| clamp 20..60
//             lateralAccel = 9.81 * tan(p95 |lean| / 0.92) clamp 1.5..7
//   accel     consecutive pairs 0 < dt <= 2.5 s, both speeds >= 5 km/h, |a| <= 12 m/s^2;
//             accel = p90 of positive a (>= 200 pairs) clamp 1..5,
//             brake = p90 of braking magnitude (>= 200 pairs) clamp 1.5..6
//   limits    points with a known limit, speed >= 0.5 * limit and |lean| <= 8 deg (or no lean); need >= 300 points;
//             overLimitFactor = median(speed / limit) clamp 0.7..1.3
//   rpm       valid rpm = finite and > 300 (a stopped engine reads 0); a ride "has rpm" with >= 100 of them
//             idle = p5 of rpm at speed < 2 km/h (>= 100 samples) clamp 700..2000
//             maxRpm = p99.9 of rpm * 1.02 (>= 500 samples) clamp 6000..14000
//             gears: points with speed >= 15 km/h (>= 500), 1-D k-means on ln(rpm / speed m/s),
//             6 clusters seeded from the generic ladder, clusters under 2% of the points dropped
//             (that gear keeps the generic ratio), ladder sorted descending and required to step
//             by >= 5% from gear to gear, else not learned
//             shift points: runs of >= 2 points and >= 0.5 s in one cluster (all 6 clusters, even those too rare for the ladder); a change to the neighbouring
//             cluster within 2.5 s is a shift; the rpm at the last point before it, median over
//             >= 15 up-shifts / >= 15 down-shifts
package io.motohub.android.routesim.learn

import io.motohub.android.routesim.sim.DrivingProfile
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

object ProfileLearner {

    fun learn(rides: List<RideSample>, kmUsed: Double, nowMillis: Long): LearnedProfile {
        val lean = learnLean(rides)
        val longitudinal = learnLongitudinal(rides)
        val over = learnOverLimit(rides)
        val engine = learnEngine(rides)
        return LearnedProfile(
            learnedAtMillis = nowMillis,
            ridesUsed = rides.size,
            kmUsed = kmUsed,
            rpmRidesUsed = engine.rpmRides,
            maxLeanDeg = lean.maxLean,
            lateralAccelMs2 = lean.lateral,
            accelMs2 = longitudinal.accel,
            brakeMs2 = longitudinal.brake,
            overLimitFactor = over,
            gearRatios = engine.gearRatios,
            idleRpm = engine.idle,
            maxRpm = engine.maxRpm,
            shiftUpRpm = engine.shiftUp,
            shiftDownRpm = engine.shiftDown,
        )
    }

    // --- lean ----------------------------------------------------------------------------

    private class LeanResult(val maxLean: Double?, val lateral: Double?)

    private fun learnLean(rides: List<RideSample>): LeanResult {
        val values = DoubleList()
        for (r in rides) {
            for (i in 0 until r.count()) {
                val v = r.speedsKph[i]
                val l = r.leanDegrees.at(i)
                if (!v.isFinite() || v < LEAN_MIN_KPH || !l.isFinite()) continue
                val a = abs(l.toDouble())
                if (a > LEAN_OUTLIER_DEG) continue
                values.add(a)
            }
        }
        if (values.size < MIN_LEAN_POINTS) return LeanResult(null, null)
        val sorted = values.sorted()
        val maxLean = percentile(sorted, 99.5).within(LearnedRanges.MAX_LEAN)
        val p95 = percentile(sorted, 95.0)
        val lateral = (G * tan(Math.toRadians(p95 / BODY_FACTOR))).within(LearnedRanges.LATERAL)
        return LeanResult(maxLean, lateral)
    }

    // --- acceleration and braking ---------------------------------------------------------

    private class LongitudinalResult(val accel: Double?, val brake: Double?)

    private fun learnLongitudinal(rides: List<RideSample>): LongitudinalResult {
        val accel = DoubleList()
        val brake = DoubleList()
        for (r in rides) {
            for (i in 0 until r.count() - 1) {
                val dt = (r.timesMillis[i + 1] - r.timesMillis[i]) / 1000.0
                if (!(dt > 0.0) || dt > MAX_PAIR_DT_S) continue
                val v0 = r.speedsKph[i]
                val v1 = r.speedsKph[i + 1]
                if (!v0.isFinite() || !v1.isFinite() || min(v0, v1) < PAIR_MIN_KPH) continue
                val a = (v1 - v0).toDouble() / 3.6 / dt
                if (abs(a) > MAX_PLAUSIBLE_ACCEL) continue
                if (a > 0.0) accel.add(a) else if (a < 0.0) brake.add(-a)
            }
        }
        val a = if (accel.size >= MIN_PAIRS) percentile(accel.sorted(), 90.0).within(LearnedRanges.ACCEL) else null
        val b = if (brake.size >= MIN_PAIRS) percentile(brake.sorted(), 90.0).within(LearnedRanges.BRAKE) else null
        return LongitudinalResult(a, b)
    }

    // --- speed against the limit ----------------------------------------------------------

    private fun learnOverLimit(rides: List<RideSample>): Double? {
        val ratios = DoubleList()
        for (r in rides) {
            val limits = r.speedLimitKph ?: continue
            for (i in 0 until r.count()) {
                val limit = limits.at(i)
                val v = r.speedsKph[i]
                if (!limit.isFinite() || limit <= 0f || !v.isFinite() || v < LIMIT_MIN_SHARE * limit) continue
                // In a bend the road, not the rider's appetite for the limit, sets the speed (the
                // engine slows for bends by itself), so only straight-ish points count.
                val lean = r.leanDegrees.at(i)
                if (lean.isFinite() && abs(lean) > LIMIT_MAX_LEAN_DEG) continue
                ratios.add(v.toDouble() / limit.toDouble())
            }
        }
        if (ratios.size < MIN_LIMIT_POINTS) return null
        return percentile(ratios.sorted(), 50.0).within(LearnedRanges.OVER_LIMIT)
    }

    // --- engine speed ---------------------------------------------------------------------

    private class EngineResult(
        val rpmRides: Int,
        val idle: Double?,
        val maxRpm: Double?,
        val gearRatios: DoubleArray?,
        val shiftUp: Double?,
        val shiftDown: Double?,
    )

    /** The points of one ride usable for gear work: speed >= 15 km/h and a valid rpm. */
    private class GearPoints(val index: IntArray, val timesMillis: LongArray, val rpm: DoubleArray, val lnRatio: DoubleArray)

    private fun validRpm(v: Float): Boolean = v.isFinite() && v > MIN_VALID_RPM

    private fun learnEngine(rides: List<RideSample>): EngineResult {
        var rpmRides = 0
        val idleValues = DoubleList()
        val allRpm = DoubleList()
        val perRide = ArrayList<GearPoints>()

        for (r in rides) {
            val rpm = r.rpm ?: continue
            var valid = 0
            val idx = IntList()
            val ts = LongList()
            val rr = DoubleList()
            val xs = DoubleList()
            for (i in 0 until r.count()) {
                val e = rpm.at(i)
                if (!validRpm(e)) continue
                valid++
                allRpm.add(e.toDouble())
                val v = r.speedsKph[i]
                if (!v.isFinite()) continue
                if (v < IDLE_MAX_KPH) idleValues.add(e.toDouble())
                if (v >= GEAR_MIN_KPH) {
                    idx.add(i); ts.add(r.timesMillis[i]); rr.add(e.toDouble())
                    xs.add(ln(e.toDouble() / (v / 3.6)))
                }
            }
            if (valid < MIN_RPM_POINTS_PER_RIDE) continue
            rpmRides++
            if (idx.size > 0) perRide.add(GearPoints(idx.toIntArray(), ts.toLongArray(), rr.toDoubleArray(), xs.toDoubleArray()))
        }

        val idle = if (idleValues.size >= MIN_IDLE_SAMPLES) percentile(idleValues.sorted(), 5.0).within(LearnedRanges.IDLE) else null
        val maxRpm = if (allRpm.size >= MIN_RPM_POINTS) {
            (percentile(allRpm.sorted(), 99.9) * MAX_RPM_HEADROOM).within(LearnedRanges.MAX_RPM)
        } else null

        val total = perRide.sumOf { it.index.size }
        if (total < MIN_GEAR_POINTS) return EngineResult(rpmRides, idle, maxRpm, null, null, null)

        val seeds = DoubleArray(DrivingProfile.GENERIC_GEAR_RATIOS.size) { ln(DrivingProfile.GENERIC_GEAR_RATIOS[it]) }
        val all = DoubleArray(total)
        var at = 0
        for (p in perRide) { System.arraycopy(p.lnRatio, 0, all, at, p.lnRatio.size); at += p.lnRatio.size }
        val centers = kMeans(all, seeds)
        val counts = IntArray(centers.size)
        for (x in all) counts[nearest(x, centers)]++
        val kept = BooleanArray(centers.size) { counts[it] >= MIN_CLUSTER_SHARE * total }
        if (kept.none { it }) return EngineResult(rpmRides, idle, maxRpm, null, null, null)

        val ladder = DoubleArray(centers.size) { g ->
            if (kept[g]) exp(centers[g]) else DrivingProfile.GENERIC_GEAR_RATIOS[g]
        }
        java.util.Arrays.sort(ladder) // ascending; 1st gear (the biggest ratio) goes first below
        for (a in 0 until ladder.size / 2) {
            val b = ladder.size - 1 - a
            val t = ladder[a]; ladder[a] = ladder[b]; ladder[b] = t
        }
        var ladderOk = true
        for (g in 1 until ladder.size) if (ladder[g - 1] < ladder[g] * MIN_GEAR_STEP) ladderOk = false
        val gearRatios = if (ladderOk) ladder else null

        // Shift points: only meaningful when the gears themselves could be told apart.
        var shiftUp: Double? = null
        var shiftDown: Double? = null
        if (gearRatios != null) {
            val ups = DoubleList()
            val downs = DoubleList()
            for (p in perRide) collectShifts(p, centers, ups, downs)
            if (ups.size >= MIN_SHIFTS) shiftUp = percentile(ups.sorted(), 50.0).within(LearnedRanges.SHIFT_UP)
            if (downs.size >= MIN_SHIFTS) shiftDown = percentile(downs.sorted(), 50.0).within(LearnedRanges.SHIFT_DOWN)
        }
        return EngineResult(rpmRides, idle, maxRpm, gearRatios, shiftUp, shiftDown)
    }

    private class Run(val cluster: Int, val firstT: Long, var lastT: Long, var lastRpm: Double, var lastIndex: Int) {
        var points = 1
    }

    /**
     * Cuts one ride into runs of consecutive points in the same cluster, keeps the runs that last
     * long enough to be a gear (a clutch dip or a blip is shorter), and counts every change to a
     * neighbouring gear. The cluster ids run 1st gear (highest ratio) first, so a higher id is a
     * higher gear: an up-shift when the id grows.
     */
    private fun collectShifts(p: GearPoints, centers: DoubleArray, ups: DoubleList, downs: DoubleList) {
        val runs = ArrayList<Run>()
        var current: Run? = null
        for (k in p.index.indices) {
            val c = nearest(p.lnRatio[k], centers)
            val prev = current
            val contiguous = prev != null && p.index[k] == prev.lastIndex + 1 &&
                p.timesMillis[k] - prev.lastT >= 1L && p.timesMillis[k] - prev.lastT <= MAX_PAIR_DT_MS
            if (prev != null && contiguous && prev.cluster == c) {
                prev.lastT = p.timesMillis[k]; prev.lastRpm = p.rpm[k]; prev.lastIndex = p.index[k]; prev.points++
            } else {
                val run = Run(c, p.timesMillis[k], p.timesMillis[k], p.rpm[k], p.index[k])
                runs.add(run)
                current = run
            }
        }
        val stable = runs.filter { it.points >= MIN_RUN_POINTS && it.lastT - it.firstT >= MIN_RUN_MS }
        for (j in 1 until stable.size) {
            val a = stable[j - 1]
            val b = stable[j]
            if (b.firstT - a.lastT > MAX_PAIR_DT_MS) continue
            val step = b.cluster - a.cluster
            if (step == 1) ups.add(a.lastRpm) else if (step == -1) downs.add(a.lastRpm)
        }
    }

    // --- small helpers --------------------------------------------------------------------

    /** Lloyd's algorithm in one dimension from the given seeds; the cluster order is kept. */
    internal fun kMeans(x: DoubleArray, seeds: DoubleArray): DoubleArray {
        val c = seeds.copyOf()
        val sum = DoubleArray(c.size)
        val cnt = IntArray(c.size)
        repeat(KMEANS_MAX_ITERATIONS) {
            for (k in c.indices) { sum[k] = 0.0; cnt[k] = 0 }
            for (v in x) { val k = nearest(v, c); sum[k] += v; cnt[k]++ }
            var shift = 0.0
            for (k in c.indices) {
                if (cnt[k] == 0) continue
                val m = sum[k] / cnt[k]
                shift = max(shift, abs(m - c[k]))
                c[k] = m
            }
            if (shift < KMEANS_EPSILON) return c
        }
        return c
    }

    private fun nearest(v: Double, centers: DoubleArray): Int {
        var best = 0
        var bestD = abs(v - centers[0])
        for (k in 1 until centers.size) {
            val d = abs(v - centers[k])
            if (d < bestD) { bestD = d; best = k }
        }
        return best
    }

    /** Linear-interpolated percentile (0..100) of an ascending array. */
    internal fun percentile(sorted: DoubleArray, p: Double): Double {
        if (sorted.isEmpty()) return Double.NaN
        if (sorted.size == 1) return sorted[0]
        val pos = Math.min(Math.max(p / 100.0, 0.0), 1.0) * (sorted.size - 1)
        val lo = pos.toInt()
        val hi = min(lo + 1, sorted.size - 1)
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
    }

    private fun RideSample.count(): Int = min(timesMillis.size, speedsKph.size)
    private fun FloatArray.at(i: Int): Float = if (i < size) this[i] else Float.NaN

    private const val G = 9.81
    private const val BODY_FACTOR = 0.92

    private const val LEAN_MIN_KPH = 10.0
    private const val LEAN_OUTLIER_DEG = 65.0
    private const val MIN_LEAN_POINTS = 500

    private const val MAX_PAIR_DT_S = 2.5
    private const val MAX_PAIR_DT_MS = 2500L
    private const val PAIR_MIN_KPH = 5.0f
    private const val MAX_PLAUSIBLE_ACCEL = 12.0
    private const val MIN_PAIRS = 200

    private const val LIMIT_MIN_SHARE = 0.5f
    private const val MIN_LIMIT_POINTS = 300
    private const val LIMIT_MAX_LEAN_DEG = 8.0f

    private const val MIN_VALID_RPM = 300f
    private const val MIN_RPM_POINTS_PER_RIDE = 100
    private const val IDLE_MAX_KPH = 2.0f
    private const val MIN_IDLE_SAMPLES = 100
    private const val MIN_RPM_POINTS = 500
    private const val MAX_RPM_HEADROOM = 1.02
    private const val GEAR_MIN_KPH = 15.0f
    private const val MIN_GEAR_POINTS = 500
    private const val MIN_CLUSTER_SHARE = 0.02
    private const val MIN_GEAR_STEP = 1.05
    private const val MIN_RUN_MS = 500L
    private const val MIN_RUN_POINTS = 2
    private const val MIN_SHIFTS = 15
    private const val KMEANS_MAX_ITERATIONS = 100
    private const val KMEANS_EPSILON = 1.0e-7
}

// Growable primitive lists (no boxing: a long ride is tens of thousands of points).

internal class DoubleList {
    private var a = DoubleArray(1024)
    var size = 0
        private set

    fun add(v: Double) {
        if (size == a.size) a = a.copyOf(size * 2)
        a[size++] = v
    }

    fun toDoubleArray(): DoubleArray = a.copyOf(size)
    fun sorted(): DoubleArray {
        val out = toDoubleArray()
        java.util.Arrays.sort(out)
        return out
    }
}

internal class IntList {
    private var a = IntArray(1024)
    var size = 0
        private set

    fun add(v: Int) {
        if (size == a.size) a = a.copyOf(size * 2)
        a[size++] = v
    }

    fun toIntArray(): IntArray = a.copyOf(size)
}

internal class LongList {
    private var a = LongArray(1024)
    var size = 0
        private set

    fun add(v: Long) {
        if (size == a.size) a = a.copyOf(size * 2)
        a[size++] = v
    }

    fun toLongArray(): LongArray = a.copyOf(size)
}
