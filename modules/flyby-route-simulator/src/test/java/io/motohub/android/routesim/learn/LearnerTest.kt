// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.routesim.learn

import io.motohub.android.module.ModuleRideEntry
import io.motohub.android.module.ModuleRideTrack
import io.motohub.android.routesim.sim.RideStyle
import io.motohub.android.routesim.sim.SimOutput
import io.motohub.android.routesim.sim.TrafficLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnerTest {

    private class FakeSources : Sources {
        var entries: List<ModuleRideEntry> = emptyList()
        val simulated = HashSet<String>()
        val withObd = HashSet<String>()
        val unreadable = HashSet<String>()
        var listingFails = false
        var limitsAnswer: (Int) -> FloatArray? = { points -> FloatArray(points - 1) { LearnTestRides.LIMIT_KPH } }

        val trackCalls = ArrayList<String>()
        var limitCalls = 0
        var lastTrackLimit = -1
        var lastListLimit = -1

        override fun recordedRides(limit: Int): List<ModuleRideEntry> {
            lastListLimit = limit
            if (listingFails) throw IllegalStateException("storage")
            return entries.take(limit)
        }

        override fun isSimulated(entry: ModuleRideEntry): Boolean = entry.id in simulated

        override fun track(entry: ModuleRideEntry, maxPoints: Int): ModuleRideTrack? {
            trackCalls.add(entry.id)
            lastTrackLimit = maxPoints
            if (entry.id in unreadable) return null
            return LearnTestRides.track(entry, outputFor(entry.id))
        }

        override fun engineRpm(track: ModuleRideTrack): FloatArray? =
            if (track.entry.id in withObd) outputFor(track.entry.id).engineRpm else null

        override fun speedLimits(latitudes: DoubleArray, longitudes: DoubleArray): FloatArray? {
            limitCalls++
            return limitsAnswer(latitudes.size)
        }
    }

    private companion object {
        // One simulated ride per slot, shared by every fake ride that points at it.
        val OUTPUTS: Array<SimOutput> by lazy {
            Array(3) { LearnTestRides.simulate(RideStyle.NORMAL, it + 1L, 12_000.0, TrafficLevel.LIGHT) }
        }

        fun outputFor(id: String): SimOutput = OUTPUTS[(id.removePrefix("r").toInt()) % OUTPUTS.size]
    }

    /** [count] rides of 12 km, r0 the oldest. */
    private fun rides(count: Int, km: Double = 12.0): List<ModuleRideEntry> =
        (0 until count).map { LearnTestRides.entry("r$it", km, 1_000_000L + it * 1000L) }

    private fun sources(count: Int, km: Double = 12.0) = FakeSources().also { it.entries = rides(count, km) }

    @Test
    fun twoRidesAreNotEnough() {
        val s = sources(2)
        val progress = ArrayList<String>()
        val report = Learner(s).learn({ progress.add(it) }, 99L)
        assertFalse(report.ok)
        assertEquals(2, report.eligibleRides)
        assertEquals(3, report.needed)
        assertEquals("3 rides of at least 10 km are needed, you have 2", report.message)
        assertNull(report.profile)
        assertTrue(report.facts.isEmpty())
        assertTrue("no ride should be read", s.trackCalls.isEmpty())
        assertEquals(200, s.lastListLimit)
        assertTrue(progress.isNotEmpty())
    }

    @Test
    fun noRidesAtAllIsReportedTheSameWay() {
        val report = Learner(FakeSources()).learn({}, 0L)
        assertFalse(report.ok)
        assertEquals("3 rides of at least 10 km are needed, you have 0", report.message)
    }

    @Test
    fun ridesUnderTenKilometresDoNotCount() {
        val s = sources(3)
        s.entries = s.entries.dropLast(1) + LearnTestRides.entry("r2", 9.9, 5_000_000L)
        val report = Learner(s).learn({}, 0L)
        assertFalse(report.ok)
        assertEquals("3 rides of at least 10 km are needed, you have 2", report.message)
        assertTrue(s.trackCalls.isEmpty())
    }

    @Test
    fun exactlyTenKilometresCounts() {
        val s = sources(3, 10.0)
        assertTrue(Learner(s).learn({}, 0L).ok)
    }

    @Test
    fun simulatedRidesAreNeverUsed() {
        val s = sources(5)
        s.simulated.addAll(listOf("r0", "r2", "r4"))
        val report = Learner(s).learn({}, 0L)
        assertFalse(report.ok)
        assertEquals(2, report.eligibleRides)
        assertEquals("3 rides of at least 10 km are needed, you have 2", report.message)
        assertTrue(s.trackCalls.isEmpty())

        // Enough real rides next to simulated ones: only the real ones are read.
        val t = sources(7)
        t.simulated.addAll(listOf("r1", "r3", "r5"))
        val ok = Learner(t).learn({}, 0L)
        assertTrue(ok.ok)
        assertEquals(setOf("r0", "r2", "r4", "r6"), t.trackCalls.toSet())
        assertEquals(4, ok.profile!!.ridesUsed)
    }

    @Test
    fun withoutAnObdAdapterTheEngineStaysGenericAndTheSummaryTellsWhy() {
        val s = sources(4)
        val report = Learner(s).learn({}, 777L)
        assertTrue(report.ok)
        assertEquals("", report.message)
        val p = report.profile!!
        assertEquals(777L, p.learnedAtMillis)
        assertEquals(4, p.ridesUsed)
        assertEquals(48.0, p.kmUsed, 1e-9)
        assertEquals(0, p.rpmRidesUsed)
        assertNull(p.gearRatios)
        assertNull(p.idleRpm)
        assertNull(p.maxRpm)
        assertNull(p.shiftUpRpm)
        assertNull(p.shiftDownRpm)
        assertNotNull(p.maxLeanDeg)
        assertNotNull(p.accelMs2)
        assertNotNull(p.overLimitFactor)
        val facts = report.facts.toMap()
        assertEquals("generic (no ride with an OBD adapter)", facts["Engine speed"])
        assertEquals("4 rides, 48 km", facts["Rides used"])
        assertTrue(facts["Maximum lean"]!!.endsWith("°"))
        assertTrue(facts["Speed against the limit"]!!.contains("limit"))
        assertFalse(facts.containsKey("Gears"))
    }

    @Test
    fun ridesWithObdTeachTheGearsToo() {
        val s = sources(4)
        s.withObd.addAll(listOf("r1", "r2", "r3"))
        val report = Learner(s).learn({}, 0L)
        assertTrue(report.ok)
        val p = report.profile!!
        assertEquals(3, p.rpmRidesUsed)
        assertNotNull(p.gearRatios)
        assertNotNull(p.idleRpm)
        val facts = report.facts.toMap()
        assertEquals("6 ratios learned from 3 rides with an OBD adapter", facts["Gears"])
        assertTrue(facts["Engine speed"]!!.contains("idle"))
        assertTrue(facts["Shift points"]!!.isNotEmpty())
    }

    @Test
    fun onlyTheThirtyMostRecentRidesAreReadAndOnlyTheNewestFiveAskForLimits() {
        val s = sources(35)
        s.entries = s.entries.reversed() // the host lists newest first; the learner must not rely on it
        val report = Learner(s).learn({}, 0L)
        assertTrue(report.ok)
        assertEquals(35, report.eligibleRides)
        assertEquals(30, s.trackCalls.size)
        assertEquals((5 until 35).map { "r$it" }.toSet(), s.trackCalls.toSet())
        assertEquals(20_000, s.lastTrackLimit)
        assertEquals(5, s.limitCalls)
        assertEquals(30, report.profile!!.ridesUsed)
    }

    @Test
    fun theLimitsQueryIsThinnedToWhatARoutingServerAccepts() {
        val s = sources(3)
        var asked = 0
        s.limitsAnswer = { points -> asked = maxOf(asked, points); FloatArray(points - 1) { 90f } }
        assertTrue(Learner(s).learn({}, 0L).ok)
        assertTrue("asked about $asked points", asked in 2..1500)
    }

    @Test
    fun whenLimitsCannotBeFetchedOverLimitIsSimplyNotLearned() {
        val s = sources(3)
        s.limitsAnswer = { null }
        val report = Learner(s).learn({}, 0L)
        assertTrue(report.ok)
        assertNull(report.profile!!.overLimitFactor)
        assertNotNull(report.profile!!.maxLeanDeg)
        assertTrue(report.facts.toMap()["Speed against the limit"]!!.startsWith("not learned"))

        val t = sources(3)
        t.limitsAnswer = { throw IllegalStateException("offline") }
        val r2 = Learner(t).learn({}, 0L)
        assertTrue(r2.ok)
        assertNull(r2.profile!!.overLimitFactor)
    }

    @Test
    fun aWrongSizedLimitsAnswerIsDiscarded() {
        val s = sources(3)
        s.limitsAnswer = { points -> FloatArray(points + 3) { 90f } }
        assertNull(Learner(s).learn({}, 0L).profile!!.overLimitFactor)
    }

    @Test
    fun ridesThatCannotBeReadDoNotCountTowardTheThree() {
        val s = sources(4)
        s.unreadable.addAll(listOf("r0", "r1"))
        val report = Learner(s).learn({}, 0L)
        assertFalse(report.ok)
        assertEquals(4, report.eligibleRides)
        assertEquals("Only 2 of your rides could be read. 3 are needed.", report.message)
        assertNull(report.profile)
    }

    @Test
    fun aHostThatFailsToListRidesLooksLikeNoRides() {
        val s = FakeSources().also { it.listingFails = true }
        val report = Learner(s).learn({}, 0L)
        assertFalse(report.ok)
        assertEquals("3 rides of at least 10 km are needed, you have 0", report.message)
    }

    @Test
    fun progressIsReportedWhileReading() {
        val s = sources(3)
        val progress = ArrayList<String>()
        Learner(s).learn({ progress.add(it) }, 0L)
        assertTrue(progress.any { it.startsWith("Reading ride 1 of 3") })
        assertTrue(progress.any { it.startsWith("Looking up the speed limits") })
        assertTrue(progress.any { it.startsWith("Working out") })
    }

    @Test
    fun thinnedIndicesSpanTheTrackEvenly() {
        val idx = Learner.thinnedIndices(20_000, 1500)
        assertEquals(1500, idx.size)
        assertEquals(0, idx.first())
        assertEquals(19_999, idx.last())
        for (i in 1 until idx.size) assertTrue(idx[i] > idx[i - 1])
        assertEquals(5, Learner.thinnedIndices(5, 1500).size)
    }

    @Test
    fun segmentLimitsSpreadOverTheOriginalPoints() {
        // 10 points, queried at 0, 4, 9: two segments with 50 and NaN.
        val out = Learner.spreadSegmentLimits(10, intArrayOf(0, 4, 9), floatArrayOf(50f, Float.NaN))!!
        assertEquals(10, out.size)
        for (p in 0..3) assertEquals(50f, out[p], 0f)
        for (p in 4..9) assertTrue(out[p].isNaN())
        assertNull(Learner.spreadSegmentLimits(10, intArrayOf(0, 4, 9), floatArrayOf(50f)))
        val last = Learner.spreadSegmentLimits(10, intArrayOf(0, 4, 9), floatArrayOf(50f, 70f))!!
        assertEquals(70f, last[9], 0f)
        assertEquals(70f, last[4], 0f)
    }
}
