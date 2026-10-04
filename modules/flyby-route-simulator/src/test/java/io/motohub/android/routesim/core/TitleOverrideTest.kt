// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.routesim.core

import io.motohub.android.routesim.sim.DrivingProfile
import io.motohub.android.routesim.sim.RideStyle
import io.motohub.android.routesim.sim.TrafficLevel
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A ride made from an experience carries the experience's name, from the planner to TRIPS. */
class TitleOverrideTest {

    private val lats = doubleArrayOf(45.0, 45.0, 45.0)
    private val lons = doubleArrayOf(7.00, 7.025, 7.05)
    private lateinit var dir: File

    @Before fun setUp() { dir = Files.createTempDirectory("titles").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    private fun rig(): Rig {
        val rig = Rig()
        rig.ok(lats, lons, 3_930.0)
        rig.routing.limits = floatArrayOf(80f, 80f)
        rig.routing.elevation = doubleArrayOf(300.0, 310.0, 305.0)
        rig.places.name(45.0, 7.00, "Alpha")
        rig.places.name(45.0, 7.05, "Beta")
        return rig
    }

    private fun settings() = RideSettings(
        1_700_000_000_000L, RideStyle.NORMAL, TrafficLevel.LIGHT, DrivingProfile.generic(RideStyle.NORMAL),
    )

    @Test
    fun theGeneratedRideCarriesTheExperiencesName() {
        val rig = rig()
        val prepared = (rig.generator.prepare(SHORT_STOPS, "Dolomiti Classic") {} as Prepare.Ok).route
        assertEquals("Dolomiti Classic", prepared.title)
        assertEquals("Alpha → Beta", prepared.defaultTitle)
        // The places of both ends are still named, whatever the ride is called.
        assertEquals("Alpha", prepared.startPlace)
        assertEquals("Beta", prepared.endPlace)

        val ride = rig.generator.generate(prepared, settings(), 7L)
        assertEquals("Dolomiti Classic", ride.prepared.title)
        assertTrue(rig.generator.save(ride) is Save.Ok)
        val saved = rig.writer.savedRide
        assertNotNull(saved)
        assertEquals("Dolomiti Classic", saved!!.title)
        assertEquals("Alpha", saved.startPlace)
        assertEquals("Beta", saved.endPlace)
    }

    @Test
    fun withoutAnOverrideTheTitleIsAToB() {
        val rig = rig()
        for (none in listOf(null, "", "   ")) {
            val p = (rig.generator.prepare(SHORT_STOPS, none) {} as Prepare.Ok).route
            assertEquals("Alpha → Beta", p.title)
            assertEquals(p.title, p.defaultTitle)
        }
        // The old call, with the progress callback alone, is unchanged.
        assertEquals("Alpha → Beta", (rig.generator.prepare(SHORT_STOPS) {} as Prepare.Ok).route.title)
    }

    @Test
    fun theNameIsTrimmed() {
        val p = (rig().generator.prepare(SHORT_STOPS, "  Tre Cime loop \n") {} as Prepare.Ok).route
        assertEquals("Tre Cime loop", p.title)
    }

    @Test
    fun aRouteAlreadyPlannedTakesAnotherTitleWithoutPlanningAgain() {
        val rig = rig()
        val p = (rig.generator.prepare(SHORT_STOPS) {} as Prepare.Ok).route
        val calls = rig.routing.routeCalls
        val titled = p.withTitle("Grand Tour")
        assertEquals("Grand Tour", titled.title)
        assertEquals("Alpha → Beta", titled.defaultTitle)
        assertSame(p.route, titled.route)
        assertEquals(p.distanceMeters, titled.distanceMeters, 0.0)
        // And back: a blank title is the default again.
        assertEquals("Alpha → Beta", titled.withTitle(null).title)
        assertEquals("Alpha → Beta", titled.withTitle(" ").title)
        assertEquals(calls, rig.routing.routeCalls)
        // A route that was planned with an override can be given the default back.
        val named = (rig.generator.prepare(SHORT_STOPS, "Named") {} as Prepare.Ok).route
        assertEquals("Alpha → Beta", named.withTitle(null).title)
    }

    @Test
    fun aSavedPlanKeepsTheTitle() {
        val store = PlanStore(dir)
        val named = Plan("p1", "Dolomiti Classic", SHORT_STOPS, settings(), 5L, "Dolomiti Classic")
        val plain = Plan("p2", "By hand", SHORT_STOPS, settings(), 6L)
        store.save(named)
        store.save(plain)
        val back = PlanStore(dir).all().associateBy { it.id }
        assertEquals("Dolomiti Classic", back.getValue("p1").titleOverride)
        assertNull(back.getValue("p2").titleOverride)
        assertEquals("Dolomiti Classic", back.getValue("p1").name)
    }

    @Test
    fun aPlanSavedBeforeTheOverrideStillOpens() {
        val old = """{"version":1,"plans":[{"id":"p","name":"Old","savedAtMillis":1,"stops":[{"lat":45.0,"lon":7.0,"label":"A"},{"lat":45.0,"lon":7.05,"label":"B"}],
            "settings":{"startAtMillis":1700000000000,"style":"NORMAL","traffic":"LIGHT"}}]}"""
        File(dir, "plans.json").writeText(old)
        val plan = PlanStore(dir).all().single()
        assertEquals("Old", plan.name)
        assertNull(plan.titleOverride)
    }

    @Test
    fun aBlankOverrideIsNotStored() {
        PlanStore(dir).save(Plan("p", "x", SHORT_STOPS, settings(), 1L, "  "))
        assertNull(PlanStore(dir).all().single().titleOverride)
        assertTrue(!File(dir, "plans.json").readText().contains("titleOverride"))
    }

    @Test
    fun theTitleOfAnOpenedPlanReachesTheRoute() {
        // What the planner does for a plan it opened: stops and override from the plan, one prepare.
        val rig = rig()
        val plan = Plan("p1", "Dolomiti Classic", SHORT_STOPS, settings(), 5L, "Dolomiti Classic")
        val prepared = (rig.generator.prepare(plan.stops, plan.titleOverride) {} as Prepare.Ok).route
        assertEquals("Dolomiti Classic", prepared.title)
        assertNotNull(rig.generator.savePlanAsRoute(plan, prepared))
        assertEquals("Dolomiti Classic", rig.writer.savedRoute!!.title)
    }

    @Test
    fun cleanTitleOverrideTrimsAndDropsBlanks() {
        assertEquals("A", cleanTitleOverride(" A "))
        assertNull(cleanTitleOverride(""))
        assertNull(cleanTitleOverride("  "))
        assertNull(cleanTitleOverride(null))
    }
}
