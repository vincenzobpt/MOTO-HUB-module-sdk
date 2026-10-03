// Derived from MockLocation (MIT, (c) 2026 Vincenzo Buonomano).
// Arc-length parameterisation of a route for the simulator: position, smoothed heading, signed
// curvature, curve speed limits and elevation, all as functions of metres travelled.
package io.motohub.android.routesim.sim

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A geographic coordinate. */
data class LatLng(val latitude: Double, val longitude: Double) {
    /** Approximate distance in metres (haversine). */
    fun distanceTo(other: LatLng): Double {
        val dLat = Math.toRadians(other.latitude - latitude)
        val dLon = Math.toRadians(other.longitude - longitude)
        val sLat = Math.sin(dLat / 2)
        val sLon = Math.sin(dLon / 2)
        val a = sLat * sLat +
            Math.cos(Math.toRadians(latitude)) * Math.cos(Math.toRadians(other.latitude)) * sLon * sLon
        return 2.0 * RouteGeometry.EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1.0 - a))
    }

    /** Linear interpolation toward [other] by [t] (0..1). */
    fun interpolateTo(other: LatLng, t: Double): LatLng =
        LatLng(latitude + (other.latitude - latitude) * t, longitude + (other.longitude - longitude) * t)

    /** Initial bearing in degrees [0, 360) from this point to [other]. */
    fun bearingTo(other: LatLng): Float {
        val dLon = Math.toRadians(other.longitude - longitude)
        val lat1 = Math.toRadians(latitude)
        val lat2 = Math.toRadians(other.latitude)
        val y = Math.sin(dLon) * Math.cos(lat2)
        val x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon)
        return ((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0).toFloat()
    }
}

/**
 * Pre-computed arc-length parameterisation of a route.
 *
 * The simulation advances one scalar, metres travelled, and asks this class for everything
 * that depends on where on the route the rider is. Not thread-safe (it keeps scratch buffers).
 *
 * Differences from the MockLocation original: the heading is a chord bearing over a ~15 m
 * window instead of a step per vertex; the curve speed limit comes from a Menger radius over a
 * window of at least 25 m each side, sampled on a fixed 5 m grid, so dense polylines do not
 * produce noise and sparse ones do not hide corners; and the signed curvature (needed by the
 * lean model) is the turn rate of the smoothed heading over a 20 m window.
 *
 * @param lateralAccelMs2 cornering budget behind the curve speed limit, v = sqrt(a * R).
 */
class RouteGeometry(route: SimRoute, private val lateralAccelMs2: Double = DEFAULT_LATERAL_ACCEL_MS2) {

    private val lats: DoubleArray = route.lats
    private val lons: DoubleArray = route.lons

    /** Number of route points. */
    val pointCount: Int = lats.size

    /** cumulative[i] = metres from the start of the route to point i. */
    private val cumulative = DoubleArray(pointCount)

    /** Total length in metres. */
    val totalMeters: Double

    /** Altitudes when every one of them is finite and there is one per point, else null. */
    private val elevations: DoubleArray? =
        route.altitudesMeters.takeIf { alt -> alt.size == pointCount && pointCount > 0 && alt.all { it.isFinite() } }

    val hasElevation: Boolean get() = elevations != null

    /** Number of grid cells; cell k sits at k * [GRID_STEP_M] metres. */
    val gridCount: Int

    /** Curve speed ceiling in m/s at each grid cell. */
    private val curveLimits: DoubleArray

    private val scratchA = DoubleArray(2)
    private val scratchB = DoubleArray(2)

    init {
        require(lats.size == lons.size) { "lats and lons differ in size" }
        require(pointCount >= 2) { "a route needs at least 2 points" }
        var acc = 0.0
        for (i in 1 until pointCount) {
            acc += segmentLength(i - 1)
            cumulative[i] = acc
        }
        totalMeters = acc
        gridCount = floor(totalMeters / GRID_STEP_M).toInt() + 2
        curveLimits = DoubleArray(gridCount) { STRAIGHT_LIMIT_MS }
        computeCurveLimits()
    }

    val isUsable: Boolean get() = totalMeters > 1.0

    // -- Queries ---------------------------------------------------------------------------

