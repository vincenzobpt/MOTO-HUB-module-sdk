// Derived from MockLocation (MIT, (c) 2026 Vincenzo Buonomano).
package io.motohub.android.routesim.sim

import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Routes the simulator tests share. Built in local metres (x east, y north) near Turin. */
internal object SimTestRoutes {
    const val LAT0 = 45.0
    const val LON0 = 7.0
    private const val M_PER_DEG = 111_194.93

    fun fromMeters(
        xs: List<Double>,
        ys: List<Double>,
        limitKph: Float = Float.NaN,
        altitude: Double = Double.NaN,
        limits: FloatArray? = null,
    ): SimRoute {
        val n = xs.size
        val cosLat = cos(Math.toRadians(LAT0))
        return SimRoute(
            lats = DoubleArray(n) { LAT0 + ys[it] / M_PER_DEG },
            lons = DoubleArray(n) { LON0 + xs[it] / (M_PER_DEG * cosLat) },
            altitudesMeters = DoubleArray(n) { altitude },
            speedLimitKph = limits ?: FloatArray(n - 1) { limitKph },
        )
    }

    fun straightNorth(lengthM: Double, spacingM: Double, limitKph: Float = Float.NaN): SimRoute {
        val n = (lengthM / spacingM).toInt() + 1
        return fromMeters(List(n) { 0.0 }, List(n) { it * spacingM }, limitKph)
    }

    /** North for 500 m, a half circle of [radius], then south again. Right turn unless mirrored. */
    fun hairpin(radius: Double = 15.0, rightTurn: Boolean = true, limitKph: Float = 90f): SimRoute {
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        var y = 0.0
        while (y <= 500.0) {
            xs.add(0.0); ys.add(y); y += 5.0
        }
        val dTheta = 2.0 / radius
        var theta = dTheta
        while (theta < Math.PI) {
            xs.add(radius - radius * cos(theta)); ys.add(500.0 + radius * sin(theta)); theta += dTheta
        }
        xs.add(2.0 * radius); ys.add(500.0)
        y = 495.0
        while (y >= 0.0) {
            xs.add(2.0 * radius); ys.add(y); y -= 5.0
        }
        val sign = if (rightTurn) 1.0 else -1.0
        return fromMeters(xs.map { it * sign }, ys, limitKph)
    }

    /** North with a gentle sinusoidal sway: long and curvy enough to exercise everything. */
    fun wavy(lengthM: Double, spacingM: Double, amplitudeM: Double, wavelengthM: Double, limitKph: Float = Float.NaN): SimRoute {
        val n = (lengthM / spacingM).toInt() + 1
        return fromMeters(
            List(n) { amplitudeM * sin(2.0 * Math.PI * it * spacingM / wavelengthM) },
            List(n) { it * spacingM },
            limitKph,
        )
    }
}

class RouteGeometryTest {

    private fun angleDiff(a: Double, b: Double): Double {
        var d = (a - b) % 360.0
        if (d > 180.0) d -= 360.0
        if (d < -180.0) d += 360.0
        return d
    }

    @Test
    fun totalLengthAndEndpoints() {
        val route = SimTestRoutes.straightNorth(1000.0, 10.0, 90f)
        val geo = RouteGeometry(route)
        assertEquals(1000.0, geo.totalMeters, 3.0)
        assertEquals(route.lats[0], geo.positionAt(0.0).latitude, 1e-9)
        assertEquals(route.lats.last(), geo.positionAt(geo.totalMeters).latitude, 1e-9)
        // Beyond the ends the position clamps.
        assertEquals(route.lats.last(), geo.positionAt(geo.totalMeters + 500.0).latitude, 1e-9)
        assertEquals(route.lats[0], geo.positionAt(-10.0).latitude, 1e-9)
        val mid = geo.positionAt(geo.totalMeters / 2.0)
        assertEquals((route.lats[0] + route.lats.last()) / 2.0, mid.latitude, 1e-6)
    }

    @Test
    fun indexAtIsMonotoneAndBounded() {
        val geo = RouteGeometry(SimTestRoutes.straightNorth(500.0, 10.0))
        var previous = 0
        var d = -20.0
        while (d < 600.0) {
            val i = geo.indexAt(d)
            assertTrue(i >= previous)
            assertTrue(i in 0 until geo.pointCount)
            previous = i
            d += 3.0
        }
        assertEquals(0, geo.indexAt(0.0))
        assertEquals(geo.pointCount - 1, geo.indexAt(geo.totalMeters))
        assertTrue(geo.segmentAt(geo.totalMeters) <= geo.pointCount - 2)
    }

    @Test
    fun headingOnStraightNorthIsZero() {
        val geo = RouteGeometry(SimTestRoutes.straightNorth(500.0, 10.0))
        for (d in listOf(0.0, 3.0, 250.0, 497.0, 500.0)) {
            assertEquals("at $d", 0.0, angleDiff(geo.headingAt(d), 0.0), 0.5)
        }
    }

    @Test
    fun headingTurnsContinuouslyThroughACorner() {
        // 200 m east, then 200 m north: a sharp 90 degree left turn at vertex (200, 0).
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        for (i in 0..40) { xs.add(i * 5.0); ys.add(0.0) }
        for (i in 1..40) { xs.add(200.0); ys.add(i * 5.0) }
        val geo = RouteGeometry(SimTestRoutes.fromMeters(xs, ys))
        assertEquals(90.0, geo.headingAt(100.0), 0.5)
        assertEquals(0.0, angleDiff(geo.headingAt(300.0), 0.0), 0.5)
        var previous = geo.headingAt(150.0)
        var d = 151.0
        while (d <= 250.0) {
            val h = geo.headingAt(d)
            // The raw segment heading would jump by 90 degrees; a ~15 m window turns at most ~6 deg/m.
            assertTrue("jump at $d: $previous -> $h", abs(angleDiff(h, previous)) <= 8.0)
            previous = h
            d += 1.0
        }
    }

