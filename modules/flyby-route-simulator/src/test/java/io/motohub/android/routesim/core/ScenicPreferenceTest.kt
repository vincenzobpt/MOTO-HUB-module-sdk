// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.routesim.core

import io.motohub.android.module.ModuleRoutePreference
import io.motohub.android.routesim.sim.DrivingProfile
import io.motohub.android.routesim.sim.RideStyle
import io.motohub.android.routesim.sim.TrafficLevel
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** An experience's ride is routed scenic, like its card; the preference travels with the plan and the prepared route. */
class ScenicPreferenceTest {

    private lateinit var dir: File

    @Before fun setUp() { dir = Files.createTempDirectory("plans").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    private fun settings() = RideSettings(1_700_000_000_000L, RideStyle.NORMAL, TrafficLevel.LIGHT, DrivingProfile.generic(RideStyle.NORMAL))

    private fun rig(): Rig {
        val rig = Rig()
        rig.ok(doubleArrayOf(45.0, 45.0, 45.0), doubleArrayOf(7.00, 7.025, 7.05), 3_930.0)
        return rig
    }

    @Test
    fun prepareDefaultsToFastest() {
        val rig = rig()
        val p = (rig.generator.prepare(SHORT_STOPS) {} as Prepare.Ok).route
        assertEquals(ModuleRoutePreference.FASTEST, rig.routing.lastPreference)
        assertEquals(ModuleRoutePreference.FASTEST, p.preference)
    }

    @Test
    fun theScenicPreferenceReachesTheHostRouting() {
        val rig = rig()
        val p = (rig.generator.prepare(SHORT_STOPS, "Dolomiti Classic", ModuleRoutePreference.SCENIC) {} as Prepare.Ok).route
        assertEquals(ModuleRoutePreference.SCENIC, rig.routing.lastPreference)
        assertEquals(ModuleRoutePreference.SCENIC, p.preference)
        assertEquals("Dolomiti Classic", p.title)
    }

    @Test
    fun theTrailingLambdaStillWorksWithNamedPreference() {
        val rig = rig()
        val seen = ArrayList<String>()
        rig.generator.prepare(SHORT_STOPS, preference = ModuleRoutePreference.SCENIC) { seen += it }
        assertTrue(seen.isNotEmpty())
        assertEquals(ModuleRoutePreference.SCENIC, rig.routing.lastPreference)
    }

    @Test
    fun renamingAPreparedRouteKeepsItsPreference() {
        val rig = rig()
        val p = (rig.generator.prepare(SHORT_STOPS, null, ModuleRoutePreference.SCENIC) {} as Prepare.Ok).route
        assertEquals(ModuleRoutePreference.SCENIC, p.withTitle("Named").preference)
        assertEquals(ModuleRoutePreference.SCENIC, p.withTitle(null).preference)
    }

    @Test
    fun aPlanSaysWhichPreferenceItIsRoutedWith() {
        assertFalse(Plan("p", "n", SHORT_STOPS, settings(), 1L).scenic)
        assertEquals(ModuleRoutePreference.FASTEST, Plan("p", "n", SHORT_STOPS, settings(), 1L).routePreference)
        assertEquals(ModuleRoutePreference.SCENIC, Plan("p", "n", SHORT_STOPS, settings(), 1L, scenic = true).routePreference)
        assertEquals(ModuleRoutePreference.SCENIC, routePreferenceOf(true))
        assertEquals(ModuleRoutePreference.FASTEST, routePreferenceOf(false))
    }

    @Test
    fun scenicRoundTripsThroughThePlanStore() {
        PlanStore(dir).save(Plan("s", "Scenic", SHORT_STOPS, settings(), 2L, "Dolomiti", scenic = true))
        PlanStore(dir).save(Plan("f", "Fast", SHORT_STOPS, settings(), 1L))
        val back = PlanStore(dir).all().associateBy { it.id }
        assertTrue(back.getValue("s").scenic)
        assertEquals("Dolomiti", back.getValue("s").titleOverride)
        assertFalse(back.getValue("f").scenic)
    }

    @Test
    fun aPlanFileWithoutScenicReadsAsNotScenic() {
        val old = """{"version":1,"plans":[{"id":"p","name":"Old","savedAtMillis":1,"titleOverride":"Named","stops":[{"lat":45.0,"lon":7.0,"label":"A"},{"lat":45.0,"lon":7.05,"label":"B"}],
            "settings":{"startAtMillis":1700000000000,"style":"NORMAL","traffic":"LIGHT"}}]}"""
        File(dir, "plans.json").writeText(old)
        val plan = PlanStore(dir).all().single()
        assertFalse(plan.scenic)
        assertEquals("Named", plan.titleOverride)
    }

    @Test
    fun aGarbledScenicValueReadsAsNotScenic() {
        val odd = """{"version":1,"plans":[{"id":"p","name":"Odd","savedAtMillis":1,"scenic":"maybe","stops":[{"lat":45.0,"lon":7.0,"label":"A"},{"lat":45.0,"lon":7.05,"label":"B"}],
            "settings":{"startAtMillis":1700000000000,"style":"NORMAL","traffic":"LIGHT"}}]}"""
        File(dir, "plans.json").writeText(odd)
        assertFalse(PlanStore(dir).all().single().scenic)
    }

    @Test
    fun aPlanIsPreparedWithItsOwnPreference() {
        // What the planner does for a saved plan: its stops, its override and its preference, one prepare.
        val rig = rig()
        val plan = Plan("p1", "Dolomiti Classic", SHORT_STOPS, settings(), 5L, "Dolomiti Classic", scenic = true)
        val p = (rig.generator.prepare(plan.stops, plan.titleOverride, plan.routePreference) {} as Prepare.Ok).route
        assertEquals(ModuleRoutePreference.SCENIC, rig.routing.lastPreference)
        assertEquals(plan.routePreference, p.preference)
    }
}