    /** Index of the last vertex at or before [meters] (clamped to the route). */
    fun indexAt(meters: Double): Int {
        val d = meters.coerceIn(0.0, totalMeters)
        var lo = 0
        var hi = pointCount - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (cumulative[mid] <= d) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Index of the segment (0..n-2) that contains [meters]. */
    fun segmentAt(meters: Double): Int = min(indexAt(meters), pointCount - 2)

    /** Interpolated position at [meters] along the route. */
    fun positionAt(meters: Double): LatLng {
        positionInto(meters, scratchA)
        return LatLng(scratchA[0], scratchA[1])
    }

    /** Writes the position at [meters] as out[0] = latitude, out[1] = longitude. */
    fun positionInto(meters: Double, out: DoubleArray) {
        val d = meters.coerceIn(0.0, totalMeters)
        val i = indexAt(d)
        if (i >= pointCount - 1) {
            out[0] = lats[pointCount - 1]
            out[1] = lons[pointCount - 1]
            return
        }
        val segLen = cumulative[i + 1] - cumulative[i]
        val t = if (segLen > 0.0) (d - cumulative[i]) / segLen else 0.0
        out[0] = lats[i] + (lats[i + 1] - lats[i]) * t
        out[1] = lons[i] + (lons[i + 1] - lons[i]) * t
    }

    /**
     * Heading in degrees [0, 360) at [meters]: the bearing of the chord across a ~15 m window
     * centred there (shifted inwards near the ends), so it turns continuously through a vertex.
     */
    fun headingAt(meters: Double): Double {
        val d = meters.coerceIn(0.0, totalMeters)
        if (totalMeters < 1.0) return 0.0
        var lo = d - HEADING_WINDOW_M / 2.0
        var hi = d + HEADING_WINDOW_M / 2.0
        if (lo < 0.0) {
            lo = 0.0
            hi = min(totalMeters, HEADING_WINDOW_M)
        } else if (hi > totalMeters) {
            hi = totalMeters
            lo = max(0.0, totalMeters - HEADING_WINDOW_M)
        }
        positionInto(lo, scratchA)
        positionInto(hi, scratchB)
        val chord = planarDistance(scratchA[0], scratchA[1], scratchB[0], scratchB[1])
        if (chord >= MIN_CHORD_M) {
            return bearing(scratchA[0], scratchA[1], scratchB[0], scratchB[1])
        }
        // Degenerate chord (a perfect U-turn or a stalled polyline): fall back to the segment.
        val i = segmentAt(d)
        return bearing(lats[i], lons[i], lats[i + 1], lons[i + 1])
    }

    /**
     * Signed curvature 1/R in 1/m at [meters], + = turning right: the change of the smoothed
     * heading over a 20 m window. Limited to +-0.25 (R = 4 m).
     */
    fun signedCurvatureAt(meters: Double): Double {
        if (totalMeters < 4.0) return 0.0
        val d = meters.coerceIn(0.0, totalMeters)
        val lo = max(0.0, d - CURVATURE_HALF_WINDOW_M)
        val hi = min(totalMeters, d + CURVATURE_HALF_WINDOW_M)
        val span = hi - lo
        if (span < 4.0) return 0.0
        var dh = headingAt(hi) - headingAt(lo)
        while (dh > 180.0) dh -= 360.0
        while (dh < -180.0) dh += 360.0
        return (Math.toRadians(dh) / span).coerceIn(-MAX_CURVATURE, MAX_CURVATURE)
    }

    /** Altitude by arc length, or NaN when the route has no complete elevation profile. */
    fun elevationAt(meters: Double): Double {
        val e = elevations ?: return Double.NaN
        val d = meters.coerceIn(0.0, totalMeters)
        val i = indexAt(d)
        if (i >= pointCount - 1) return e[pointCount - 1]
        val segLen = cumulative[i + 1] - cumulative[i]
        val t = if (segLen > 0.0) (d - cumulative[i]) / segLen else 0.0
        return e[i] * (1.0 - t) + e[i + 1] * t
    }

    /** Curve speed ceiling in m/s of grid cell [k] (cell k is at k * [GRID_STEP_M] metres). */
    fun curveLimitCellMs(k: Int): Double = curveLimits[k.coerceIn(0, gridCount - 1)]

    /** Curve speed ceiling in m/s around [meters] (the lower of the two neighbouring cells). */
    fun curveLimitMsAt(meters: Double): Double {
        val x = meters.coerceIn(0.0, totalMeters) / GRID_STEP_M
        val k = floor(x).toInt()
        return min(curveLimitCellMs(k), curveLimitCellMs(k + 1))
    }

    /** Lowest curve speed ceiling in m/s between [fromMeters] and [toMeters]. */
    fun minCurveLimitMs(fromMeters: Double, toMeters: Double): Double {
        val a = floor(min(fromMeters, toMeters).coerceIn(0.0, totalMeters) / GRID_STEP_M).toInt()
        val b = floor(max(fromMeters, toMeters).coerceIn(0.0, totalMeters) / GRID_STEP_M).toInt() + 1
        var m = Double.MAX_VALUE
        for (k in a..b) m = min(m, curveLimitCellMs(k))
        return m
    }

    // -- Curve limits ----------------------------------------------------------------------

    /**
     * Turn radius from the route points [w] metres behind, at, and [w] metres ahead of each grid
     * cell (Menger curvature, i.e. the circumradius of the three points), then
     * v = sqrt(a_lat * R). Sampling by distance rather than vertex index is what keeps this
     * stable on polylines with wildly uneven vertex spacing.
     */
    private fun computeCurveLimits() {
        if (pointCount < 3 || totalMeters < 2.0 * MIN_CURVE_WINDOW_M) return
        val pa = DoubleArray(2)
        val pb = DoubleArray(2)
        val pc = DoubleArray(2)
        for (k in 0 until gridCount) {
            val s = min(k * GRID_STEP_M, totalMeters)
            val w = min(CURVE_WINDOW_M, min(s, totalMeters - s))
            if (w < MIN_CURVE_WINDOW_M) continue
            positionInto(s - w, pa)
            positionInto(s, pb)
            positionInto(s + w, pc)
            val radius = circumRadius(pa, pb, pc)
            if (!radius.isFinite()) continue
            val v = sqrt(lateralAccelMs2 * radius)
            curveLimits[k] = v.coerceIn(MIN_CURVE_LIMIT_MS, STRAIGHT_LIMIT_MS)
        }
    }

    /** Circumradius in metres of three nearly collinear-safe points; +Infinity when straight. */
    private fun circumRadius(a: DoubleArray, b: DoubleArray, c: DoubleArray): Double {
        val cosLat = cos(Math.toRadians(b[0])).coerceAtLeast(0.05)
        val ax = Math.toRadians(a[1] - b[1]) * cosLat * EARTH_RADIUS_M
        val ay = Math.toRadians(a[0] - b[0]) * EARTH_RADIUS_M
        val cx = Math.toRadians(c[1] - b[1]) * cosLat * EARTH_RADIUS_M
        val cy = Math.toRadians(c[0] - b[0]) * EARTH_RADIUS_M
        val ab = sqrt(ax * ax + ay * ay)
        val bc = sqrt(cx * cx + cy * cy)
        val ac = sqrt((cx - ax) * (cx - ax) + (cy - ay) * (cy - ay))
        if (ab < 1.0 || bc < 1.0 || ac < 1.0) return Double.POSITIVE_INFINITY
        val cross2 = abs(ax * cy - ay * cx) // twice the triangle area
        if (cross2 < 1e-6) return Double.POSITIVE_INFINITY
        return (ab * bc * ac) / (2.0 * cross2)
    }

    // -- Helpers ---------------------------------------------------------------------------

    private fun segmentLength(i: Int): Double = planarDistance(lats[i], lons[i], lats[i + 1], lons[i + 1])

    private fun planarDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val midLat = Math.toRadians((lat1 + lat2) / 2.0)
        val dx = Math.toRadians(lon2 - lon1) * cos(midLat)
        val dy = Math.toRadians(lat2 - lat1)
        return sqrt(dx * dx + dy * dy) * EARTH_RADIUS_M
    }

