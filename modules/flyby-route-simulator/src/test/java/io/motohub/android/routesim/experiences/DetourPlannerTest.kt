// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.routesim.experiences

import io.motohub.android.module.ModuleSightKind
import io.motohub.android.routesim.sim.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetourPlannerTest {

    private val road = testRoad()

    /** The road is about 39 km and 59 minutes at the test's speed. */
    private fun plan(
        sights: List<Sight>,
        filters: ExperienceFilters = ExperienceFilters(),
        baseKm: Double = 39.3,
        baseMinutes: Double = 59.0,
        alt: Int = 0,
        radiusM: Int = DetourPlanner.DEFAULT_RADIUS_M,
        avoid: List<ExperiencePlace> = emptyList(),
    ) = DetourPlanner.plan(road, sights, filters, baseKm, baseMinutes, alt, radiusM, avoid)

    private fun names(list: List<Sight>) = list.map { it.name }

    // --- ordering and count ----------------------------------------------------------------

    @Test fun picksComeInTheOrderTheRoadMeetsThem() {
        val sights = listOf(
            sightAt("Late", 7.40, 1.0), sightAt("Early", 7.10, 1.0), sightAt("Middle", 7.25, 1.0),
        )
        assertEquals(listOf("Early", "Middle", "Late"), names(plan(sights)))
    }

    @Test fun orderFollowsTheRoadNotTheRanking() {
        // The best-ranked one (closest) is the last on the road.
        val sights = listOf(
            sightAt("Far", 7.10, 3.0), sightAt("Mid", 7.25, 2.0), sightAt("Near", 7.40, 0.5),
        )
        assertEquals(listOf("Far", "Mid", "Near"), names(plan(sights)))
        assertEquals("Near", DetourPlanner.planRanked(road, sights, ExperienceFilters(), 39.3, 59.0, 0).first().name)
    }

    @Test fun neverMoreThanTheDetoursAsked() {
        val sights = sixSights()
        assertEquals(2, plan(sights, ExperienceFilters(detours = 2)).size)
        assertEquals(6, plan(sights, ExperienceFilters(detours = 6)).size)
        assertEquals(1, plan(sights, ExperienceFilters(detours = 1)).size)
    }

    @Test fun anAbsurdCountIsClampedToTheMaximum() {
        val many = (0 until 12).map { sightAt("S$it", 7.03 + it * 0.04, 1.0) }
        assertEquals(ExperienceFilters.MAX_DETOURS, plan(many, ExperienceFilters(detours = 40)).size)
    }

    @Test fun zeroDetoursMeansNone() {
        assertTrue(plan(sixSights(), ExperienceFilters(detours = 0)).isEmpty())
    }

    @Test fun noSightsNoDetours() {
        assertTrue(plan(emptyList()).isEmpty())
    }

    // --- budget ----------------------------------------------------------------------------

    @Test fun kmBudgetIsRespected() {
        // 2 km off costs 2 x 2 x 1.4 = 5.6 km; 3 km off costs 8.4 km. 10 km are left.
        val sights = listOf(sightAt("Two", 7.15, 2.0), sightAt("Three", 7.35, 3.0))
        val picked = plan(sights, ExperienceFilters(maxKm = 50), baseKm = 40.0)
        assertEquals(listOf("Two"), names(picked))
    }

    @Test fun theSumOfTheExtrasStaysWithinTheKmLimit() {
        val sights = sixSights(offKm = 2.0)   // 5.6 km each
        for (limit in listOf(46, 52, 58, 64, 80)) {
            val picked = plan(sights, ExperienceFilters(maxKm = limit, detours = 6), baseKm = 40.0)
            val extra = picked.sumOf { DetourPlanner.extraKm(2000.0) }
            assertTrue("limit $limit extra $extra", 40.0 + extra <= limit + 0.01)
        }
        // 45 km leaves 5 km: not even one of 5.6 km.
        assertTrue(plan(sights, ExperienceFilters(maxKm = 45, detours = 6), baseKm = 40.0).isEmpty())
        // 46 km leaves 6 km: exactly one.
        assertEquals(1, plan(sights, ExperienceFilters(maxKm = 46, detours = 6), baseKm = 40.0).size)
    }

    @Test fun minutesBudgetIsRespected() {
        // 3 km off: 8.4 km at 45 km/h = 11.2 min. 1 km off: 2.8 km = 3.7 min. 5 minutes are left.
        val far = listOf(sightAt("Far", 7.2, 3.0))
        val near = listOf(sightAt("Near", 7.2, 1.0))
        val filters = ExperienceFilters(maxMinutes = 64)
        assertTrue(plan(far, filters, baseMinutes = 59.0).isEmpty())
        assertEquals(listOf("Near"), names(plan(near, filters, baseMinutes = 59.0)))
    }

    @Test fun aSightTooDearForWhatIsLeftDoesNotStopTheCheaperOnes() {
        // The best-ranked cannot be afforded but the others can.
        val sights = listOf(sightAt("Dear", 7.15, 3.5), sightAt("Cheap", 7.35, 0.4))
        val picked = plan(sights, ExperienceFilters(maxKm = 43), baseKm = 40.0)
        assertEquals(listOf("Cheap"), names(picked))
    }

    @Test fun aBaseAlreadyOverTheLimitGetsNoDetours() {
        val sights = sixSights()
        assertTrue(plan(sights, ExperienceFilters(maxKm = 30), baseKm = 39.3).isEmpty())
        assertTrue(plan(sights, ExperienceFilters(maxMinutes = 40), baseMinutes = 59.0).isEmpty())
    }

    @Test fun aBaseExactlyAtTheLimitStillMayGetFreeDetours() {
        // A sight on the road itself costs nothing.
        val onRoad = listOf(sightAt("OnTheRoad", 7.25, 0.0))
        assertEquals(1, plan(onRoad, ExperienceFilters(maxKm = 40), baseKm = 40.0).size)
        assertTrue(plan(listOf(sightAt("Off", 7.25, 1.0)), ExperienceFilters(maxKm = 40), baseKm = 40.0).isEmpty())
    }

    // --- radius ----------------------------------------------------------------------------

    @Test fun sightsBeyondTheRadiusAreLeftOut() {
        val sights = listOf(sightAt("Out", 7.25, 5.0))
        assertTrue(plan(sights).isEmpty())
        assertEquals(1, plan(sights, radiusM = 6_000).size)
        assertEquals(1, plan(listOf(sightAt("In", 7.25, 3.9))).size)
    }

    // --- the culture slider ----------------------------------------------------------------

    @Test fun cultureSliderChoosesBetweenSceneryAndHeritage() {
        val sights = listOf(
            sightAt("Lookout", 7.20, 1.0, ModuleSightKind.VIEWPOINT),
            sightAt("Castle", 7.30, 1.0, ModuleSightKind.HERITAGE),
        )
        val one = ExperienceFilters(detours = 1)
        assertEquals(listOf("Lookout"), names(plan(sights, one.copy(culture = 0))))
        assertEquals(listOf("Castle"), names(plan(sights, one.copy(culture = 100))))
        assertEquals(listOf("Lookout"), names(plan(sights, one.copy(culture = 20))))
        assertEquals(listOf("Castle"), names(plan(sights, one.copy(culture = 80))))
    }

    @Test fun passesCountAsSceneryForTheSlider() {
        val sights = listOf(
            sightAt("The Pass", 7.20, 1.0, ModuleSightKind.PASS),
            sightAt("Castle", 7.30, 1.0, ModuleSightKind.HERITAGE),
        )
        assertEquals(listOf("The Pass"), names(plan(sights, ExperienceFilters(detours = 1, culture = 0))))
        assertEquals(listOf("Castle"), names(plan(sights, ExperienceFilters(detours = 1, culture = 100))))
    }

    @Test fun kindWeights() {
        val v = ModuleSightKind.VIEWPOINT
        val p = ModuleSightKind.PASS
        val h = ModuleSightKind.HERITAGE
        assertEquals(1.0, DetourPlanner.kindWeight(v, null), 1e-9)
        assertEquals(1.0, DetourPlanner.kindWeight(p, null), 1e-9)
        assertEquals(1.0, DetourPlanner.kindWeight(h, null), 1e-9)
        assertEquals(1.0, DetourPlanner.kindWeight(v, 0), 1e-9)
        assertEquals(1.0, DetourPlanner.kindWeight(p, 0), 1e-9)
        assertEquals(0.35, DetourPlanner.kindWeight(h, 0), 1e-9)
        assertEquals(0.35, DetourPlanner.kindWeight(v, 100), 1e-9)
        assertEquals(1.0, DetourPlanner.kindWeight(h, 100), 1e-9)
        assertEquals(0.675, DetourPlanner.kindWeight(v, 50), 1e-9)
        assertEquals(0.675, DetourPlanner.kindWeight(h, 50), 1e-9)
        assertEquals(0.0, DetourPlanner.kindWeight(99, 50), 1e-9)
        // Out-of-range sliders are clamped.
        assertEquals(1.0, DetourPlanner.kindWeight(v, -30), 1e-9)
        assertEquals(1.0, DetourPlanner.kindWeight(h, 400), 1e-9)
    }

    @Test fun neutralSliderTreatsKindsAlike() {
        // Same distance, so only the tie-break decides, and it is the same whatever the kinds.
        val a = listOf(sightAt("A-view", 7.20, 1.0, ModuleSightKind.VIEWPOINT), sightAt("B-castle", 7.30, 1.0, ModuleSightKind.HERITAGE))
        val b = listOf(sightAt("A-view", 7.20, 1.0, ModuleSightKind.HERITAGE), sightAt("B-castle", 7.30, 1.0, ModuleSightKind.VIEWPOINT))
        val one = ExperienceFilters(detours = 1, culture = null)
        assertEquals(names(plan(a, one)), names(plan(b, one)))
    }

    @Test fun aHighPassBeatsAViewpointAtTheSameDistance() {
        val sights = listOf(
            sightAt("Lookout", 7.20, 1.0, ModuleSightKind.VIEWPOINT),
            sightAt("High Pass", 7.30, 1.0, ModuleSightKind.PASS, elevationM = 2400.0),
        )
        assertEquals(listOf("High Pass"), names(plan(sights, ExperienceFilters(detours = 1))))
    }

    @Test fun aHigherPassBeatsALowerOne() {
        val sights = listOf(
            sightAt("Low Pass", 7.20, 1.0, ModuleSightKind.PASS, elevationM = 600.0),
            sightAt("High Pass", 7.30, 1.0, ModuleSightKind.PASS, elevationM = 2300.0),
        )
        assertEquals(listOf("High Pass"), names(plan(sights, ExperienceFilters(detours = 1))))
    }

    @Test fun aSightWithoutHeightIsNotPenalisedAsAPass() {
        val sights = listOf(sightAt("Pass without height", 7.2, 1.0, ModuleSightKind.PASS))
        assertEquals(1, plan(sights).size)
    }

    @Test fun closerToTheRoadWins() {
        val sights = listOf(sightAt("Far", 7.20, 3.0), sightAt("Near", 7.30, 1.0))
        assertEquals(listOf("Near"), names(plan(sights, ExperienceFilters(detours = 1))))
    }

    // --- the ends, duplicates, spacing -----------------------------------------------------

    @Test fun sightsNearTheStartOrTheEndAreNotDetours() {
        val a = LatLng(45.0, 7.0)
        val b = LatLng(45.0, 7.5)
        val nearA = sightAt("Near A", 7.005, 0.5)
        val nearB = sightAt("Near B", 7.495, 0.5)
        val fine = sightAt("Fine", 7.25, 1.0)
        assertTrue(LatLng(nearA.latitude, nearA.longitude).distanceTo(a) < 1000.0)
        assertTrue(LatLng(nearB.latitude, nearB.longitude).distanceTo(b) < 1000.0)
        assertEquals(listOf("Fine"), names(plan(listOf(nearA, nearB, fine))))
    }

    @Test fun aSightJustBeyondAKilometreFromTheStartCounts() {
        // 0.015 degrees of longitude is 1.18 km from A.
        assertEquals(1, plan(listOf(sightAt("Not so near", 7.015, 0.0))).size)
    }

    @Test fun sameNameIsTakenOnce() {
        val sights = listOf(sightAt("Rocca", 7.10, 1.5), sightAt("rocca ", 7.40, 1.0), sightAt("Other", 7.25, 2.0))
        val picked = plan(sights)
        assertEquals(1, picked.count { it.name.trim().equals("Rocca", ignoreCase = true) })
        // The closer one of the two was kept.
        assertEquals(7.40, picked.first { it.name.trim().equals("rocca", ignoreCase = true) }.longitude, 1e-9)
    }

    @Test fun picksWithin400MetresOfEachOtherAreOne() {
        val sights = listOf(sightAt("One", 7.25, 1.0), sightAt("Two", 7.25, 1.2))
        assertEquals(1, plan(sights).size)
    }

    @Test fun twoDetoursAreNotCloserThan8PercentOfTheRoadAlongIt() {
        // 8% of 39 km is 3.1 km, 0.04 degrees of longitude: 0.01 apart is too close, 0.05 is fine.
        assertEquals(1, plan(listOf(sightAt("One", 7.20, 1.0), sightAt("Two", 7.21, 1.0))).size)
        assertEquals(2, plan(listOf(sightAt("One", 7.20, 1.0), sightAt("Two", 7.25, 1.0))).size)
    }

    @Test fun anUnnamedOrUnknownKindOrNanSightIsIgnored() {
        val sights = listOf(
            sightAt(" ", 7.10, 1.0),
            Sight("Odd", 45.01, 7.2, 99),
            Sight("Nowhere", Double.NaN, 7.3, ModuleSightKind.VIEWPOINT),
            sightAt("Good", 7.4, 1.0),
        )
        assertEquals(listOf("Good"), names(plan(sights)))
    }

    // --- determinism and alternatives ------------------------------------------------------

    @Test fun theSameInputsGiveTheSameDetours() {
        val sights = sixSights()
        val first = plan(sights)
        repeat(5) { assertEquals(first, plan(sights)) }
    }

    @Test fun theOrderOfTheSightsGivenDoesNotMatter() {
        val sights = sixSights()
        val expected = plan(sights)
        assertEquals(expected, plan(sights.reversed()))
        assertEquals(expected, plan(sights.shuffled(java.util.Random(7))))
        assertEquals(plan(sights, alt = 2), plan(sights.reversed(), alt = 2))
    }

    @Test fun equalSightsAreBrokenByNameSoTheResultIsStable() {
        val sights = listOf(sightAt("Beta", 7.2, 1.0), sightAt("Alpha", 7.3, 1.0))
        val one = ExperienceFilters(detours = 1)
        assertEquals(listOf("Alpha"), names(plan(sights, one)))
        assertEquals(listOf("Alpha"), names(plan(sights.reversed(), one)))
    }

    @Test fun everyAlternativeDiffersFromTheEarlierOnes() {
        // Nine sights well apart, each a little further from the road than the one before.
        val sights = (0 until 9).map { sightAt("S$it", 7.05 + it * 0.05, 0.5 + it * 0.2) }
        val sets = (0..5).map { alt -> plan(sights, alt = alt).map { it.name }.toSet() }
        for (i in sets.indices) for (j in 0 until i) {
            assertNotEquals("alt $i equals alt $j", sets[j], sets[i])
        }
        // The best set is the best three.
        assertEquals(setOf("S0", "S1", "S2"), sets[0])
        sets.forEach { assertEquals(3, it.size) }
    }

    @Test fun alternativesAreStableToo() {
        val sights = (0 until 9).map { sightAt("S$it", 7.05 + it * 0.05, 0.5 + it * 0.2) }
        for (alt in 0..4) assertEquals(plan(sights, alt = alt), plan(sights, alt = alt))
    }

    @Test fun alternativesAreStillInOrderAlongTheRoad() {
        val sights = (0 until 9).map { sightAt("S$it", 7.05 + it * 0.05, 0.5 + it * 0.2) }
        for (alt in 0..4) {
            val longitudes = plan(sights, alt = alt).map { it.longitude }
            assertEquals(longitudes.sorted(), longitudes)
        }
    }

    @Test fun alternativesRespectTheBudgetToo() {
        val sights = (0 until 9).map { sightAt("S$it", 7.05 + it * 0.05, 1.0 + it * 0.3) }
        val filters = ExperienceFilters(maxKm = 50, detours = 3)
        for (alt in 0..4) {
            val picked = plan(sights, filters, baseKm = 40.0, alt = alt)
            val extra = picked.sumOf { s ->
                DetourPlanner.extraKm(
                    RoadLine(road).project(s.latitude, s.longitude).offM,
                )
            }
            assertTrue("alt $alt extra $extra", 40.0 + extra <= 50.0 + 0.01)
        }
    }

    @Test fun withTooFewSightsAnAlternativeRepeatsTheLastSet() {
        val one = listOf(sightAt("Only", 7.25, 1.0))
        assertEquals(names(plan(one, alt = 0)), names(plan(one, alt = 3)))
        assertEquals(listOf("Only"), names(plan(one, alt = 3)))

        val two = listOf(sightAt("First", 7.15, 1.0), sightAt("Second", 7.35, 2.0))
        assertEquals(listOf("First", "Second"), names(plan(two, alt = 0)))
        assertEquals(listOf("Second"), names(plan(two, alt = 1)))
        assertEquals(listOf("Second"), names(plan(two, alt = 2)))
        assertEquals(listOf("Second"), names(plan(two, alt = 9)))
    }

    @Test fun aHugeAltDoesNotLoopForever() {
        val sights = sixSights()
        assertTrue(plan(sights, alt = Int.MAX_VALUE).isNotEmpty())
        assertEquals(plan(sights, alt = 0), plan(sights, alt = -4))
    }

    @Test fun planRankedStartsWithTheBestPick() {
        val sights = listOf(sightAt("Far", 7.10, 3.0), sightAt("Near", 7.25, 0.5), sightAt("Mid", 7.40, 2.0))
        val ranked = DetourPlanner.planRanked(road, sights, ExperienceFilters(), 39.3, 59.0, 0)
        assertEquals(listOf("Near", "Mid", "Far"), names(ranked))
    }

    // --- the stops the ride already has ----------------------------------------------------

    @Test fun aSightNearAViaPointIsNotADetour() {
        val via = ExperiencePlace("Passo Via", 45.0, 7.25)
        val sights = listOf(
            sightAt("The Pass", 7.2505, 0.4, ModuleSightKind.PASS, 2100.0),   // about 0.4 km from the via point
            sightAt("Lookout", 7.40, 1.0),
        )
        assertEquals(listOf("The Pass", "Lookout"), names(plan(sights)))
        assertEquals(listOf("Lookout"), names(plan(sights, avoid = listOf(via))))
    }

    @Test fun aSightFurtherThanAKilometreFromAViaPointStaysADetour() {
        val via = ExperiencePlace("Passo Via", 45.0, 7.25)
        val sights = listOf(sightAt("Next Door", 7.27, 1.0))   // about 1.5 km away
        assertEquals(listOf("Next Door"), names(plan(sights, avoid = listOf(via))))
    }

    @Test fun aSightWithTheNameOfAStopIsNotADetourWhereverItIs() {
        val avoid = listOf(ExperiencePlace("  Passo Via ", 45.0, 7.25), ExperiencePlace("Bolzano", 45.0, 7.5))
        val sights = listOf(sightAt("passo via", 7.10, 1.0), sightAt("BOLZANO", 7.40, 1.0), sightAt("Other", 7.20, 1.0))
        assertEquals(listOf("Other"), names(plan(sights, avoid = avoid)))
    }

    @Test fun planRankedHonoursTheStopsToo() {
        val avoid = listOf(ExperiencePlace("Passo Via", 45.0, 7.25))
        val sights = listOf(sightAt("Passo Via", 7.30, 0.5), sightAt("Elsewhere", 7.10, 0.5))
        val ranked = DetourPlanner.planRanked(road, sights, ExperienceFilters(), 39.3, 59.0, 0, DetourPlanner.DEFAULT_RADIUS_M, avoid)
        assertEquals(listOf("Elsewhere"), names(ranked))
    }

    // --- the road line ---------------------------------------------------------------------

    @Test fun projectionGivesTheDistanceAlongAndOff() {
        val line = RoadLine(road)
        val p = line.project(45.0 + 1.0 / 111.19, 7.25)
        val expectedAlong = LatLng(45.0, 7.0).distanceTo(LatLng(45.0, 7.25))
        assertEquals(expectedAlong, p.alongM, 30.0)
        assertEquals(1000.0, p.offM, 30.0)
        assertEquals(LatLng(45.0, 7.0).distanceTo(LatLng(45.0, 7.5)), line.lengthM, 1.0)
    }

    @Test fun projectionCanBeBoundedToASection() {
        val loop = listOf(45.0 to 7.0, 45.0 to 7.1, 45.0 to 7.2, 45.0 to 7.1, 45.0 to 7.0)
        val line = RoadLine(loop)
        val out = line.project(45.001, 7.05, fromSegment = 0, toSegment = 1)
        val back = line.project(45.001, 7.05, fromSegment = 2, toSegment = 3)
        assertTrue(out.segment <= 1)
        assertTrue(back.segment >= 2)
        assertTrue(out.alongM < back.alongM)
        // A bound before the start, or past the end, is clamped.
        assertEquals(out.alongM, line.project(45.001, 7.05, fromSegment = -4, toSegment = 1).alongM, 0.0)
        assertEquals(back.alongM, line.project(45.001, 7.05, fromSegment = 2, toSegment = 99).alongM, 0.0)
    }

    @Test fun projectionCanBeAskedFromASegmentOn() {
        // A road that goes out and comes back along the same line: the point is near both ends.
        val loop = listOf(45.0 to 7.0, 45.0 to 7.1, 45.0 to 7.2, 45.0 to 7.1, 45.0 to 7.0)
        val line = RoadLine(loop)
        val first = line.project(45.001, 7.05)
        val later = line.project(45.001, 7.05, fromSegment = 2)
        assertTrue(first.alongM < later.alongM)
        assertEquals(first.offM, later.offM, 1.0)
    }

    // --- helpers ---------------------------------------------------------------------------

    private fun sixSights(offKm: Double = 1.0) =
        listOf(7.05, 7.12, 7.19, 7.26, 7.33, 7.40).mapIndexed { i, lon -> sightAt("S$i", lon, offKm) }
}
