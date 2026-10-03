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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlanStoreTest {

    private lateinit var dir: File

    @Before fun setUp() { dir = Files.createTempDirectory("plans").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    private fun plan(id: String, saved: Long, name: String = "Plan $id"): Plan {
        val base = DrivingProfile.generic(RideStyle.SPORTY)
        val profile = base.copy(accelMs2 = 2.75, maxLeanDeg = 44.5, shiftUpRpm = 7123.0, gearRatios = doubleArrayOf(700.0, 500.0, 400.0))
        return Plan(
            id, name,
            listOf(Stop(45.123456, 7.654321, "Start à"), Stop(45.5, 7.9, "Via \"quoted\""), Stop(46.0, 8.0, "End")),
            RideSettings(1_700_000_123_456L, RideStyle.SPORTY, TrafficLevel.HEAVY, profile),
            saved,
        )
    }

    @Test
    fun emptyDirectoryHasNoPlans() {
        assertTrue(PlanStore(dir).all().isEmpty())
        assertTrue(PlanStore(File(dir, "not/yet/there")).all().isEmpty())
    }

    @Test
    fun aPlanSurvivesARoundTrip() {
        PlanStore(dir).save(plan("a", 100L))
        val back = PlanStore(dir).all().single()   // a fresh store reads the file
        val want = plan("a", 100L)
        assertEquals("a", back.id)
        assertEquals(want.name, back.name)
        assertEquals(100L, back.savedAtMillis)
        assertEquals(3, back.stops.size)
        assertEquals(45.123456, back.stops[0].latitude, 0.0)
        assertEquals(7.654321, back.stops[0].longitude, 0.0)
        assertEquals("Start à", back.stops[0].label)
        assertEquals("Via \"quoted\"", back.stops[1].label)
        assertEquals(want.settings.startAtMillis, back.settings.startAtMillis)
        assertEquals(RideStyle.SPORTY, back.settings.style)
        assertEquals(TrafficLevel.HEAVY, back.settings.traffic)
        val p = back.settings.profile
        val w = want.settings.profile
        assertEquals(w.style, p.style)
        assertEquals(2.75, p.accelMs2, 0.0)
        assertEquals(w.brakeMs2, p.brakeMs2, 0.0)
        assertEquals(w.lateralAccelMs2, p.lateralAccelMs2, 0.0)
        assertEquals(w.overLimitFactor, p.overLimitFactor, 0.0)
        assertEquals(w.defaultCruiseKph, p.defaultCruiseKph, 0.0)
        assertEquals(44.5, p.maxLeanDeg, 0.0)
        assertEquals(w.idleRpm, p.idleRpm, 0.0)
        assertEquals(w.maxRpm, p.maxRpm, 0.0)
        assertEquals(7123.0, p.shiftUpRpm, 0.0)
        assertEquals(w.shiftDownRpm, p.shiftDownRpm, 0.0)
        assertArrayEquals(w.gearRatios, p.gearRatios, 0.0)
    }

    @Test
    fun savingTheSameIdReplacesAndListIsNewestFirst() {
        val store = PlanStore(dir)
        store.save(plan("a", 100L))
        store.save(plan("b", 300L))
        store.save(plan("c", 200L))
        assertEquals(listOf("b", "c", "a"), store.all().map { it.id })
        store.save(plan("a", 400L, name = "Renamed"))
        val all = store.all()
        assertEquals(listOf("a", "b", "c"), all.map { it.id })
        assertEquals("Renamed", all.first().name)
    }

    @Test
    fun removeDeletesOnlyThatPlan() {
        val store = PlanStore(dir)
        store.save(plan("a", 1L))
        store.save(plan("b", 2L))
        store.remove("a")
        store.remove("missing")
        assertEquals(listOf("b"), PlanStore(dir).all().map { it.id })
    }

    @Test
    fun aCorruptFileReadsAsEmptyAndCanBeWrittenOver() {
        File(dir, "plans.json").writeText("{ this is not json")
        val store = PlanStore(dir)
        assertTrue(store.all().isEmpty())
        store.save(plan("a", 1L))
        assertEquals(1, store.all().size)
    }

    @Test
    fun aBrokenEntryIsSkippedNotFatal() {
        File(dir, "plans.json").writeText("""{"version":1,"plans":[{"id":"x"},""" +
            """{"id":"y","name":"ok","savedAtMillis":5,"stops":[{"lat":1.0,"lon":2.0,"label":"L"}],"settings":{"startAtMillis":9,"style":"CALM","traffic":"NONE"}}]}""")
        val all = PlanStore(dir).all()
        assertEquals(listOf("y"), all.map { it.id })
        // A missing profile falls back on the generic one of the style.
        assertEquals(DrivingProfile.generic(RideStyle.CALM).accelMs2, all[0].settings.profile.accelMs2, 0.0)
        assertEquals(RideStyle.CALM, all[0].settings.style)
    }
}