    private fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val midLat = Math.toRadians((lat1 + lat2) / 2.0)
        val dx = Math.toRadians(lon2 - lon1) * cos(midLat)
        val dy = Math.toRadians(lat2 - lat1)
        val deg = Math.toDegrees(atan2(dx, dy))
        return (deg + 360.0) % 360.0
    }

    companion object {
        const val EARTH_RADIUS_M = 6_371_000.0
        const val DEFAULT_LATERAL_ACCEL_MS2 = 3.2

        /** Spacing of the curve-limit grid. */
        const val GRID_STEP_M = 5.0

        /** Window of the heading chord. */
        const val HEADING_WINDOW_M = 15.0

        /** The curvature is the heading change between d - this and d + this. */
        const val CURVATURE_HALF_WINDOW_M = 10.0
        const val MAX_CURVATURE = 0.25

        /** Menger window each side of a grid cell. */
        const val CURVE_WINDOW_M = 25.0

        /** Smallest shrunken window (near the ends of the route) still trusted for a radius. */
        const val MIN_CURVE_WINDOW_M = 10.0

        const val MIN_CURVE_LIMIT_MS = 8.0 / 3.6
        const val STRAIGHT_LIMIT_MS = 100.0
        private const val MIN_CHORD_M = 0.3
    }
}
