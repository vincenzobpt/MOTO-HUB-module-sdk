// Derived from MockLocation (MIT, (c) 2026 Vincenzo Buonomano).
// The fast ride simulation: one synchronous loop on a virtual clock that turns a planned route
// into the recorded channels of a trip. No coroutines, no delay, no wall clock, no Android.
package io.motohub.android.routesim.sim

import java.util.Random
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object RideSimulator {

    // Seeds of the independent random streams, derived from the request seed.
    private const val SALT_TRAFFIC = 0x5DEECE66DL
    private const val SALT_IDLE = 0x2545F4914F6CDD1DL
    private const val SALT_LEAN = 0x1B873593L
    private const val SALT_DRIVE = 0x3C6EF372FE94F82BL
    private const val SALT_GPS = 0x7F4A7C15L

    /** Idle with the engine on before the first move and after the arrival: 20..40 s. */
    private const val IDLE_MIN_MS = 20_000
    private const val IDLE_SPAN_MS = 20_000

    /** Share of the comfortable braking used when planning ahead, the rest is reserve to catch up. */
    private const val PLAN_BRAKE_SHARE = 0.75

    /** Time constant of the throttle easing into a target speed from below. */
    private const val APPROACH_TAU_S = 0.6

    /** Jerk limits, m/s^3: brake onset (a becoming more negative) and everything else. */
    private const val JERK_BRAKE_ONSET = 12.0
    private const val JERK_OTHER = 5.0

    /** Acceleration fades with speed (engine power): full below ~0, 45% at and above ~30 m/s. */
    private const val ACCEL_FADE_SPEED_MS = 55.0
    private const val ACCEL_FADE_FLOOR = 0.45

    /** Arrival: within this distance and slow enough the bike is placed on the end and stops. */
    private const val ARRIVAL_CAPTURE_M = TrafficRuntime.STOP_CAPTURE_M
    private const val ARRIVAL_CAPTURE_SPEED_MS = TrafficRuntime.STOP_CAPTURE_SPEED_MS

    /** Below this the bike is stationary for the GPS model. */
    private const val STATIONARY_MS = 0.5

    /** Overtaking starts only when the bike is already near its cruise target. */
    private const val OVERTAKE_CRUISE_SHARE = 0.9

    private val METERS_PER_DEG_LAT =RouteGeometry.EARTH_RADIUS_M * Math.PI / 180.0

    private const val PHASE_IDLE_START = 0
    private const val PHASE_DRIVING = 1
    private const val PHASE_IDLE_END = 2

    /**
     * Rides [request] and returns every sample. Deterministic for a given request: the same seed
     * gives the same output, a different seed a different one. [SimRequest.startedAtMillis] is
     * not used here (times are relative to the start); the caller adds it.
     */
    fun run(request: SimRequest): SimOutput {
        val route = request.route
        val profile = request.profile
        val n = route.lats.size
        require(n >= 2 && route.lons.size == n) { "a route needs at least 2 points, lats and lons of equal size" }

        val hz = request.sampleRateHz.coerceIn(1, 100)
        val dtMs = max(1, 1000 / hz).toLong()
        val dtS = dtMs / 1000.0

        val geo = RouteGeometry(route, profile.lateralAccelMs2)
        val total = geo.totalMeters
        val step = RouteGeometry.GRID_STEP_M
        val gridCount = geo.gridCount

        // Speed ceilings per grid cell from the segment limit, and where overtaking makes sense.
        val limitMs = DoubleArray(gridCount)
        val overtakeEligible = BooleanArray(gridCount)
        for (k in 0 until gridCount) {
            val seg = geo.segmentAt(min(k * step, total))
            val lim = if (seg < route.speedLimitKph.size) route.speedLimitKph[seg].toDouble() else Double.NaN
            if (lim.isNaN() || lim <= 0.0) {
                limitMs[k] = profile.defaultCruiseKph / 3.6
                overtakeEligible[k] = true
            } else {
                limitMs[k] = lim * profile.overLimitFactor / 3.6
                overtakeEligible[k] = lim >= 70.0
            }
        }

        val events = TrafficPlanner.plan(total, request.traffic, Random(request.seed xor SALT_TRAFFIC)) { d ->
            overtakeEligible[cellOf(d, step, gridCount)]
        }
        val traffic = TrafficRuntime(events)

        val idleRng = Random(request.seed xor SALT_IDLE)
        val idleStartMs = IDLE_MIN_MS + idleRng.nextInt(IDLE_SPAN_MS + 1)
        val idleEndMs = IDLE_MIN_MS + idleRng.nextInt(IDLE_SPAN_MS + 1)

        val lean = LeanModel(profile, Random(request.seed xor SALT_LEAN))
        val drivetrain = Drivetrain(profile, Random(request.seed xor SALT_DRIVE))
        val gps = GpsNoise(Random(request.seed xor SALT_GPS))

        val planBrake = profile.brakeMs2 * PLAN_BRAKE_SHARE
        val maxSteps = ((total / 0.5 + 3600.0 + events.size * 60.0) * hz).toLong() + 2L * (idleStartMs + idleEndMs) / dtMs

        val out = Recorder()
        val pos = DoubleArray(2)

        var phase = PHASE_IDLE_START
        var phaseElapsedMs = 0L
        var s = 0.0 // metres along the route
        var v = 0.0 // m/s
        var a = 0.0 // smoothed acceleration command, m/s^2
        var accelActual = 0.0 // measured over the last tick, fed to the drivetrain
        var i = 0L

        while (true) {
            // -- Sample the current state -----------------------------------------------------
            val moving = v > 0.3
            val curvature = if (moving) geo.signedCurvatureAt(s) else 0.0
            val leanDeg = lean.step(v, curvature, dtS)
            val drive = drivetrain.step(v, accelActual, dtS)
            val fix = gps.step(dtS, v < STATIONARY_MS)
            geo.positionInto(s, pos)
            val latRad = Math.toRadians(pos[0])
            val lat = pos[0] + fix.northM / METERS_PER_DEG_LAT
            val lon = pos[1] + fix.eastM / (METERS_PER_DEG_LAT * cos(latRad).coerceAtLeast(0.05))
            val cap = profile.maxLeanDeg
            val leanOut = if (leanDeg.isFinite()) leanDeg.coerceIn(-cap, cap) else 0.0

            out.add(
                timeMs = i * dtMs,
                lat = lat,
                lon = lon,
                alt = geo.elevationAt(s),
                speedKph = (v * 3.6).toFloat(),
                heading = geo.headingAt(s).toFloat(),
                lean = leanOut.toFloat(),
                rpm = drive.rpm.toFloat(),
                gear = drive.gear,
                accuracy = fix.accuracyM.toFloat(),
                satellites = fix.satellites,
            )

            if (phase == PHASE_IDLE_END && phaseElapsedMs >= idleEndMs) break
            i++

            // -- Advance the state by one tick --------------------------------------------------
            if (i > maxSteps && phase != PHASE_IDLE_END) {
                // Safety valve against a stalled ride: place the bike on the destination.
                s = total
                v = 0.0
                a = 0.0
                accelActual = 0.0
                phase = PHASE_IDLE_END
                phaseElapsedMs = 0L
                continue
            }

            when (phase) {
                PHASE_IDLE_START -> {
                    phaseElapsedMs += dtMs
                    accelActual = 0.0
                    if (phaseElapsedMs >= idleStartMs) {
                        phase = if (total >= 1.0) PHASE_DRIVING else PHASE_IDLE_END
                        phaseElapsedMs = 0L
                    }
                }

                PHASE_IDLE_END -> {
                    phaseElapsedMs += dtMs
                    accelActual = 0.0
                }

                else -> {
                    val cell = cellOf(s, step, gridCount)
                    val cruising = v >= OVERTAKE_CRUISE_SHARE * limitMs[cell]
                    val snap = traffic.update(s, v, dtMs, overtakeEligible[cell] && cruising)
                    if (!snap.isNaN()) {
                        s = snap
                        v = 0.0
                        a = 0.0
                    }

                    if (traffic.holding) {
                        v = 0.0
                        a = 0.0
                        accelActual = 0.0
                    } else if (total - s <= ARRIVAL_CAPTURE_M && v <= ARRIVAL_CAPTURE_SPEED_MS) {
                        s = total
                        v = 0.0
                        a = 0.0
                        accelActual = 0.0
                        phase = PHASE_IDLE_END
                        phaseElapsedMs = 0L
                    } else {
                        val allowed = allowedMs(s, total, geo, limitMs, traffic, planBrake)
                        val accelCap = profile.accelMs2 * max(ACCEL_FADE_FLOOR, 1.0 - v / ACCEL_FADE_SPEED_MS)
                        val aDes = if (v > allowed) {
                            -min(profile.brakeMs2, (v - allowed) / dtS)
                        } else {
                            min(accelCap, (allowed - v) / APPROACH_TAU_S)
                        }
                        val da = aDes - a
                        val maxDown = JERK_BRAKE_ONSET * dtS
                        val maxUp = JERK_OTHER * dtS
                        a += da.coerceIn(-maxDown, maxUp)

                        val vNew = max(0.0, v + a * dtS)
                        accelActual = (vNew - v) / dtS
                        v = vNew
                        s = min(total, s + v * dtS)
                    }
                }
            }
        }

        return out.toOutput(total)
    }

    private fun cellOf(meters: Double, step: Double, gridCount: Int): Int =
        floor(meters / step).toInt().coerceIn(0, gridCount - 1)

    /**
     * Highest speed in m/s the bike may have at [s] and still meet, braking at [planBrake], the
     * lowest ceiling of everything ahead: curves, segment limits (raised by an ongoing overtake
     * burst), traffic events and the destination. For a ceiling c at distance ds ahead the
     * allowed speed now is sqrt(c^2 + 2 * planBrake * ds).
     */
    private fun allowedMs(
        s: Double,
        total: Double,
        geo: RouteGeometry,
        limitMs: DoubleArray,
        traffic: TrafficRuntime,
        planBrake: Double,
    ): Double {
        val step = RouteGeometry.GRID_STEP_M
        val boost = traffic.boostMs

        var allowed = TrafficRuntime.stopCeilingMs(total - s, planBrake)
        var k = cellOf(s, step, limitMs.size)
        while (k < limitMs.size) {
            val ds = max(0.0, k * step - s)
            if (2.0 * planBrake * ds >= allowed * allowed) break
            val ceiling = min(geo.curveLimitCellMs(k), limitMs[k] + boost)
            val v = sqrt(ceiling * ceiling + 2.0 * planBrake * ds)
            if (v < allowed) allowed = v
            k++
        }
        return min(allowed, traffic.allowedMs(s, planBrake))
    }

    /** Growable parallel arrays for the samples. */
    private class Recorder {
        private var cap = 4096
        private var size = 0
        private var times = LongArray(cap)
        private var lats = DoubleArray(cap)
        private var lons = DoubleArray(cap)
        private var alts = DoubleArray(cap)
        private var speeds = FloatArray(cap)
        private var headings = FloatArray(cap)
        private var leans = FloatArray(cap)
        private var rpms = FloatArray(cap)
        private var gears = IntArray(cap)
        private var accuracies = FloatArray(cap)
        private var sats = IntArray(cap)

        fun add(
            timeMs: Long, lat: Double, lon: Double, alt: Double, speedKph: Float, heading: Float,
            lean: Float, rpm: Float, gear: Int, accuracy: Float, satellites: Int,
        ) {
            if (size == cap) grow()
            times[size] = timeMs
            lats[size] = lat
            lons[size] = lon
            alts[size] = alt
            speeds[size] = speedKph
            headings[size] = heading
            leans[size] = lean
            rpms[size] = rpm
            gears[size] = gear
            accuracies[size] = accuracy
            sats[size] = satellites
            size++
        }

        private fun grow() {
            cap *= 2
            times = times.copyOf(cap)
            lats = lats.copyOf(cap)
            lons = lons.copyOf(cap)
            alts = alts.copyOf(cap)
            speeds = speeds.copyOf(cap)
            headings = headings.copyOf(cap)
            leans = leans.copyOf(cap)
            rpms = rpms.copyOf(cap)
            gears = gears.copyOf(cap)
            accuracies = accuracies.copyOf(cap)
            sats = sats.copyOf(cap)
        }

        fun toOutput(distanceMeters: Double) = SimOutput(
            timesMillis = times.copyOf(size),
            latitudes = lats.copyOf(size),
            longitudes = lons.copyOf(size),
            altitudesMeters = alts.copyOf(size),
            speedsKph = speeds.copyOf(size),
            headingDegrees = headings.copyOf(size),
            leanDegrees = leans.copyOf(size),
            engineRpm = rpms.copyOf(size),
            gear = gears.copyOf(size),
            accuracyMeters = accuracies.copyOf(size),
            satellites = sats.copyOf(size),
            distanceMeters = distanceMeters,
        )
    }
}
