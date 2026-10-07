// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.routesim.experiences

import io.motohub.android.module.ModuleRouteError
import io.motohub.android.module.ModuleRoutePreference
import io.motohub.android.module.ModuleSightKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ExperienceRoutesTest {

    private val routing = ExpFakeRouting()
    private val sights = ExpFakeSights()
    private val routes = ExperienceRoutes(routing, sights)

    @Before fun forgetCaches() = ExperienceRoutes.clearCaches()

    private fun experience(id: String = "xx-test", via: List<ExperiencePlace> = emptyList()) = Experience(
        id = id,
        name = "Test ride",
        description = "A test ride.",
        region = "Test",
        from = ExperiencePlace("A", 45.0, 7.0),
        to = ExperiencePlace("B", 45.0, 7.5),
        via = via,
        km = 39.0,
        minutes = 59.0,
        nature = 50,
        twisty = 50,
        gravel = 0,
        maxElevationM = 1500,
        shape = List(12) { 45.0 to 7.0 + it * 0.04 },
    )

    private fun threeSights() = listOf(
        sightAt("Alpha", 7.10, 1.0), sightAt("Bravo", 7.25, 1.0), sightAt("Charlie", 7.40, 1.0),
    )

    private fun offer(list: List<Sight>, complete: Boolean = true) {
        sights.result = sightResult(list, complete)
    }

    // --- the plain route -------------------------------------------------------------------

    @Test fun baseIsTheScenicRouteThroughViaPoints() {
        val via = listOf(ExperiencePlace("Via", 45.0, 7.25))
        val base = routes.base(experience(via = via))
        assertNotNull(base)
        assertEquals(1, routing.routeCalls.size)
        assertEquals(ModuleRoutePreference.SCENIC, routing.preferences.single())
        val (lats, lons) = routing.routeCalls.single()
        assertEquals(3, lats.size)
        assertEquals(listOf(7.0, 7.25, 7.5), lons.toList())
        assertEquals(39.3, base!!.km, 0.3)
        assertTrue(base.minutes > 50.0 && base.minutes < 65.0)
        assertEquals(base.latitudes.size, base.polyline().size)
    }

    @Test fun baseIsCachedPerExperienceAcrossInstances() {
        assertNotNull(routes.base(experience()))
        assertNotNull(routes.base(experience()))
        assertNotNull(ExperienceRoutes(routing, sights).base(experience()))
        assertEquals(1, routing.routeCalls.size)
        assertNotNull(routes.base(experience("xx-other")))
        assertEquals(2, routing.routeCalls.size)
    }

    @Test fun baseIsNullWhenTheRouterFails() {
        routing.handler = { _, _, _ -> routing.refuse(ModuleRouteError.NO_NETWORK) }
        assertNull(routes.base(experience()))
        routing.throwOnRoute = true
        assertNull(routes.base(experience()))
    }

    @Test fun aFailedBaseIsNotCached() {
        routing.handler = { _, _, _ -> routing.refuse(ModuleRouteError.RATE_LIMITED) }
        assertNull(routes.base(experience()))
        routing.handler = null
        assertNotNull(routes.base(experience()))
    }

    // --- the happy path --------------------------------------------------------------------

    @Test fun happyPathAddsTheDetoursInRouteOrder() {
        offer(threeSights())
        val r = routes.build(experience(), ExperienceFilters(detours = 3), alt = 0)

        assertFalse(r.error)
        assertNull(r.note)
        assertTrue(r.complete)
        assertEquals(listOf("A", "Alpha", "Bravo", "Charlie", "B"), r.stops.map { it.label })
        assertEquals(listOf("Alpha", "Bravo", "Charlie"), r.detours.map { it.name })
        assertEquals(45.0, r.stops.first().latitude, 1e-9)
        assertEquals(7.5, r.stops.last().longitude, 1e-9)
        assertEquals(1844, r.maxElevationM)

        // Two route calls (the plain road, then the stops) and one sights search, all scenic.
        assertEquals(2, routing.routeCalls.size)
        assertEquals(1, sights.calls)
        assertTrue(routing.preferences.all { it == ModuleRoutePreference.SCENIC })
        assertEquals(5, routing.routeCalls.last().first.size)

        // km and minutes are those of the final route (a little longer than the plain one).
        val plain = routes.base(experience())!!
        assertTrue(r.km > plain.km)
        assertEquals(r.km / 40.0 * 60.0, r.minutes, 0.5)
    }

    @Test fun catalogueSightsAreUsedWithoutAskingTheHost() {
        sights.result = sightFailure()   // the host would fail: it must not be asked at all
        val e = experience().copy(sights = threeSights())
        val r = routes.build(e, ExperienceFilters(detours = 3), alt = 0)
        assertEquals(0, sights.calls)
        assertNull(r.note)
        assertTrue(r.complete)
        assertEquals(listOf("Alpha", "Bravo", "Charlie"), r.detours.map { it.name })
    }

    @Test fun theSearchAsksForAllKindsWithTheDefaultRadius() {
        offer(threeSights())
        routes.build(experience(), ExperienceFilters(), 0)
        assertEquals(ModuleSightKind.ALL, sights.lastKinds)
        assertEquals(DetourPlanner.DEFAULT_RADIUS_M, sights.lastRadius)
    }

    @Test fun viaPointsAndDetoursAreMergedByPositionAlongTheRoad() {
        offer(listOf(sightAt("Early", 7.10, 1.0), sightAt("Late", 7.40, 1.0)))
        val via = listOf(ExperiencePlace("Via", 45.0, 7.25))
        val r = routes.build(experience(via = via), ExperienceFilters(detours = 2), 0)
        assertEquals(listOf("A", "Early", "Via", "Late", "B"), r.stops.map { it.label })
        assertEquals(listOf("Early", "Late"), r.detours.map { it.name })
    }

    @Test fun twoViaPointsKeepTheirOrder() {
        val via = listOf(ExperiencePlace("V1", 45.0, 7.15), ExperiencePlace("V2", 45.0, 7.35))
        offer(listOf(sightAt("Middle", 7.25, 1.0)))
        val r = routes.build(experience(via = via), ExperienceFilters(detours = 1), 0)
        assertEquals(listOf("A", "V1", "Middle", "V2", "B"), r.stops.map { it.label })
    }

    @Test fun aSightOnAViaPointIsNotOfferedAsADetour() {
        // The pass the experience forces is also a sight: it is already a stop, not a detour.
        val via = listOf(ExperiencePlace("Passo Test", 45.0, 7.25))
        offer(
            listOf(
                sightAt("Passo Test", 7.2502, 0.3, ModuleSightKind.PASS, 2200.0),   // right at the via point
                sightAt("Another Name", 7.2503, 0.2),                                // within 1 km of it
                sightAt("passo test ", 7.40, 1.0),                                  // same name, far away
                sightAt("Real Detour", 7.12, 1.0),
            ),
        )
        val r = routes.build(experience(via = via), ExperienceFilters(detours = 4), 0)
        assertEquals(listOf("Real Detour"), r.detours.map { it.name })
        assertEquals(listOf("A", "Real Detour", "Passo Test", "B"), r.stops.map { it.label })
        assertEquals(r.stops.size, r.stops.map { it.label.lowercase().trim() }.toSet().size)
    }

    @Test fun aSightNamedLikeAOrBIsNotADetour() {
        offer(listOf(sightAt("a", 7.2, 1.0), sightAt("B", 7.3, 1.0), sightAt("Fine", 7.4, 1.0)))
        val r = routes.build(experience(), ExperienceFilters(detours = 3), 0)
        assertEquals(listOf("Fine"), r.detours.map { it.name })
        assertEquals(listOf("A", "Fine", "B"), r.stops.map { it.label })
    }

    @Test fun aDetourStaysBetweenTheViaPointsItLiesBetweenOnALoop() {
        // A loop: out east along 45.00 to a via point, then back west along 45.05 to B near A.
        val loop = ArrayList<Pair<Double, Double>>()
        for (i in 0..20) loop += 45.0 to 7.0 + i * 0.01
        for (i in 1..20) loop += 45.05 to 7.2 - i * 0.01
        routing.handler = { _, _, _ ->
            val lats = DoubleArray(loop.size) { loop[it].first }
            val lons = DoubleArray(loop.size) { loop[it].second }
            routing.answer(lats, lons, 60.0, 90.0)
        }
        val exp = experience(via = listOf(ExperiencePlace("Far", 45.0, 7.2))).copy(
            to = ExperiencePlace("B", 45.05, 7.0),
        )
        // Near the return leg only: after the via point, whatever lies where on the road.
        offer(listOf(Sight("Return", 45.05 + 1.0 / 111.19, 7.10, ModuleSightKind.VIEWPOINT)))
        val back = routes.build(exp, ExperienceFilters(detours = 1, maxKm = 500, maxMinutes = 900), 0)
        assertEquals(listOf("A", "Far", "Return", "B"), back.stops.map { it.label })
        assertEquals(listOf("Return"), back.detours.map { it.name })

        ExperienceRoutes.clearCaches()
        // Near the outbound leg only: before the via point.
        offer(listOf(sightAt("Outbound", 7.10, 1.0)))
        val out = routes.build(exp, ExperienceFilters(detours = 1, maxKm = 500, maxMinutes = 900), 0)
        assertEquals(listOf("A", "Outbound", "Far", "B"), out.stops.map { it.label })
    }

    @Test fun culturePicksTheDetours() {
        offer(listOf(sightAt("Lookout", 7.2, 1.0), sightAt("Castle", 7.3, 1.0, ModuleSightKind.HERITAGE)))
        val scenery = routes.build(experience(), ExperienceFilters(detours = 1, culture = 0), 0)
        val culture = routes.build(experience(), ExperienceFilters(detours = 1, culture = 100), 0)
        assertEquals(listOf("Lookout"), scenery.detours.map { it.name })
        assertEquals(listOf("Castle"), culture.detours.map { it.name })
    }

    @Test fun altGivesADifferentSet() {
        offer((0 until 9).map { sightAt("S$it", 7.05 + it * 0.05, 0.5 + it * 0.2) })
        val best = routes.build(experience(), ExperienceFilters(detours = 3), 0)
        val other = routes.build(experience(), ExperienceFilters(detours = 3), 1)
        assertFalse(other.error)
        assertEquals(3, other.detours.size)
        assertTrue(best.detours.map { it.name } != other.detours.map { it.name })
    }

    @Test fun zeroDetoursIsThePlainRouteWithoutAskingForSights() {
        val r = routes.build(experience(), ExperienceFilters(detours = 0), 0)
        assertFalse(r.error)
        assertNull(r.note)
        assertEquals(listOf("A", "B"), r.stops.map { it.label })
        assertTrue(r.detours.isEmpty())
        assertEquals(0, sights.calls)
        assertEquals(1, routing.routeCalls.size)
        assertEquals(1844, r.maxElevationM)
    }

    @Test fun nothingFoundIsThePlainRouteWithANote() {
        offer(emptyList())
        val r = routes.build(experience(), ExperienceFilters(detours = 3), 0)
        assertFalse(r.error)
        assertEquals(listOf("A", "B"), r.stops.map { it.label })
        assertEquals(RouteNotes.SIGHTS_UNAVAILABLE, r.note)
        assertEquals(1, routing.routeCalls.size)
    }

    @Test fun anEmptySearchIsUnavailableAndNeitherItNorItsResultIsCached() {
        // The host turns chunks it could not fetch into an empty list, ok = true: an outage, not "nothing here".
        offer(emptyList())
        val filters = ExperienceFilters(detours = 3)
        val first = routes.build(experience(), filters, 0)
        assertEquals(RouteNotes.SIGHTS_UNAVAILABLE, first.note)
        assertEquals(1, sights.calls)

        offer(threeSights())   // the outage is over
        routes.resetBreaker()   // the rider asked again
        val again = routes.build(experience(), filters, 0)
        assertEquals(2, sights.calls)
        assertEquals(3, again.detours.size)
        assertNull(again.note)

        // And the good answer is what is remembered from now on.
        routes.build(experience(), filters, 0)
        assertEquals(2, sights.calls)
    }

    @Test fun noDetoursIsRememberedOnlyWhenSightsWereFoundButNonePassed() {
        // A sight 9 km from the road is outside the 4 km radius: found, but nothing worth a detour.
        offer(listOf(sightAt("Remote", 7.25, 9.0)))
        val filters = ExperienceFilters(detours = 3)
        val first = routes.build(experience(), filters, 0)
        assertEquals(RouteNotes.NO_DETOURS, first.note)
        assertTrue(first.detours.isEmpty())
        val calls = routing.routeCalls.size
        val again = routes.build(experience(), filters, 0)
        assertEquals(RouteNotes.NO_DETOURS, again.note)
        assertEquals(1, sights.calls)
        assertEquals(calls, routing.routeCalls.size)
    }

    @Test fun aPartialSearchIsNotRememberedWhenNothingPassed() {
        offer(listOf(sightAt("Remote", 7.25, 9.0)), complete = false)
        val filters = ExperienceFilters(detours = 3)
        assertEquals(RouteNotes.PARTIAL_SEARCH, routes.build(experience(), filters, 0).note)
        routes.build(experience(), filters, 0)
        assertEquals(2, sights.calls)
    }

    @Test fun anIncompleteSearchIsSaidSo() {
        offer(threeSights(), complete = false)
        val r = routes.build(experience(), ExperienceFilters(detours = 3), 0)
        assertFalse(r.error)
        assertFalse(r.complete)
        assertNotNull(r.note)
        assertEquals(3, r.detours.size)
    }

    @Test fun aBaseOverTheLimitGetsNoDetoursAndNoFinalRoute() {
        offer(threeSights())
        val r = routes.build(experience(), ExperienceFilters(maxKm = 30, detours = 3), 0)
        assertFalse(r.error)
        assertTrue(r.detours.isEmpty())
        assertEquals(1, routing.routeCalls.size)
        assertEquals(0, sights.calls)
        assertNotNull(r.note)
    }

    // --- sights failing --------------------------------------------------------------------

    @Test fun sightsFailingGiveThePlainRouteAndANote() {
        sights.result = sightFailure()
        val r = routes.build(experience(), ExperienceFilters(detours = 3), 0)
        assertFalse(r.error)
        assertNotNull(r.note)
        assertTrue(r.detours.isEmpty())
        assertEquals(listOf("A", "B"), r.stops.map { it.label })
        assertEquals(39.3, r.km, 0.3)
        assertEquals(1, routing.routeCalls.size)
    }

    @Test fun sightsThrowingGiveThePlainRouteToo() {
        sights.throws = true
        val r = routes.build(experience(), ExperienceFilters(detours = 3), 0)
        assertFalse(r.error)
        assertNotNull(r.note)
        assertEquals(2, r.stops.size)
    }

    @Test fun aSightsFailureIsNotRemembered() {
        sights.result = sightFailure()
        routes.build(experience(), ExperienceFilters(detours = 3), 0)
        offer(threeSights())
        routes.resetBreaker()
        val again = routes.build(experience(), ExperienceFilters(detours = 3), 0)
        assertEquals(2, sights.calls)
        assertEquals(3, again.detours.size)
        assertNull(again.note)
    }

    // --- the final route failing or too long -----------------------------------------------

    @Test fun aFailingFinalRouteFallsBackToThePlainRoute() {
        offer(threeSights())
        routing.handler = { n, _, _ -> if (n >= 2) routing.refuse(ModuleRouteError.RATE_LIMITED) else null }
        val r = routes.build(experience(), ExperienceFilters(detours = 3), 0)
        assertFalse(r.error)
        assertNotNull(r.note)
        assertEquals(listOf("A", "B"), r.stops.map { it.label })
        assertTrue(r.detours.isEmpty())
        // The plain road, then the stops with three detours, with two, with one.
        assertEquals(4, routing.routeCalls.size)
        assertEquals(listOf(2, 5, 4, 3), routing.routeCalls.map { it.first.size })
    }

    @Test fun noNetworkIsNotRetried() {
        offer(threeSights())
        routing.handler = { n, _, _ -> if (n >= 2) routing.refuse(ModuleRouteError.NO_NETWORK) else null }
        val r = routes.build(experience(), ExperienceFilters(detours = 3), 0)
        assertFalse(r.error)
        assertEquals(2, r.stops.size)
        assertEquals(2, routing.routeCalls.size)
    }

    @Test fun aRetrySucceedingKeepsTheRemainingDetours() {
        offer(threeSights())
        routing.handler = { n, _, _ -> if (n in 2..2) routing.refuse(ModuleRouteError.OTHER) else null }
        val r = routes.build(experience(), ExperienceFilters(detours = 3), 0)
        assertFalse(r.error)
        assertEquals(2, r.detours.size)
        assertEquals(3, routing.routeCalls.size)
    }

    @Test fun overBudgetDropsTheLowestRankedDetourAndRetries() {
        // Alpha, Bravo, Charlie are 0.2, 0.3 and 0.4 km off the road: Charlie ranks last.
        offer(listOf(sightAt("Alpha", 7.10, 0.2), sightAt("Bravo", 7.25, 0.3), sightAt("Charlie", 7.40, 0.4)))
        routing.handler = { n, lats, lons ->
            // The route with all three detours comes out at 50 km, over the 45 km limit.
            if (n >= 2 && lats.size >= 5) routing.answer(lats, lons, 50.0, 60.0) else null
        }
        val r = routes.build(experience(), ExperienceFilters(maxKm = 45, detours = 3), 0)
        assertFalse(r.error)
        assertEquals(listOf("Alpha", "Bravo"), r.detours.map { it.name })
        assertEquals(listOf("A", "Alpha", "Bravo", "B"), r.stops.map { it.label })
        assertEquals(3, routing.routeCalls.size)
        assertTrue(r.km <= 45.0)
    }

    @Test fun overTheMinutesLimitDropsDetoursToo() {
        offer(listOf(sightAt("Alpha", 7.10, 0.2), sightAt("Bravo", 7.25, 0.3), sightAt("Charlie", 7.40, 0.4)))
        routing.handler = { n, lats, lons ->
            if (n >= 2 && lats.size >= 4) routing.answer(lats, lons, 40.0, 70.0) else null
        }
        val r = routes.build(experience(), ExperienceFilters(maxMinutes = 65, detours = 3), 0)
        assertFalse(r.error)
        assertEquals(listOf("Alpha"), r.detours.map { it.name })
        assertEquals(4, routing.routeCalls.size)
    }

    @Test fun neverFittingFallsBackToThePlainRouteAfterTwoRetries() {
        routing.handler = { n, lats, lons ->
            if (n >= 2) routing.answer(lats, lons, 60.0, 90.0) else null
        }
        offer(listOf(sightAt("Alpha", 7.10, 0.3), sightAt("Bravo", 7.25, 0.3), sightAt("Charlie", 7.40, 0.3)))
        val r = routes.build(experience(), ExperienceFilters(maxKm = 45, detours = 3), 0)
        assertFalse(r.error)
        assertTrue(r.detours.isEmpty())
        assertEquals(listOf("A", "B"), r.stops.map { it.label })
        assertNotNull(r.note)
        assertEquals(39.3, r.km, 0.3)
        assertEquals(4, routing.routeCalls.size)   // the plain road + 3 and 2 and 1 detours
    }

    @Test fun aRouteThatWasLongAlreadyIsNotDroppedForBeingLong() {
        // The plain road is over the limit: no detours are planned, so nothing is over by detours.
        offer(threeSights())
        val r = routes.build(experience(), ExperienceFilters(maxKm = 20, detours = 3), 0)
        assertFalse(r.error)
        assertEquals(2, r.stops.size)
        assertNotNull(r.note)
        assertEquals(0, sights.calls)
    }

    // --- elevation -------------------------------------------------------------------------

    @Test fun elevationFailingDoesNotFailTheBuild() {
        offer(threeSights())
        routing.elevation = { _, _ -> null }
        val r = routes.build(experience(), ExperienceFilters(detours = 3), 0)
        assertFalse(r.error)
        assertNull(r.maxElevationM)
        assertEquals(3, r.detours.size)
    }

    @Test fun elevationThrowingDoesNotFailTheBuild() {
        offer(threeSights())
        routing.throwOnElevation = true
        val r = routes.build(experience(), ExperienceFilters(detours = 3), 0)
        assertFalse(r.error)
        assertNull(r.maxElevationM)
    }

    @Test fun nanElevationsAreIgnored() {
        routing.elevation = { lats, _ -> DoubleArray(lats.size) { if (it == 5) 987.6 else Double.NaN } }
        val r = routes.build(experience(), ExperienceFilters(detours = 0), 0)
        assertEquals(988, r.maxElevationM)
        routing.elevation = { lats, _ -> DoubleArray(lats.size) { Double.NaN } }
        val none = routes.build(experience("xx-nan"), ExperienceFilters(detours = 0), 0)
        assertNull(none.maxElevationM)
    }

    @Test fun longRoadsAreThinnedForTheSearchAndTheElevations() {
        routing.piecesPerLeg = 1500
        offer(emptyList())
        val r = routes.build(experience(), ExperienceFilters(detours = 3), 0)
        assertFalse(r.error)
        assertEquals(ExperienceRoutes.MAX_SEARCH_POINTS, sights.lastPointCount)
        assertEquals(ExperienceRoutes.MAX_ELEVATION_SAMPLES, routing.elevationSizes.single())
    }

    @Test fun thinningKeepsTheEndsAndIsMonotonic() {
        val idx = ExperienceRoutes.thinIndices(1501, 500)
        assertEquals(500, idx.size)
        assertEquals(0, idx.first())
        assertEquals(1500, idx.last())
        for (i in 1 until idx.size) assertTrue(idx[i] > idx[i - 1])
        assertEquals(listOf(0, 1, 2), ExperienceRoutes.thinIndices(3, 500).toList())
    }

    // --- caching ---------------------------------------------------------------------------

    @Test fun theSecondIdenticalBuildMakesNoHostCalls() {
        offer(threeSights())
        val filters = ExperienceFilters(detours = 3)
        val first = routes.build(experience(), filters, 0)
        val routeCalls = routing.routeCalls.size
        val elevationCalls = routing.elevationSizes.size
        val sightCalls = sights.calls
        val second = routes.build(experience(), filters, 0)
        assertEquals(routeCalls, routing.routeCalls.size)
        assertEquals(elevationCalls, routing.elevationSizes.size)
        assertEquals(sightCalls, sights.calls)
        assertEquals(first.stops.map { it.label }, second.stops.map { it.label })
        assertEquals(first.km, second.km, 0.0)
    }

    @Test fun theCacheSurvivesANewInstance() {
        offer(threeSights())
        val filters = ExperienceFilters(detours = 3)
        routes.build(experience(), filters, 0)
        val calls = routing.routeCalls.size
        ExperienceRoutes(routing, sights).build(experience(), filters, 0)
        assertEquals(calls, routing.routeCalls.size)
    }

    @Test fun theCacheKeyIsTheExperienceAndTheFiltersThatMatter() {
        offer(threeSights())
        routes.build(experience(), ExperienceFilters(detours = 3), 0)
        val c0 = routing.routeCalls.size
        // Km, minutes, culture, detours and alt each make a new entry (one final route each)...
        routes.build(experience(), ExperienceFilters(detours = 2), 0)
        routes.build(experience(), ExperienceFilters(detours = 3, culture = 10), 0)
        routes.build(experience(), ExperienceFilters(detours = 3, maxKm = 300), 0)
        routes.build(experience(), ExperienceFilters(detours = 3, maxMinutes = 300), 0)
        routes.build(experience(), ExperienceFilters(detours = 3), 1)
        assertEquals(c0 + 5, routing.routeCalls.size)
        // ...so does another experience (its plain road and its final route)...
        routes.build(experience("xx-other"), ExperienceFilters(detours = 3), 0)
        assertEquals(c0 + 7, routing.routeCalls.size)
        // ...and the ones that do not affect the detours do not.
        routes.build(experience(), ExperienceFilters(detours = 3, minKm = 10, minMinutes = 10, nature = 30, twisty = 70, gravel = 5, region = "x"), 0)
        assertEquals(c0 + 7, routing.routeCalls.size)
    }

    @Test fun theSightsAreSearchedOncePerRoadWhateverTheFilters() {
        offer(threeSights())
        routes.build(experience(), ExperienceFilters(detours = 3), 0)
        routes.build(experience(), ExperienceFilters(detours = 2), 0)
        routes.build(experience(), ExperienceFilters(detours = 3, culture = 10), 0)
        routes.build(experience(), ExperienceFilters(detours = 3, maxKm = 300), 0)
        routes.build(experience(), ExperienceFilters(detours = 3), 1)
        assertEquals(1, sights.calls)
        // Another road is another search, and so is another radius.
        routes.build(experience("xx-other"), ExperienceFilters(detours = 3), 0)
        assertEquals(2, sights.calls)
        ExperienceRoutes(routing, sights, radiusM = 2_000).build(experience(), ExperienceFilters(detours = 1), 0)
        assertEquals(3, sights.calls)
        assertEquals(2_000, sights.lastRadius)
        // The cache is process-wide: a new instance reuses the first search.
        ExperienceRoutes(routing, sights).build(experience(), ExperienceFilters(detours = 1, maxKm = 400), 0)
        assertEquals(3, sights.calls)
    }

    @Test fun aSightsFailureIsNeverPutInTheSightsCache() {
        sights.throws = true
        routes.build(experience(), ExperienceFilters(detours = 3), 0)
        sights.throws = false
        sights.result = sightFailure()
        routes.resetBreaker()
        routes.build(experience(), ExperienceFilters(detours = 3), 0)
        offer(threeSights())
        routes.resetBreaker()
        assertEquals(3, routes.build(experience(), ExperienceFilters(detours = 3), 0).detours.size)
        assertEquals(3, sights.calls)
    }

    @Test fun aCatalogueUpdateUnderTheSameIdIsNotServedFromTheOldCaches() {
        offer(threeSights())
        val filters = ExperienceFilters(detours = 3)
        val old = routes.build(experience(), filters, 0)
        val calls = routing.routeCalls.size
        val sightCalls = sights.calls

        // Same id, a via point added: a different road.
        val updated = experience(via = listOf(ExperiencePlace("Via", 45.0, 7.25)))
        val base = routes.base(updated)
        assertNotNull(base)
        assertEquals(calls + 1, routing.routeCalls.size)
        assertEquals(3, routing.routeCalls.last().first.size)
        val fresh = routes.build(updated, filters, 0)
        assertTrue(fresh.stops.any { it.label == "Via" })
        assertFalse(old.stops.any { it.label == "Via" })
        assertEquals(sightCalls + 1, sights.calls)   // the new road is searched again

        // The same experience again is served from the caches.
        val after = routing.routeCalls.size
        routes.build(updated, filters, 0)
        assertEquals(after, routing.routeCalls.size)
    }

    @Test fun theCacheKeepsTheMostRecentFortyResults() {
        offer(threeSights())
        val filters = ExperienceFilters(detours = 3)
        // The plain road is routed once, then every result adds one final route.
        fun finals() = routing.routeCalls.size - 1
        for (alt in 0 until ExperienceRoutes.CACHE_SIZE) routes.build(experience(), filters, alt)
        assertEquals(ExperienceRoutes.CACHE_SIZE, finals())
        // Touch the oldest, then add one more: the oldest survives and the next one is evicted.
        routes.build(experience(), filters, 0)
        assertEquals(ExperienceRoutes.CACHE_SIZE, finals())
        routes.build(experience(), filters, ExperienceRoutes.CACHE_SIZE)
        assertEquals(ExperienceRoutes.CACHE_SIZE + 1, finals())
        routes.build(experience(), filters, 0)
        assertEquals(ExperienceRoutes.CACHE_SIZE + 1, finals())
        routes.build(experience(), filters, 1)
        assertEquals(ExperienceRoutes.CACHE_SIZE + 2, finals())
        assertEquals(1, sights.calls)
    }

    @Test fun anErrorIsNotCached() {
        routing.handler = { _, _, _ -> routing.refuse(ModuleRouteError.NO_NETWORK) }
        assertTrue(routes.build(experience(), ExperienceFilters(), 0).error)
        routing.handler = null
        offer(threeSights())
        assertFalse(routes.build(experience(), ExperienceFilters(), 0).error)
    }

    // --- errors ----------------------------------------------------------------------------

    @Test fun noNetworkOnTheBaseRouteIsAnError() {
        routing.handler = { _, _, _ -> routing.refuse(ModuleRouteError.NO_NETWORK) }
        val r = routes.build(experience(), ExperienceFilters(), 0)
        assertTrue(r.error)
        assertTrue(r.note!!.contains("internet", ignoreCase = true))
        assertEquals(listOf("A", "B"), r.stops.map { it.label })
        assertEquals(39.0, r.km, 0.0)
        assertEquals(59.0, r.minutes, 0.0)
        assertEquals(1500, r.maxElevationM)
        assertFalse(r.complete)
        assertEquals(0, sights.calls)
    }

    @Test fun aBusyServerIsAnErrorThatSaysSo() {
        routing.handler = { _, _, _ -> routing.refuse(ModuleRouteError.RATE_LIMITED, "slow down") }
        val r = routes.build(experience(), ExperienceFilters(), 0)
        assertTrue(r.error)
        assertTrue(r.note!!.contains("busy", ignoreCase = true))
    }

    @Test fun anyOtherFailureIsAnError() {
        routing.handler = { _, _, _ -> routing.refuse(ModuleRouteError.OTHER) }
        val r = routes.build(experience(), ExperienceFilters(), 0)
        assertTrue(r.error)
        assertFalse(r.note.isNullOrBlank())
        routing.handler = { _, _, _ -> routing.refuse(ModuleRouteError.NONE) }
        assertTrue(routes.build(experience("xx-none"), ExperienceFilters(), 0).error)
    }

    @Test fun neverThrows() {
        routing.throwOnRoute = true
        sights.throws = true
        routing.throwOnElevation = true
        val r = routes.build(experience(), ExperienceFilters(detours = 6), alt = 7)
        assertTrue(r.error)
        assertFalse(r.note.isNullOrBlank())

        // A router that answers nonsense is an error, not a crash.
        routing.throwOnRoute = false
        routing.handler = { _, _, _ -> routing.answer(DoubleArray(1), DoubleArray(1), 1.0, 1.0) }
        assertTrue(routes.build(experience("xx-nonsense"), ExperienceFilters(), 0).error)
        routing.handler = { _, lats, _ -> routing.answer(lats, DoubleArray(1), 1.0, 1.0) }
        assertTrue(routes.build(experience("xx-mismatch"), ExperienceFilters(), 0).error)
    }

    @Test fun aFinalRouteThatThrowsFallsBackToThePlainRoute() {
        offer(threeSights())
        routing.handler = { n, _, _ -> if (n >= 2) throw IllegalStateException("boom") else null }
        val r = routes.build(experience(), ExperienceFilters(detours = 3), 0)
        assertFalse(r.error)
        assertEquals(2, r.stops.size)
        assertNotNull(r.note)
    }

    @Test fun nonsenseSightsAreIgnored() {
        sights.result = sightResult(
            listOf(
                Sight("", 45.01, 7.1, ModuleSightKind.VIEWPOINT),
                Sight("NaN", Double.NaN, 7.2, ModuleSightKind.VIEWPOINT),
                Sight("Kind", 45.01, 7.3, 77),
                sightAt("Fine", 7.4, 1.0),
            ),
        )
        val r = routes.build(experience(), ExperienceFilters(detours = 3), 0)
        assertFalse(r.error)
        assertEquals(listOf("Fine"), r.detours.map { it.name })
    }

    @Test fun anExperienceWithoutKmInTheRouterAnswerStillGetsAFigure() {
        routing.handler = { _, lats, lons -> routing.answer(lats, lons, 0.0, 0.0) }
        val base = routes.base(experience())!!
        assertTrue(base.km > 0.0)
        assertTrue(base.minutes > 0.0)
    }
}
