// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.routesim.core

import io.motohub.android.module.ModuleRideEntry
import io.motohub.android.module.ModuleRouteError
import io.motohub.android.routesim.sim.DrivingProfile
import io.motohub.android.routesim.sim.LatLng
import io.motohub.android.routesim.sim.RideStyle
import io.motohub.android.routesim.sim.SimOutput
import io.motohub.android.routesim.sim.TrafficLevel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RideGeneratorTest {

    private val lats = doubleArrayOf(45.0, 45.0, 45.0)
    private val lons = doubleArrayOf(7.00, 7.025, 7.05)

    private fun settings(style: RideStyle = RideStyle.NORMAL, traffic: TrafficLevel = TrafficLevel.LIGHT) =
        RideSettings(1_700_000_000_000L, style, traffic, DrivingProfile.generic(style))

    private fun prepared(rig: Rig = Rig()): PreparedRoute {
        rig.ok(lats, lons, 3_930.0)
        rig.routing.limits = floatArrayOf(80f, 80f)
        rig.routing.elevation = doubleArrayOf(300.0, 310.0, 305.0)
        rig.places.name(45.0, 7.00, "Alpha")
        rig.places.name(45.0, 7.05, "Beta")
        return (rig.generator.prepare(SHORT_STOPS) {} as Prepare.Ok).route
    }

    // ---- prepare ----

    @Test
    fun prepareDensifiesAndMergesLimitsAndElevations() {
        val rig = Rig()
        val p = prepared(rig)
        val r = p.route
        var max = 0.0
        for (i in 0 until r.lats.size - 1) {
            max = maxOf(max, LatLng(r.lats[i], r.lons[i]).distanceTo(LatLng(r.lats[i + 1], r.lons[i + 1])))
        }
        assertTrue(max <= 25.0)
        assertTrue(r.speedLimitKph.all { it == 80f })
        assertEquals(300.0, r.altitudesMeters.first(), 0.0)
        assertEquals(305.0, r.altitudesMeters.last(), 0.0)
        assertEquals(3_930.0, p.distanceMeters, 0.0)
        assertEquals("Alpha", p.startPlace)
        assertEquals("Beta", p.endPlace)
        assertEquals("Alpha → Beta", p.title)
        assertEquals(io.motohub.android.module.ModuleRoutePreference.FASTEST, rig.routing.lastPreference)
        assertEquals(2, rig.routing.lastRouteLats.size)
        assertEquals(7.05, rig.routing.lastRouteLons[1], 0.0)
    }

    @Test
    fun progressIsReported() {
        val rig = Rig()
        rig.ok(lats, lons, 3_930.0)
        val seen = ArrayList<String>()
        rig.generator.prepare(SHORT_STOPS) { seen += it }
        assertTrue(seen.size >= 3)
        assertTrue(seen.all { it.isNotBlank() })
    }

    @Test
    fun nullSpeedLimitsBecomeNaN() {
        val rig = Rig()
        rig.ok(lats, lons, 3_930.0)
        rig.routing.limits = null
        rig.routing.elevation = doubleArrayOf(1.0, 2.0, 3.0)
        val r = (rig.generator.prepare(SHORT_STOPS) {} as Prepare.Ok).route.route
        assertTrue(r.speedLimitKph.isNotEmpty())
        assertTrue(r.speedLimitKph.all { it.isNaN() })
        assertFalse(r.altitudesMeters.any { it.isNaN() })
    }

    @Test
    fun nullElevationsBecomeNaN() {
        val rig = Rig()
        rig.ok(lats, lons, 3_930.0)
        rig.routing.limits = floatArrayOf(50f, 50f)
        rig.routing.elevation = null
        val r = (rig.generator.prepare(SHORT_STOPS) {} as Prepare.Ok).route.route
        assertTrue(r.altitudesMeters.isNotEmpty())
        assertTrue(r.altitudesMeters.all { it.isNaN() })
        assertTrue(r.speedLimitKph.all { it == 50f })
    }

    @Test
    fun wrongSizedLimitsAndElevationsAreTreatedAsMissing() {
        val rig = Rig()
        rig.ok(lats, lons, 3_930.0)
        rig.routing.limits = floatArrayOf(50f)
        rig.routing.elevation = doubleArrayOf(1.0)
        val r = (rig.generator.prepare(SHORT_STOPS) {} as Prepare.Ok).route.route
        assertTrue(r.speedLimitKph.all { it.isNaN() })
        assertTrue(r.altitudesMeters.all { it.isNaN() })
    }

    @Test
    fun titleFallsBackOnCoordinatesWhenReverseReturnsNull() {
        val rig = Rig()
        rig.ok(lats, lons, 3_930.0)
        val p = (rig.generator.prepare(SHORT_STOPS) {} as Prepare.Ok).route
        assertNull(p.startPlace)
        assertNull(p.endPlace)
        assertEquals("45.0000, 7.0000 → 45.0000, 7.0500", p.title)
    }

    @Test
    fun titleMixesAPlaceAndCoordinates() {
        val rig = Rig()
        rig.ok(lats, lons, 3_930.0)
        rig.places.name(45.0, 7.00, "Alpha")
        val p = (rig.generator.prepare(SHORT_STOPS) {} as Prepare.Ok).route
        assertEquals("Alpha", p.startPlace)
        assertNull(p.endPlace)
        assertEquals("Alpha → 45.0000, 7.0500", p.title)
    }

    @Test
    fun viaStopsAreSentToTheRouter() {
        val rig = Rig()
        rig.ok(lats, lons, 3_930.0)
        val stops = listOf(Stop(45.0, 7.0, "A"), Stop(45.0, 7.02, "V"), Stop(45.0, 7.05, "B"))
        rig.generator.prepare(stops) {}
        assertEquals(3, rig.routing.lastRouteLats.size)
        assertEquals(7.02, rig.routing.lastRouteLons[1], 0.0)
    }

    @Test
    fun needsTwoStops() {
        val rig = Rig()
        val r = rig.generator.prepare(listOf(Stop(45.0, 7.0, "A"))) {}
        assertTrue(r is Prepare.Failed)
        assertEquals(0, rig.routing.routeCalls)
    }

    @Test
    fun routeOver500KmIsRefusedWithAMessage() {
        val rig = Rig()
        rig.ok(lats, lons, 600_000.0)
        val r = rig.generator.prepare(SHORT_STOPS) {} as Prepare.Failed
        assertEquals(ModuleRouteError.TOO_LONG, r.kind)
        assertTrue(r.message, r.message.contains("500 km"))
        assertTrue(r.message, r.message.contains("600"))
    }

    @Test
    fun routeJustUnder500KmPasses() {
        val rig = Rig()
        rig.ok(lats, lons, 499_000.0)
        assertTrue(rig.generator.prepare(SHORT_STOPS) {} is Prepare.Ok)
    }

    @Test
    fun stopsFarApartAreRefusedWithoutAskingTheServer() {
        val rig = Rig()
        val r = rig.generator.prepare(listOf(Stop(45.0, 7.0, "A"), Stop(45.0, 16.0, "B"))) {} as Prepare.Failed
        assertEquals(ModuleRouteError.TOO_LONG, r.kind)
        assertTrue(r.message.contains("500 km"))
        assertEquals(0, rig.routing.routeCalls)
    }

    @Test
    fun routeFailureKindsAreSurfaced() {
        for (kind in listOf(
            ModuleRouteError.NO_NETWORK, ModuleRouteError.RATE_LIMITED, ModuleRouteError.NO_API_KEY,
            ModuleRouteError.TOO_LONG, ModuleRouteError.OTHER,
        )) {
            val rig = Rig()
            rig.fail(kind, "the server said $kind")
            val r = rig.generator.prepare(SHORT_STOPS) {} as Prepare.Failed
            assertEquals(kind, r.kind)
            assertEquals("the server said $kind", r.message)

            // Without a message from the host the module words it itself.
            rig.fail(kind, null)
            val r2 = rig.generator.prepare(SHORT_STOPS) {} as Prepare.Failed
            assertEquals(kind, r2.kind)
            assertTrue(r2.message.isNotBlank())
        }
    }

    @Test
    fun aThrowingRouterIsAFailureNotACrash() {
        val rig = Rig()   // no route result set: the fake throws
        val r = rig.generator.prepare(SHORT_STOPS) {} as Prepare.Failed
        assertEquals(ModuleRouteError.OTHER, r.kind)
    }

    // ---- generate ----

    @Test
    fun generateIsDeterministicPerSeed() {
        val p = prepared()
        val g = Rig().generator
        val a = g.generate(p, settings(), 7L).output
        val b = g.generate(p, settings(), 7L).output
        val c = g.generate(p, settings(), 8L).output
        assertArrayEquals(a.timesMillis, b.timesMillis)
        assertArrayEquals(a.speedsKph, b.speedsKph, 0f)
        assertArrayEquals(a.latitudes, b.latitudes, 0.0)
        assertFalse(a.speedsKph.contentEquals(c.speedsKph) && a.latitudes.contentEquals(c.latitudes))
    }

    @Test
    fun generatedOutputHasMonotonicTimeAndConsistentLengths() {
        val p = prepared()
        val ride = Rig().generator.generate(p, settings(), 1L)
        val o = ride.output
        val n = o.timesMillis.size
        assertTrue(n > 100)
        assertEquals(n, o.latitudes.size)
        assertEquals(n, o.speedsKph.size)
        assertEquals(n, o.engineRpm.size)
        for (i in 1 until n) assertTrue(o.timesMillis[i] > o.timesMillis[i - 1])
        // About 3.9 km at road speeds takes minutes, not seconds.
        assertTrue(o.timesMillis.last() > 60_000L)
        assertEquals(1L, ride.seed)
        assertSame(p, ride.prepared)
    }

    // ---- save ----

    @Test
    fun saveBuildsTheSimulatedRideFromTheOutput() {
        val rig = Rig()
        val p = prepared(rig)
        val ride = rig.generator.generate(p, settings(), 3L)
        val result = rig.generator.save(ride)
        assertEquals("ride-1", (result as Save.Ok).rideId)
        val saved = rig.writer.savedRide!!
        val o = ride.output
        assertEquals("Alpha → Beta", saved.title)
        assertEquals(1_700_000_000_000L, saved.startedAtMillis)
        assertEquals("Alpha", saved.startPlace)
        assertEquals("Beta", saved.endPlace)
        val n = o.timesMillis.size
        for (len in listOf(
            saved.latitudes.size, saved.longitudes.size, saved.altitudesMeters.size, saved.timesMillis.size,
            saved.speedsKph.size, saved.leanDegrees.size, saved.engineRpm.size, saved.accuracyMeters.size,
            saved.satellites.size,
        )) assertEquals(n, len)
        assertArrayEquals(o.timesMillis, saved.timesMillis)
        assertArrayEquals(o.latitudes, saved.latitudes, 0.0)
        assertArrayEquals(o.speedsKph, saved.speedsKph, 0f)
        assertArrayEquals(o.leanDegrees, saved.leanDegrees, 0f)
        assertArrayEquals(o.engineRpm, saved.engineRpm, 0f)
        assertArrayEquals(o.accuracyMeters, saved.accuracyMeters, 0f)
    }

    private fun handmadeOutput(rpm: FloatArray): SimOutput {
        val n = rpm.size
        return SimOutput(
            timesMillis = LongArray(n) { it * 100L },
            latitudes = DoubleArray(n) { 45.0 + it * 1e-5 },
            longitudes = DoubleArray(n) { 7.0 },
            altitudesMeters = DoubleArray(n) { Double.NaN },
            speedsKph = FloatArray(n) { 10f + it },
            headingDegrees = FloatArray(n),
            leanDegrees = FloatArray(n) { it - 1f },
            engineRpm = rpm,
            gear = IntArray(n),
            accuracyMeters = FloatArray(n) { 3f },
            satellites = IntArray(n) { 9 },
            distanceMeters = 100.0,
        )
    }

    @Test
    fun saveKeepsNaNRpmAndAltitudes() {
        val rig = Rig()
        val p = prepared(rig)
        val out = handmadeOutput(floatArrayOf(Float.NaN, 2000f, Float.NaN))
        val result = rig.generator.save(GeneratedRide(p, settings(), 1L, out))
        assertTrue(result is Save.Ok)
        val saved = rig.writer.savedRide!!
        assertTrue(saved.engineRpm[0].isNaN())
        assertEquals(2000f, saved.engineRpm[1], 0f)
        assertTrue(saved.engineRpm[2].isNaN())
        assertTrue(saved.altitudesMeters.all { it.isNaN() })
    }

    @Test
    fun saveFailsWhenTheHostReturnsNoId() {
        val rig = Rig()
        val p = prepared(rig)
        rig.writer.rideId = null
        val result = rig.generator.save(GeneratedRide(p, settings(), 1L, handmadeOutput(floatArrayOf(1f, 2f, 3f))))
        assertTrue(result is Save.Failed)
        assertTrue((result as Save.Failed).message.isNotBlank())
    }

    @Test
    fun saveRefusesInconsistentArrays() {
        val rig = Rig()
        val p = prepared(rig)
        val bad = handmadeOutput(floatArrayOf(1f, 2f))   // 2 samples
        val broken = SimOutput(
            bad.timesMillis, bad.latitudes, bad.longitudes, bad.altitudesMeters, FloatArray(1), bad.headingDegrees,
            bad.leanDegrees, bad.engineRpm, bad.gear, bad.accuracyMeters, bad.satellites, 1.0,
        )
        assertTrue(rig.generator.save(GeneratedRide(p, settings(), 1L, broken)) is Save.Failed)
        assertNull(rig.writer.savedRide)
    }

    // ---- save as route ----

    @Test
    fun savePlanAsRouteHandsTheDensifiedRouteToTheWriter() {
        val rig = Rig()
        val p = prepared(rig)
        val plan = Plan("p1", "Sunday loop", SHORT_STOPS, settings(), 5L)
        assertEquals("route-1", rig.generator.savePlanAsRoute(plan, p))
        val r = rig.writer.savedRoute!!
        assertEquals("Sunday loop", r.title)
        assertEquals(p.route.lats.size, r.latitudes.size)
        assertEquals(p.route.lats.size, r.altitudesMeters.size)
        assertEquals(3_930.0, r.distanceMeters, 0.0)
        assertTrue(r.durationSeconds > 60.0)
        assertEquals("Beta", r.destinationLabel)
    }

    @Test
    fun savePlanAsRouteUsesNoAltitudesWhenNoneAreKnown() {
        val rig = Rig()
        rig.ok(lats, lons, 3_930.0)
        val p = (rig.generator.prepare(SHORT_STOPS) {} as Prepare.Ok).route
        val plan = Plan("p1", " ", SHORT_STOPS, settings(), 5L)
        rig.generator.savePlanAsRoute(plan, p)
        val r = rig.writer.savedRoute!!
        assertEquals(0, r.altitudesMeters.size)
        assertEquals(p.title, r.title)
        assertEquals("B", r.destinationLabel)
    }

    @Test
    fun savePlanAsRouteReturnsNullOnFailure() {
        val rig = Rig()
        val p = prepared(rig)
        rig.writer.routeId = null
        assertNull(rig.generator.savePlanAsRoute(Plan("p", "n", SHORT_STOPS, settings(), 0L), p))
    }

    // ---- Flyby ----

    private fun entry(id: String) = ModuleRideEntry(id, ModuleRideEntry.KIND_RECORDED, "t", 0L, 1.0, 1L)

    @Test
    fun openInFlybyFindsTheEntryById() {
        val rig = Rig()
        rig.bridge.installed += "motohub-flyby"
        val wanted = entry("r2")
        rig.rides.recorded = listOf(entry("r1"), wanted, entry("r3"))
        assertTrue(rig.generator.openInFlyby("r2"))
        assertEquals("motohub-flyby", rig.bridge.openedModule)
        assertEquals("flyby-ride", rig.bridge.openedFeature)
        assertSame(wanted, rig.bridge.openedEntry)
    }

    @Test
    fun openInFlybyIsFalseWhenFlybyIsMissing() {
        val rig = Rig()
        rig.rides.recorded = listOf(entry("r1"))
        assertFalse(rig.generator.flybyInstalled())
        assertFalse(rig.generator.openInFlyby("r1"))
        assertNull(rig.bridge.openedEntry)
    }

    @Test
    fun openInFlybyIsFalseWhenTheEntryIsMissing() {
        val rig = Rig()
        rig.bridge.installed += "motohub-flyby"
        rig.rides.recorded = listOf(entry("r1"))
        assertTrue(rig.generator.flybyInstalled())
        assertFalse(rig.generator.openInFlyby("nope"))
        assertNull(rig.bridge.openedEntry)
    }

    @Test
    fun openInFlybyPassesOnTheHostsRefusal() {
        val rig = Rig()
        rig.bridge.installed += "motohub-flyby"
        rig.bridge.openResult = false
        rig.rides.recorded = listOf(entry("r1"))
        assertFalse(rig.generator.openInFlyby("r1"))
        assertNotNull(rig.bridge.openedEntry)
    }
}