    @Test
    fun signedCurvatureIsPositiveForRightTurnsAndNegativeForLeft() {
        val radius = 100.0
        fun arc(sign: Double): RouteGeometry {
            val xs = ArrayList<Double>()
            val ys = ArrayList<Double>()
            var theta = 0.0
            while (theta <= Math.PI * 0.75) {
                xs.add(sign * (radius - radius * cos(theta)))
                ys.add(radius * sin(theta))
                theta += 2.0 / radius
            }
            return RouteGeometry(SimTestRoutes.fromMeters(xs, ys))
        }
        val right = arc(1.0)
        val left = arc(-1.0)
        val d = right.totalMeters / 2.0
        assertEquals(0.01, right.signedCurvatureAt(d), 0.001)
        assertEquals(-0.01, left.signedCurvatureAt(d), 0.001)
        val straight = RouteGeometry(SimTestRoutes.straightNorth(400.0, 5.0))
        assertEquals(0.0, straight.signedCurvatureAt(200.0), 1e-4)
    }

    @Test
    fun curveLimitFollowsRadius() {
        val radius = 100.0
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        var theta = 0.0
        while (theta <= Math.PI) {
            xs.add(radius - radius * cos(theta)); ys.add(radius * sin(theta)); theta += 2.0 / radius
        }
        val geo = RouteGeometry(SimTestRoutes.fromMeters(xs, ys), lateralAccelMs2 = 3.2)
        val expected = Math.sqrt(3.2 * radius)
        assertEquals(expected, geo.curveLimitMsAt(geo.totalMeters / 2.0), expected * 0.05)
    }

    @Test
    fun straightRouteHasNoCurveLimit() {
        val geo = RouteGeometry(SimTestRoutes.straightNorth(2000.0, 10.0))
        assertEquals(RouteGeometry.STRAIGHT_LIMIT_MS, geo.minCurveLimitMs(0.0, geo.totalMeters), 1e-9)
    }

    @Test
    fun denseNoisyPolylineDoesNotProduceFalseCorners() {
        // A straight road sampled every metre with +-5 cm of lateral noise: an index-window
        // curvature would read this as a tight zig-zag; a 25 m distance window must not.
        val rng = Random(7)
        val n = 1500
        val xs = List(n) { 0.05 * rng.nextGaussian() }
        val ys = List(n) { it * 1.0 }
        val geo = RouteGeometry(SimTestRoutes.fromMeters(xs, ys))
        assertTrue(
            "min curve limit ${geo.minCurveLimitMs(0.0, geo.totalMeters)}",
            geo.minCurveLimitMs(30.0, geo.totalMeters - 30.0) > 25.0,
        )
    }

    @Test
    fun hairpinHasALowCurveLimit() {
        val geo = RouteGeometry(SimTestRoutes.hairpin(radius = 15.0), lateralAccelMs2 = 3.2)
        val apex = 500.0 + Math.PI * 15.0 / 2.0
        val limit = geo.minCurveLimitMs(apex - 10.0, apex + 10.0)
        assertEquals(Math.sqrt(3.2 * 15.0), limit, Math.sqrt(3.2 * 15.0) * 0.15)
        assertTrue(geo.curveLimitMsAt(100.0) > 50.0)
    }

    @Test
    fun elevationInterpolatesWhenComplete() {
        val route = SimTestRoutes.straightNorth(1000.0, 10.0)
        val n = route.lats.size
        val withAlt = SimRoute(route.lats, route.lons, DoubleArray(n) { 100.0 + it * 100.0 / (n - 1) }, route.speedLimitKph)
        val geo = RouteGeometry(withAlt)
        assertTrue(geo.hasElevation)
        assertEquals(100.0, geo.elevationAt(0.0), 1e-9)
        assertEquals(150.0, geo.elevationAt(geo.totalMeters / 2.0), 0.5)
        assertEquals(200.0, geo.elevationAt(geo.totalMeters), 1e-9)
    }

    @Test
    fun elevationIsNaNWhenUnknownOrIncomplete() {
        val route = SimTestRoutes.straightNorth(1000.0, 10.0)
        val n = route.lats.size
        val none = RouteGeometry(route)
        assertFalse(none.hasElevation)
        assertTrue(none.elevationAt(500.0).isNaN())

        val partial = DoubleArray(n) { 100.0 }
        partial[n / 2] = Double.NaN
        val geo = RouteGeometry(SimRoute(route.lats, route.lons, partial, route.speedLimitKph))
        assertFalse(geo.hasElevation)
        assertTrue(geo.elevationAt(100.0).isNaN())
    }

    @Test
    fun repeatedPointsDoNotBreakQueries() {
        val xs = listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val ys = listOf(0.0, 0.0, 50.0, 50.0, 100.0, 100.0)
        val geo = RouteGeometry(SimTestRoutes.fromMeters(xs, ys))
        assertEquals(100.0, geo.totalMeters, 0.5)
        for (d in listOf(0.0, 25.0, 50.0, 75.0, 100.0)) {
            val p = geo.positionAt(d)
            assertTrue(p.latitude.isFinite() && p.longitude.isFinite())
            assertTrue(geo.headingAt(d).isFinite())
            assertTrue(geo.signedCurvatureAt(d).isFinite())
        }
    }

    @Test
    fun tooShortARouteIsRejected() {
        try {
            RouteGeometry(SimRoute(doubleArrayOf(45.0), doubleArrayOf(7.0), doubleArrayOf(Double.NaN), FloatArray(0)))
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
    }
}
