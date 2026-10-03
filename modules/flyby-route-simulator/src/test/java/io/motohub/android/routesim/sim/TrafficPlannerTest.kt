package io.motohub.android.routesim.sim

import java.util.Random
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficPlannerTest {

    private fun plan(total: Double, level: TrafficLevel, seed: Long, overtakeAllowed: (Double) -> Boolean = { true }) =
        TrafficPlanner.plan(total, level, Random(seed), overtakeAllowed)

    @Test
    fun noTrafficMeansNoEvents() {
        assertTrue(plan(100_000.0, TrafficLevel.NONE, 1L).isEmpty())
    }

    @Test
    fun sameSeedGivesTheSameEventsAndAnotherSeedDoesNot() {
        val a = plan(50_000.0, TrafficLevel.HEAVY, 7L)
        val b = plan(50_000.0, TrafficLevel.HEAVY, 7L)
        val c = plan(50_000.0, TrafficLevel.HEAVY, 8L)
        assertEquals(a.size, b.size)
        for (i in a.indices) {
            assertEquals(a[i].type, b[i].type)
            assertEquals(a[i].startMeters, b[i].startMeters, 0.0)
            assertEquals(a[i].durationMs, b[i].durationMs)
        }
        val same = a.size == c.size && a.indices.all { a[it].startMeters == c[it].startMeters }
        assertFalse(same)
    }

    @Test
    fun frequencyFollowsTheTrafficLevel() {
        var light = 0
        var heavy = 0
        for (seed in 1L..10L) {
            light += plan(100_000.0, TrafficLevel.LIGHT, seed).size
            heavy += plan(100_000.0, TrafficLevel.HEAVY, seed).size
        }
        val lightPerRun = light / 10.0
        val heavyPerRun = heavy / 10.0
        // Nominal 15 and 50 events per 100 km; gaps and event lengths thin that out a little.
        assertTrue("light $lightPerRun", lightPerRun in 8.0..20.0)
        assertTrue("heavy $heavyPerRun", heavyPerRun in 25.0..55.0)
        assertTrue(heavyPerRun > 2.0 * lightPerRun)
    }

    @Test
    fun eventsAreSortedSeparatedAndInsideTheRoute() {
        val total = 80_000.0
        for (seed in 1L..5L) {
            val events = plan(total, TrafficLevel.HEAVY, seed)
            var previousEnd = TrafficPlanner.EDGE_MARGIN_M
            for (e in events) {
                assertTrue("start ${e.startMeters} before $previousEnd", e.startMeters >= previousEnd + TrafficPlanner.MIN_GAP_M - 1e-6)
                assertTrue(e.endMeters <= total - TrafficPlanner.EDGE_MARGIN_M + 1e-6)
                previousEnd = e.endMeters
            }
        }
    }

    @Test
    fun eventParametersStayInTheirRanges() {
        val events = plan(200_000.0, TrafficLevel.HEAVY, 21L)
        assertTrue(events.size > 50)
        val seen = HashSet<TrafficEventType>()
        for (e in events) {
            seen.add(e.type)
            when (e.type) {
                TrafficEventType.TRAFFIC_LIGHT -> assertTrue("wait ${e.durationMs}", e.durationMs in 8_000L..45_000L)
                TrafficEventType.JUNCTION -> assertTrue(e.speedKph in 15.0..30.0)
                TrafficEventType.QUEUE -> {
                    assertTrue(e.speedKph in 5.0..25.0)
                    assertTrue(e.lengthMeters in 100.0..400.0)
                }
                TrafficEventType.OVERTAKE -> {
                    assertTrue(e.speedKph in 15.0..30.0)
                    assertTrue(e.durationMs in 8_000L..15_000L)
                }
            }
        }
        assertEquals(TrafficEventType.values().toSet(), seen)
    }

    @Test
    fun overtakesOnlyWhereAllowed() {
        // Overtaking allowed only in the first 40 km of a 100 km route.
        for (seed in 1L..5L) {
            val events = plan(100_000.0, TrafficLevel.HEAVY, seed) { it < 40_000.0 }
            for (e in events) {
                if (e.type == TrafficEventType.OVERTAKE) assertTrue(e.startMeters < 40_000.0)
            }
        }
        val none = plan(100_000.0, TrafficLevel.HEAVY, 3L) { false }
        assertTrue(none.none { it.type == TrafficEventType.OVERTAKE })
    }

    @Test
    fun heavyTrafficAlwaysHasAStopOnALongEnoughRoute() {
        for (seed in 1L..60L) {
            val events = plan(3_000.0, TrafficLevel.HEAVY, seed)
            assertTrue("seed $seed", events.any { it.type == TrafficEventType.TRAFFIC_LIGHT })
        }
    }

    @Test
    fun tinyRoutesGetNoEvents() {
        assertTrue(plan(300.0, TrafficLevel.HEAVY, 1L).isEmpty())
    }

    // -- State machine ----------------------------------------------------------------------

    private val brake = 2.25

    @Test
    fun trafficLightStopsWaitsAndReleases() {
        val light = TrafficEvent(TrafficEventType.TRAFFIC_LIGHT, 500.0, 0.0, 0.0, 10_000L)
        val rt = TrafficRuntime(listOf(light))

        // Far away: a braking-distance ceiling, no stop yet.
        assertEquals(sqrt(2.0 * brake * 500.0), rt.allowedMs(0.0, brake), 1e-9)
        assertTrue(rt.update(100.0, 20.0, 100L, true).isNaN())
        assertFalse(rt.holding)

        // Close but still too fast to count as stopped.
        assertTrue(rt.update(499.9, 3.0, 100L, true).isNaN())
        assertFalse(rt.holding)

        // Rolled to a halt on the line: snapped to the stop point and held.
        assertEquals(500.0, rt.update(499.9, 0.3, 100L, true), 0.0)
        assertTrue(rt.holding)
        assertEquals(0.0, rt.allowedMs(500.0, brake), 0.0)
        assertEquals(1, rt.lightStops)

        // 10 s at 100 ms ticks: released on the tick that uses the last of the wait.
        var ticks = 0
        while (rt.holding && ticks < 1000) {
            rt.update(500.0, 0.0, 100L, true)
            ticks++
        }
        assertEquals(100, ticks)
        assertEquals(Double.MAX_VALUE, rt.allowedMs(500.0, brake), 0.0)
    }

    @Test
    fun junctionAndQueueCapTheSpeedInTheirZone() {
        val junction = TrafficEvent(TrafficEventType.JUNCTION, 1000.0, 30.0, 18.0, 0L)
        val queue = TrafficEvent(TrafficEventType.QUEUE, 2000.0, 200.0, 7.2, 0L)
        val rt = TrafficRuntime(listOf(junction, queue))

        val c = 18.0 / 3.6
        assertEquals(sqrt(c * c + 2.0 * brake * 100.0), rt.allowedMs(900.0, brake), 1e-9)
        assertEquals(c, rt.allowedMs(1010.0, brake), 1e-9)

        // Past the junction it is retired and the queue becomes the next ceiling.
        rt.update(1040.0, 10.0, 100L, true)
        val q = 7.2 / 3.6
        assertEquals(sqrt(q * q + 2.0 * brake * 500.0), rt.allowedMs(1500.0, brake), 1e-9)
        assertEquals(q, rt.allowedMs(2100.0, brake), 1e-9)
        rt.update(2300.0, 10.0, 100L, true)
        assertEquals(Double.MAX_VALUE, rt.allowedMs(2300.0, brake), 0.0)
    }

    @Test
    fun lightsBeyondTheLookaheadDoNotLimitTheSpeed() {
        val light = TrafficEvent(TrafficEventType.TRAFFIC_LIGHT, 5000.0, 0.0, 0.0, 10_000L)
        val rt = TrafficRuntime(listOf(light))
        assertEquals(Double.MAX_VALUE, rt.allowedMs(0.0, brake), 0.0)
    }

    @Test
    fun overtakeWaitsForAGoodStretchThenBoostsForItsDuration() {
        val overtake = TrafficEvent(TrafficEventType.OVERTAKE, 1000.0, 300.0, 21.6, 8_000L)
        val rt = TrafficRuntime(listOf(overtake))

        rt.update(900.0, 25.0, 100L, true)
        assertEquals(0.0, rt.boostMs, 0.0) // not there yet
        rt.update(1010.0, 25.0, 100L, false)
        assertEquals(0.0, rt.boostMs, 0.0) // there, but not a place to overtake
        rt.update(1020.0, 25.0, 100L, true)
        assertEquals(21.6 / 3.6, rt.boostMs, 1e-9)

        var ticks = 0
        while (rt.boostMs > 0.0 && ticks < 1000) {
            rt.update(1100.0, 25.0, 100L, true)
            ticks++
        }
        assertEquals(80, ticks)
        assertEquals(Double.MAX_VALUE, rt.allowedMs(1100.0, brake), 0.0)
    }

    @Test
    fun overtakeThatNeverGetsAChanceExpires() {
        val overtake = TrafficEvent(TrafficEventType.OVERTAKE, 1000.0, 300.0, 21.6, 8_000L)
        val after = TrafficEvent(TrafficEventType.JUNCTION, 1600.0, 30.0, 18.0, 0L)
        val rt = TrafficRuntime(listOf(overtake, after))
        rt.update(1010.0, 25.0, 100L, false)
        rt.update(1400.0, 25.0, 100L, true) // past the window: too late
        assertEquals(0.0, rt.boostMs, 0.0)
    }

    @Test
    fun aLightPassedWithoutStoppingIsRetired() {
        val light = TrafficEvent(TrafficEventType.TRAFFIC_LIGHT, 500.0, 0.0, 0.0, 10_000L)
        val rt = TrafficRuntime(listOf(light))
        rt.update(505.0, 10.0, 100L, true)
        assertFalse(rt.holding)
        assertEquals(Double.MAX_VALUE, rt.allowedMs(505.0, brake), 0.0)
    }
}
