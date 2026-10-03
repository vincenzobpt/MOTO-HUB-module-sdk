// Derived from MockLocation (MIT, (c) 2026 Vincenzo Buonomano).
package io.motohub.android.routesim.sim

import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RideSimulatorTest {

    private val dtS = 0.1

    private fun ride(
        route: SimRoute,
        traffic: TrafficLevel = TrafficLevel.NONE,
        seed: Long = 42L,
        style: RideStyle = RideStyle.NORMAL,
    ): SimOutput = RideSimulator.run(
        SimRequest(
            route = route,
            startedAtMillis = 1_700_000_000_000L,
            profile = DrivingProfile.generic(style),
            traffic = traffic,
            seed = seed,
            sampleRateHz = 10,
        ),
    )

    /** Distance along the route at each sample, rebuilt from the recorded speeds. */
    private fun distances(out: SimOutput): DoubleArray {
        val d = DoubleArray(out.speedsKph.size)
        for (i in 1 until d.size) d[i] = d[i - 1] + out.speedsKph[i] / 3.6 * dtS
        return d
    }

    private fun leadingStationary(out: SimOutput): Int = out.speedsKph.indexOfFirst { it > 0f }

    private fun trailingStationary(out: SimOutput): Int = out.speedsKph.size - 1 - out.speedsKph.indexOfLast { it > 0f }

    /** Number of stops strictly between the first move and the last move. */
    private fun midRideStops(out: SimOutput): Int {
        val first = out.speedsKph.indexOfFirst { it >= 5f }
        val last = out.speedsKph.indexOfLast { it >= 5f }
        var stops = 0
        var stopped = false
        for (i in first..last) {
            val nowStopped = out.speedsKph[i] < 0.5f
            if (nowStopped && !stopped) stops++
            stopped = nowStopped
        }
        return stops
    }

    private fun assertParallel(out: SimOutput) {
        val n = out.timesMillis.size
        assertTrue(n > 1)
        assertEquals(n, out.latitudes.size)
        assertEquals(n, out.longitudes.size)
        assertEquals(n, out.altitudesMeters.size)
        assertEquals(n, out.speedsKph.size)
        assertEquals(n, out.headingDegrees.size)
        assertEquals(n, out.leanDegrees.size)
        assertEquals(n, out.engineRpm.size)
        assertEquals(n, out.gear.size)
        assertEquals(n, out.accuracyMeters.size)
        assertEquals(n, out.satellites.size)
    }

    @Test
    fun straightRouteReachesTheCruiseSpeedAndStopsAtTheEnd() {
        val route = SimTestRoutes.straightNorth(2000.0, 5.0, 90f)
        val out = ride(route)
        assertParallel(out)

        val cruise = 90.0 * 0.98
        val maxKph = out.speedsKph.max().toDouble()
        assertTrue("max speed $maxKph", maxKph in (cruise - 2.5)..(cruise + 2.0))

        assertEquals(0f, out.speedsKph.last(), 0f)
        // 20..40 s of idle at 10 Hz on each side (one extra sample for the boundary).
        assertTrue("leading ${leadingStationary(out)}", leadingStationary(out) in 200..402)
        assertTrue("trailing ${trailingStationary(out)}", trailingStationary(out) in 200..402)

        assertEquals(2000.0, out.distanceMeters, 6.0)
        val rebuilt = distances(out).last()
        assertEquals(out.distanceMeters, rebuilt, out.distanceMeters * 0.01)

        // Positions are noisy but stay within a few metres of the ends of the route.
        val metersPerDegLat = 111_194.93
        val startErrorM = abs(out.latitudes.first() - route.lats.first()) * metersPerDegLat
        val endErrorM = abs(out.latitudes.last() - route.lats.last()) * metersPerDegLat
        assertTrue("start error $startErrorM", startErrorM < 25.0)
        assertTrue("end error $endErrorM", endErrorM < 25.0)
    }

    @Test
    fun noTrafficRideNeverStopsInTheMiddle() {
        val out = ride(SimTestRoutes.straightNorth(5000.0, 10.0, 90f))
        assertEquals(0, midRideStops(out))
    }

    @Test
    fun idleHasGearZeroAndIdleRpm() {
        val out = ride(SimTestRoutes.straightNorth(1000.0, 10.0, 70f))
        val idle = DrivingProfile.generic(RideStyle.NORMAL).idleRpm
        for (i in 0 until 150) {
            assertEquals(0, out.gear[i])
            assertEquals(idle, out.engineRpm[i].toDouble(), idle * 0.1)
            assertEquals(0f, out.speedsKph[i], 0f)
        }
        val last = out.engineRpm.size - 1
        assertEquals(0, out.gear[last])
        assertEquals(idle, out.engineRpm[last].toDouble(), idle * 0.1)
        // Somewhere in the ride the bike is in a real gear.
        assertTrue(out.gear.any { it >= 3 })
    }

    @Test
    fun timesStartAtZeroAndStepBy100Ms() {
        val out = ride(SimTestRoutes.straightNorth(1500.0, 10.0, 80f))
        assertEquals(0L, out.timesMillis[0])
        for (i in 1 until out.timesMillis.size) {
            assertEquals(100L, out.timesMillis[i] - out.timesMillis[i - 1])
        }
    }

    @Test
    fun hairpinKeepsToTheCurveSpeedLimit() {
        val profile = DrivingProfile.generic(RideStyle.NORMAL)
        val route = SimTestRoutes.hairpin(radius = 15.0, limitKph = 90f)
        val geo = RouteGeometry(route, profile.lateralAccelMs2)
        val out = ride(route)
        val d = distances(out)

        var minInCurve = Double.MAX_VALUE
        for (i in d.indices) {
            val speedMs = out.speedsKph[i] / 3.6
            // Around the apex, where the limit is at its lowest: at most the curve limit + 5%.
            if (d[i] in 515.0..532.0) {
                val limit = geo.minCurveLimitMs(d[i] - 3.0, d[i] + 3.0)
                assertTrue("sample $i at ${d[i]} m: $speedMs m/s > limit $limit", speedMs <= limit * 1.05 + 0.15)
                minInCurve = minOf(minInCurve, out.speedsKph[i].toDouble())
            }
            // Everywhere: the bike can still brake to every curve limit ahead at its comfortable rate.
            var k = Math.floor(d[i] / RouteGeometry.GRID_STEP_M).toInt()
            while (k < geo.gridCount && k < Math.floor(d[i] / RouteGeometry.GRID_STEP_M).toInt() + 60) {
                val ds = maxOf(0.0, k * RouteGeometry.GRID_STEP_M - d[i])
                val reachable = Math.sqrt(geo.curveLimitCellMs(k).let { it * it } + 2.0 * profile.brakeMs2 * ds)
                assertTrue("sample $i at ${d[i]} m: $speedMs m/s, cell $k reachable $reachable", speedMs <= reachable * 1.05 + 0.2)
                k++
            }
        }
        // It really did slow right down for the hairpin, and was fast on the straight before it.
        assertTrue("speed in the hairpin $minInCurve", minInCurve < 32.0)
        assertTrue(out.speedsKph.max() > 60f)
        assertEquals(0f, out.speedsKph.last(), 0f)
    }

    @Test
    fun leanSignFollowsTheTurnDirection() {
        val right = ride(SimTestRoutes.hairpin(rightTurn = true))
        val left = ride(SimTestRoutes.hairpin(rightTurn = false))
        assertTrue("right turn max lean ${right.leanDegrees.max()}", right.leanDegrees.max() > 8f)
        assertTrue("right turn min lean ${right.leanDegrees.min()}", right.leanDegrees.min() > -8f)
        assertTrue("left turn min lean ${left.leanDegrees.min()}", left.leanDegrees.min() < -8f)
        assertTrue("left turn max lean ${left.leanDegrees.max()}", left.leanDegrees.max() < 8f)
        // Stopped: upright.
        for (i in 0 until 100) assertEquals(0f, right.leanDegrees[i], 1.0f)
    }

    @Test
    fun speedLimitChangesAreRespectedAndBrakedFor() {
        // 1.5 km at 90 then 1.5 km at 50.
        val n = 301
        val xs = List(n) { 0.0 }
        val ys = List(n) { it * 10.0 }
        val limits = FloatArray(n - 1) { if (it < 150) 90f else 50f }
        val route = SimTestRoutes.fromMeters(xs, ys, limits = limits)
        val out = ride(route)
        val d = distances(out)
        val slow = 50.0 * 0.98
        for (i in d.indices) {
            if (d[i] >= 1505.0) assertTrue("at ${d[i]}: ${out.speedsKph[i]}", out.speedsKph[i] <= slow + 2.0)
        }
        assertTrue(out.speedsKph.max() > 80f)
    }

    @Test
    fun unknownLimitUsesTheDefaultCruiseSpeed() {
        val profile = DrivingProfile.generic(RideStyle.NORMAL)
        val out = ride(SimTestRoutes.straightNorth(3000.0, 10.0, Float.NaN))
        val maxKph = out.speedsKph.max().toDouble()
        assertTrue("max $maxKph", maxKph in (profile.defaultCruiseKph - 2.5)..(profile.defaultCruiseKph + 2.0))
    }

    @Test
    fun sameSeedGivesIdenticalOutputAndAnotherSeedDoesNot() {
        val route = SimTestRoutes.wavy(8000.0, 10.0, 200.0, 2500.0, 80f)
        val a = ride(route, TrafficLevel.LIGHT, seed = 99L)
        val b = ride(route, TrafficLevel.LIGHT, seed = 99L)
        val c = ride(route, TrafficLevel.LIGHT, seed = 100L)

        assertArrayEquals(a.timesMillis, b.timesMillis)
        assertArrayEquals(a.latitudes, b.latitudes, 0.0)
        assertArrayEquals(a.longitudes, b.longitudes, 0.0)
        assertArrayEquals(a.speedsKph, b.speedsKph, 0f)
        assertArrayEquals(a.leanDegrees, b.leanDegrees, 0f)
        assertArrayEquals(a.engineRpm, b.engineRpm, 0f)
        assertArrayEquals(a.gear, b.gear)
        assertArrayEquals(a.satellites, b.satellites)
        assertEquals(a.distanceMeters, b.distanceMeters, 0.0)

        val identical = a.timesMillis.size == c.timesMillis.size &&
            a.latitudes.contentEquals(c.latitudes) && a.speedsKph.contentEquals(c.speedsKph)
        assertFalse(identical)
        assertNotEquals(a.latitudes.size, 0)
    }

    @Test
    fun heavyTrafficTakesLongerAndStops() {
        val route = SimTestRoutes.straightNorth(20_000.0, 10.0, 90f)
        val none = ride(route, TrafficLevel.NONE)
        val heavy = ride(route, TrafficLevel.HEAVY)
        assertParallel(heavy)

        // Same seed, same idle durations: the whole difference is the traffic.
        assertTrue("none ${none.timesMillis.size} heavy ${heavy.timesMillis.size}", heavy.timesMillis.size > none.timesMillis.size + 100)
        assertEquals(0, midRideStops(none))
        assertTrue("stops ${midRideStops(heavy)}", midRideStops(heavy) >= 1)
        assertEquals(0f, heavy.speedsKph.last(), 0f)
        assertEquals(20_000.0, heavy.distanceMeters, 30.0)
    }

    @Test
    fun overtakingCanExceedTheTargetButNeverTheCurveLimit() {
        // 100 km/h limit, lots of overtaking opportunities: some burst above 98 km/h, but the
        // plan must stay sane (nothing above target + 30 km/h + margin).
        val route = SimTestRoutes.straightNorth(60_000.0, 10.0, 100f)
        val out = ride(route, TrafficLevel.HEAVY, seed = 5L)
        assertTrue(out.speedsKph.max() < 98f + 31f)
    }

    @Test
    fun stylesDifferInPace() {
        val route = SimTestRoutes.wavy(10_000.0, 10.0, 150.0, 1500.0, 80f)
        val calm = ride(route, style = RideStyle.CALM)
        val sporty = ride(route, style = RideStyle.SPORTY)
        assertTrue("calm ${calm.timesMillis.size} sporty ${sporty.timesMillis.size}", calm.timesMillis.size > sporty.timesMillis.size)
        assertTrue(sporty.speedsKph.max() > calm.speedsKph.max())
    }

    @Test
    fun elevationIsNaNWhenTheRouteHasNone() {
        val route = SimTestRoutes.straightNorth(1000.0, 10.0, 80f) // altitudes are all NaN
        val out = ride(route)
        assertTrue(out.altitudesMeters.all { it.isNaN() })
    }

    @Test
    fun elevationIsInterpolatedWhenComplete() {
        val flat = SimTestRoutes.straightNorth(1000.0, 10.0, 80f)
        val n = flat.lats.size
        val route = SimRoute(flat.lats, flat.lons, DoubleArray(n) { 300.0 + it * 100.0 / (n - 1) }, flat.speedLimitKph)
        val out = ride(route)
        assertTrue(out.altitudesMeters.all { it.isFinite() })
        assertEquals(300.0, out.altitudesMeters.first(), 1e-6)
        assertEquals(400.0, out.altitudesMeters.last(), 1e-6)
        for (i in 1 until out.altitudesMeters.size) {
            assertTrue(out.altitudesMeters[i] >= out.altitudesMeters[i - 1] - 1e-9)
        }
    }

    @Test
    fun everyChannelIsFiniteAndInRange() {
        val route = SimTestRoutes.wavy(12_000.0, 10.0, 250.0, 1200.0, 70f)
        val profile = DrivingProfile.generic(RideStyle.SPORTY)
        val out = ride(route, TrafficLevel.HEAVY, seed = 3L, style = RideStyle.SPORTY)
        assertParallel(out)
        for (i in out.timesMillis.indices) {
            assertTrue(out.latitudes[i].isFinite() && out.longitudes[i].isFinite())
            assertTrue("speed $i", out.speedsKph[i].isFinite() && out.speedsKph[i] >= 0f)
            assertTrue("heading $i", out.headingDegrees[i].isFinite() && out.headingDegrees[i] in 0f..360f)
            assertTrue("lean $i", out.leanDegrees[i].isFinite() && abs(out.leanDegrees[i]) <= profile.maxLeanDeg + 1e-3)
            assertTrue("rpm $i", out.engineRpm[i].isFinite() && out.engineRpm[i] in 0f..(profile.maxRpm.toFloat() + 1f))
            assertTrue("gear $i", out.gear[i] in 0..6)
            assertTrue("accuracy $i", out.accuracyMeters[i].isFinite() && out.accuracyMeters[i] > 0f)
            assertTrue("satellites $i", out.satellites[i] > 0)
        }
    }

    @Test
    fun aHundredKilometreRouteRunsFast() {
        val route = SimTestRoutes.wavy(100_000.0, 10.0, 400.0, 6000.0, 90f)
        val started = System.nanoTime()
        val out = ride(route, TrafficLevel.LIGHT, seed = 11L)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        assertParallel(out)
        assertTrue("took $elapsedMs ms", elapsedMs < 5_000L)
        assertEquals(RouteGeometry(route).totalMeters, out.distanceMeters, 5.0)
        assertTrue(out.timesMillis.size > 30_000)
    }

    @Test
    fun aVeryShortRouteStillProducesAnIdleOnlyOrShortRide() {
        val route = SimTestRoutes.straightNorth(30.0, 5.0, 50f)
        val out = ride(route)
        assertParallel(out)
        assertEquals(0f, out.speedsKph.last(), 0f)
        assertEquals(30.0, out.distanceMeters, 1.0)
    }

    @Test
    fun otherSampleRatesKeepStrictlyIncreasingTimes() {
        val route = SimTestRoutes.straightNorth(1000.0, 10.0, 80f)
        for (hz in listOf(1, 4, 25, 30)) {
            val out = RideSimulator.run(
                SimRequest(route, 0L, DrivingProfile.generic(RideStyle.NORMAL), TrafficLevel.NONE, 1L, hz),
            )
            assertEquals(0L, out.timesMillis[0])
            for (i in 1 until out.timesMillis.size) assertTrue(out.timesMillis[i] > out.timesMillis[i - 1])
            assertEquals(1000.0, out.distanceMeters, 5.0)
        }
    }

    @Test
    fun genericProfilesGearTheBikeAsDocumented() {
        for (style in RideStyle.values()) {
            val p = DrivingProfile.generic(style)
            assertEquals(6, p.gearRatios.size)
            // 6th gear at 100 km/h ~ 4800 rpm, 1st gear at 40 km/h ~ 8500 rpm.
            assertEquals(4800.0, 100.0 / 3.6 * p.gearRatios[5], 100.0)
            assertEquals(8500.0, 40.0 / 3.6 * p.gearRatios[0], 100.0)
            for (g in 1 until 6) assertTrue(p.gearRatios[g] < p.gearRatios[g - 1])
        }
        assertTrue(DrivingProfile.generic(RideStyle.CALM).overLimitFactor < DrivingProfile.generic(RideStyle.NORMAL).overLimitFactor)
        assertTrue(DrivingProfile.generic(RideStyle.NORMAL).overLimitFactor < DrivingProfile.generic(RideStyle.SPORTY).overLimitFactor)
    }
}
