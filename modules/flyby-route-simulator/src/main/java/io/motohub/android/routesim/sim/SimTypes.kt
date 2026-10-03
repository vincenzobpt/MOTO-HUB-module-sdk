// Shared value types of the route simulator engine. Plain Kotlin, no Android.
package io.motohub.android.routesim.sim

enum class RideStyle { CALM, NORMAL, SPORTY }

enum class TrafficLevel { NONE, LIGHT, HEAVY }

/** Everything the rider can tune. [generic] gives the three built-in styles. */
data class DrivingProfile(
    val style: RideStyle,
    /** Comfortable acceleration, m/s^2 (NORMAL ~2.2). */
    val accelMs2: Double,
    /** Comfortable braking, m/s^2 (NORMAL ~3.0). */
    val brakeMs2: Double,
    /** Cornering budget used for the curve speed limit, m/s^2 (NORMAL ~3.2). */
    val lateralAccelMs2: Double,
    /** Cruise target = limit * factor (CALM 0.85, NORMAL 0.98, SPORTY 1.12). */
    val overLimitFactor: Double,
    /** Cruise target when a segment has no known limit. */
    val defaultCruiseKph: Double,
    /** Hard cap on lean, degrees (NORMAL ~38, SPORTY ~48, CALM ~30). */
    val maxLeanDeg: Double,
    /** Engine idle speed, rpm (~1300). */
    val idleRpm: Double,
    /** Rev limiter, rpm (~9500). */
    val maxRpm: Double,
    /** Upshift point, rpm (CALM 5200, NORMAL 6200, SPORTY 8000). */
    val shiftUpRpm: Double,
    /** Downshift point, rpm (~2800). */
    val shiftDownRpm: Double,
    /** Overall rpm per (m/s) for each gear, index 0 = 1st gear, 6 gears. */
    val gearRatios: DoubleArray,
) {
    companion object {
        /**
         * Overall rpm per m/s of a generic ~800 cc adventure bike: 6th gear at 100 km/h
         * (27.78 m/s) turns 4800 rpm, 1st gear at 40 km/h (11.11 m/s) turns 8500 rpm, and the
         * ratios in between follow a geometric ladder (each gear ~0.743 of the one below).
         */
        val GENERIC_GEAR_RATIOS: DoubleArray = doubleArrayOf(765.0, 568.0, 422.0, 313.0, 233.0, 172.8)

        fun generic(style: RideStyle): DrivingProfile = when (style) {
            RideStyle.CALM -> DrivingProfile(
                style = style,
                accelMs2 = 1.5,
                brakeMs2 = 2.2,
                lateralAccelMs2 = 2.4,
                overLimitFactor = 0.85,
                defaultCruiseKph = 65.0,
                maxLeanDeg = 30.0,
                idleRpm = 1300.0,
                maxRpm = 9500.0,
                shiftUpRpm = 5200.0,
                shiftDownRpm = 2800.0,
                gearRatios = GENERIC_GEAR_RATIOS.copyOf(),
            )
            RideStyle.NORMAL -> DrivingProfile(
                style = style,
                accelMs2 = 2.2,
                brakeMs2 = 3.0,
                lateralAccelMs2 = 3.2,
                overLimitFactor = 0.98,
                defaultCruiseKph = 80.0,
                maxLeanDeg = 38.0,
                idleRpm = 1300.0,
                maxRpm = 9500.0,
                shiftUpRpm = 6200.0,
                shiftDownRpm = 2800.0,
                gearRatios = GENERIC_GEAR_RATIOS.copyOf(),
            )
            RideStyle.SPORTY -> DrivingProfile(
                style = style,
                accelMs2 = 3.2,
                brakeMs2 = 4.2,
                lateralAccelMs2 = 4.6,
                overLimitFactor = 1.12,
                defaultCruiseKph = 95.0,
                maxLeanDeg = 48.0,
                idleRpm = 1300.0,
                maxRpm = 9500.0,
                shiftUpRpm = 8000.0,
                shiftDownRpm = 2800.0,
                gearRatios = GENERIC_GEAR_RATIOS.copyOf(),
            )
        }
    }
}

/** The planned route, already densified by the caller. n points, n-1 segments. */
class SimRoute(
    val lats: DoubleArray,
    val lons: DoubleArray,
    /** Size n, NaN = unknown. */
    val altitudesMeters: DoubleArray,
    /** Size n-1, limit of segment i -> i+1, NaN = none. */
    val speedLimitKph: FloatArray,
)

class SimRequest(
    val route: SimRoute,
    val startedAtMillis: Long,
    val profile: DrivingProfile,
    val traffic: TrafficLevel,
    val seed: Long,
    /** 10 in production. */
    val sampleRateHz: Int,
)

/** Parallel arrays, one slot per sample, ALL the same length. */
class SimOutput(
    /** Relative to the start, strictly increasing. */
    val timesMillis: LongArray,
    /** With GPS noise. */
    val latitudes: DoubleArray,
    val longitudes: DoubleArray,
    /** NaN when unknown. */
    val altitudesMeters: DoubleArray,
    val speedsKph: FloatArray,
    val headingDegrees: FloatArray,
    /** +right, -left. */
    val leanDegrees: FloatArray,
    val engineRpm: FloatArray,
    /** 0 = neutral/stopped, 1..6. */
    val gear: IntArray,
    val accuracyMeters: FloatArray,
    val satellites: IntArray,
    /** True route distance ridden. */
    val distanceMeters: Double,
)
