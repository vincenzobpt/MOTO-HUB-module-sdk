// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.routesim.ui

import io.motohub.android.routesim.core.Stop
import io.motohub.android.routesim.experiences.Experience
import io.motohub.android.routesim.experiences.ExperienceFilters
import io.motohub.android.routesim.experiences.ExperiencePlace
import io.motohub.android.routesim.experiences.ExperienceRoute
import io.motohub.android.routesim.experiences.Mismatch
import io.motohub.android.routesim.experiences.RankedExperience
import io.motohub.android.routesim.experiences.RouteNotes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperiencesLogicTest {

    private fun exp(
        id: String,
        km: Double = 160.0,
        minutes: Double = 210.0,
        nature: Int = 80,
        twisty: Int = 80,
        gravel: Int = 0,
        elevation: Int = 2239,
        region: String = "Dolomites",
        via: List<ExperiencePlace> = emptyList(),
    ) = Experience(
        id = id, name = "Ride $id", description = "d", region = region,
        from = ExperiencePlace("Start $id", 46.54, 12.13), to = ExperiencePlace("End $id", 46.49, 11.35), via = via,
        km = km, minutes = minutes, nature = nature, twisty = twisty, gravel = gravel, maxElevationM = elevation,
        shape = List(12) { 46.0 + it * 0.05 to 11.0 + it * 0.05 },
    )

    private fun ranked(e: Experience, mismatches: List<Mismatch> = emptyList(), tags: List<String> = listOf("Mountain", "Bends", "Nature")) =
        RankedExperience(e, 1.0, mismatches, tags)

    private fun route(
        km: Double = 175.0,
        minutes: Double = 230.0,
        note: String? = null,
        error: Boolean = false,
        stops: List<Stop> = listOf(Stop(1.0, 2.0, "A"), Stop(1.5, 2.5, "Detour"), Stop(3.0, 4.0, "B")),
        elevation: Int? = 2400,
    ) = ExperienceRoute(stops, emptyList(), km, minutes, elevation, true, note, error)

    private fun result(filters: ExperienceFilters, alt: Int = 0, route: ExperienceRoute = route()) =
        CardResult(ExperiencesLogic.routeKey(filters, alt), route)

    // ---- numbers

    @Test
    fun printsKmDurationAndElevation() {
        assertEquals("160 km", ExperiencesLogic.kmText(159.6))
        assertEquals("42 km", ExperiencesLogic.kmText(42.2))
        assertEquals("3h 30m", ExperiencesLogic.durationText(210.0))
        assertEquals("1h 05m", ExperiencesLogic.durationText(65.0))
        assertEquals("45 min", ExperiencesLogic.durationText(45.0))
        assertEquals("max 2239 m", ExperiencesLogic.elevationText(2239))
        assertNull(ExperiencesLogic.elevationText(null))
        assertNull(ExperiencesLogic.elevationText(0))
    }

    // ---- what a card misses

    @Test
    fun saysHowMuchTooLongOrTooShort() {
        val e = exp("a")
        val f = ExperienceFilters(maxKm = 120, minMinutes = 240)
        assertEquals("42 km longer than your maximum", ExperiencesLogic.mismatchLabel(Mismatch.Km(42), f, e))
        assertEquals("12 km shorter than your minimum", ExperiencesLogic.mismatchLabel(Mismatch.Km(-12), f, e))
        assertEquals("1h 10m longer than your maximum", ExperiencesLogic.mismatchLabel(Mismatch.Minutes(70), f, e))
        assertEquals("20 min shorter than your minimum", ExperiencesLogic.mismatchLabel(Mismatch.Minutes(-20), f, e))
    }

    @Test
    fun saysWhichWayASliderIsMissed() {
        val city = exp("a", nature = 10, twisty = 10, gravel = 0)
        val wild = exp("b", nature = 95, twisty = 95, gravel = 90)
        val wantsWild = ExperienceFilters(nature = 90, twisty = 90, gravel = 80)
        val wantsCity = ExperienceFilters(nature = 5, twisty = 5, gravel = 0)
        assertEquals("Not much nature", ExperiencesLogic.mismatchLabel(Mismatch.Nature, wantsWild, city))
        assertEquals("Not many bends", ExperiencesLogic.mismatchLabel(Mismatch.Twisty, wantsWild, city))
        assertEquals("Not much gravel", ExperiencesLogic.mismatchLabel(Mismatch.Gravel, wantsWild, city))
        assertEquals("Not much city", ExperiencesLogic.mismatchLabel(Mismatch.Nature, wantsCity, wild))
        assertEquals("Too many bends", ExperiencesLogic.mismatchLabel(Mismatch.Twisty, wantsCity, wild))
        assertEquals("Too much gravel", ExperiencesLogic.mismatchLabel(Mismatch.Gravel, wantsCity, wild))
    }

    @Test
    fun namesTheRegionThatWasAsked() {
        val f = ExperienceFilters(region = "Tuscany")
        assertEquals("Not in Tuscany", ExperiencesLogic.mismatchLabel(Mismatch.Region, f, exp("a")))
        assertEquals("Another region", ExperiencesLogic.mismatchLabel(Mismatch.Region, ExperienceFilters(), exp("a")))
    }

    @Test
    fun theLineShowsTheFirstAndCountsTheRest() {
        val e = exp("a")
        val f = ExperienceFilters(maxKm = 100)
        assertNull(ExperiencesLogic.mismatchLine(emptyList(), f, e))
        assertEquals("60 km longer than your maximum", ExperiencesLogic.mismatchLine(listOf(Mismatch.Km(60)), f, e))
        assertEquals(
            "60 km longer than your maximum · +2 more",
            ExperiencesLogic.mismatchLine(listOf(Mismatch.Km(60), Mismatch.Nature, Mismatch.Gravel), f, e),
        )
    }

    @Test
    fun realNumbersReplaceTheKmAndTimeVerdicts() {
        val e = exp("a", km = 240.0, minutes = 300.0)
        val f = ExperienceFilters(maxKm = 200, maxMinutes = 280)
        val r = ranked(e, listOf(Mismatch.Km(40), Mismatch.Minutes(20), Mismatch.Nature))
        // Without a real route, or with a failed one, the catalogue's verdict stands.
        assertEquals(r.mismatches, ExperiencesLogic.mismatchesFor(r, f, null))
        assertEquals(r.mismatches, ExperiencesLogic.mismatchesFor(r, f, route(error = true)))
        // The detour-free road is 190 km and 250 min: both limits are met, the slider is still missed.
        assertEquals(listOf<Mismatch>(Mismatch.Nature), ExperiencesLogic.mismatchesFor(r, f, route(km = 190.0, minutes = 250.0)))
        // A longer real route misses the limits by the real amounts.
        assertEquals(
            listOf<Mismatch>(Mismatch.Km(55), Mismatch.Minutes(30), Mismatch.Nature),
            ExperiencesLogic.mismatchesFor(r, f, route(km = 255.0, minutes = 310.0)),
        )
        // Below a minimum is a negative amount.
        assertEquals(
            listOf<Mismatch>(Mismatch.Km(-20)),
            ExperiencesLogic.mismatchesFor(ranked(e), ExperienceFilters(minKm = 100), route(km = 80.0, minutes = 100.0)),
        )
    }

    // ---- cards

    @Test
    fun aCardStartsFromTheCatalogueAndTakesTheRealNumbers() {
        val e = exp("a", km = 160.0, minutes = 210.0, elevation = 2239)
        val f = ExperienceFilters()
        val r = ranked(e)

        val before = ExperiencesLogic.cardView(r, f, 0, null, busy = true)
        assertEquals("160 km", before.kmText)
        assertEquals("3h 30m", before.durationText)
        assertEquals("max 2239 m", before.elevationText)
        assertTrue(before.loading)
        assertFalse(before.real)
        assertEquals("Ride a", before.name)
        assertEquals("Dolomites", before.region)

        val after = ExperiencesLogic.cardView(r, f, 0, result(f, 0, route(km = 175.4, minutes = 230.0, elevation = 2400)), busy = false)
        assertEquals("175 km", after.kmText)
        assertEquals("3h 50m", after.durationText)
        assertEquals("max 2400 m", after.elevationText)
        assertFalse(after.loading)
        assertTrue(after.real)
    }

    @Test
    fun aRouteForOtherFiltersOrAnotherIdeaIsNotShown() {
        val e = exp("a")
        val f = ExperienceFilters(detours = 3)
        val stale = result(f.copy(detours = 5), 0, route(km = 300.0))
        val view = ExperiencesLogic.cardView(ranked(e), f, 0, stale, busy = true)
        assertEquals("160 km", view.kmText)
        assertTrue(view.loading)
        val otherIdea = result(f, 0, route(km = 300.0))
        assertEquals("160 km", ExperiencesLogic.cardView(ranked(e), f, 1, otherIdea, busy = false).kmText)
        // Filters that do not change the route do not make it stale.
        val moreNature = f.copy(nature = 90, twisty = 20, region = "Tuscany", minKm = 50)
        assertEquals("300 km", ExperiencesLogic.cardView(ranked(e), moreNature, 0, otherIdea, busy = false).kmText)
    }

    @Test
    fun aFailedRouteKeepsTheCatalogueNumbersAndStopsTheProgressMark() {
        val e = exp("a")
        val f = ExperienceFilters()
        val failed = result(f, 0, route(km = 160.0, error = true, note = RouteNotes.OTHER))
        val view = ExperiencesLogic.cardView(ranked(e), f, 0, failed, busy = false)
        assertEquals("160 km", view.kmText)
        assertFalse(view.real)
        assertFalse(view.loading)
        // Even while the queue still lists it, a card that has its (failed) answer is not shown as loading.
        assertFalse(ExperiencesLogic.cardView(ranked(e), f, 0, failed, busy = true).loading)
    }

    @Test
    fun theCardCarriesTagsMismatchAndIdeaCounter() {
        val e = exp("a", km = 240.0)
        val f = ExperienceFilters(maxKm = 200, detours = 2)
        val r = ranked(e, listOf(Mismatch.Km(40)), tags = listOf("Gravel", "Mountain", "Bends", "Nature", "Long"))
        val first = ExperiencesLogic.cardView(r, f, 0, null, busy = false)
        assertEquals(listOf("Gravel", "Mountain", "Bends", "Nature"), first.tags)
        assertEquals("40 km longer than your maximum", first.mismatchLine)
        assertNull(first.ideaText)
        assertTrue(first.canOtherIdeas)
        assertEquals("Idea 3", ExperiencesLogic.cardView(r, f, 2, null, busy = false).ideaText)
        assertFalse(ExperiencesLogic.cardView(r, f.copy(detours = 0), 0, null, busy = false).canOtherIdeas)
    }

    @Test
    fun otherIdeasCountUpToTheLimitOfTheDetourPlanner() {
        assertEquals(1, ExperiencesLogic.nextAlt(0))
        assertEquals(5, ExperiencesLogic.nextAlt(4))
        assertEquals(1, ExperiencesLogic.nextAlt(-3))
        assertEquals(60, ExperiencesLogic.nextAlt(60))
        assertNull(ExperiencesLogic.ideaText(0))
        assertEquals("Idea 2", ExperiencesLogic.ideaText(1))
    }

    // ---- which routes to ask for

    private fun ten() = (0 until 10).map { ranked(exp("xx-%02d".format(it))) }

    @Test
    fun asksForTheCardsOnScreenAndOneBelowNearestTheTopFirst() {
        val cards = ten()
        val f = ExperienceFilters()
        val visible = setOf("xx-02", "xx-03", "xx-04")
        val ids = ExperiencesLogic.wanted(cards, emptyMap(), f, emptyMap(), visible).map { it.id }
        assertEquals(listOf("xx-02", "xx-03", "xx-04", "xx-05"), ids)
        assertEquals(
            listOf("xx-02", "xx-03", "xx-04"),
            ExperiencesLogic.wanted(cards, emptyMap(), f, emptyMap(), visible, lookAhead = 0).map { it.id },
        )
        assertEquals(
            listOf("xx-08", "xx-09"),
            ExperiencesLogic.wanted(cards, emptyMap(), f, emptyMap(), setOf("xx-08", "xx-09")).map { it.id },
        )
    }

    @Test
    fun asksForNothingWhenNothingIsOnScreen() {
        assertTrue(ExperiencesLogic.wanted(ten(), emptyMap(), ExperienceFilters(), emptyMap(), emptySet()).isEmpty())
        assertTrue(ExperiencesLogic.wanted(ten(), emptyMap(), ExperienceFilters(), emptyMap(), setOf("gone")).isEmpty())
        assertTrue(ExperiencesLogic.wanted(emptyList(), emptyMap(), ExperienceFilters(), emptyMap(), setOf("xx-00")).isEmpty())
    }

    @Test
    fun skipsTheCardsThatAlreadyHaveTheirRoute() {
        val cards = ten()
        val f = ExperienceFilters()
        val have = mapOf("xx-00" to result(f), "xx-01" to result(f, 0, route(error = true)))
        val ids = ExperiencesLogic.wanted(cards, emptyMap(), f, have, setOf("xx-00", "xx-01", "xx-02")).map { it.id }
        // A failed answer counts as an answer: it is not asked again until something changes.
        assertEquals(listOf("xx-02", "xx-03"), ids)
    }

    @Test
    fun asksAgainWhenTheFiltersOrTheIdeaChangedTheRoute() {
        val cards = ten()
        val f = ExperienceFilters(detours = 3)
        val have = mapOf("xx-00" to result(f), "xx-01" to result(f))
        val visible = setOf("xx-00", "xx-01")
        assertEquals(listOf("xx-02"), ExperiencesLogic.wanted(cards, emptyMap(), f, have, visible).map { it.id })
        assertEquals(
            listOf("xx-00", "xx-01", "xx-02"),
            ExperiencesLogic.wanted(cards, emptyMap(), f.copy(detours = 4), have, visible).map { it.id },
        )
        assertEquals(
            listOf("xx-00", "xx-02"),
            ExperiencesLogic.wanted(cards, mapOf("xx-00" to 1), f, have, visible).map { it.id },
        )
        // The request carries the alternative asked for.
        assertEquals(1, ExperiencesLogic.wanted(cards, mapOf("xx-00" to 1), f, have, visible).first().alt)
    }

    @Test
    fun aRequestIsTheSameWhenOnlyIrrelevantFiltersDiffer() {
        val e = exp("a")
        val one = RouteRequest(e, ExperienceFilters(nature = 10), 0)
        val two = RouteRequest(e, ExperienceFilters(nature = 90, region = "x"), 0)
        assertEquals(one, two)
        assertEquals(one.hashCode(), two.hashCode())
        assertFalse(one == RouteRequest(e, ExperienceFilters(maxKm = 300), 0))
        assertFalse(one == RouteRequest(e, ExperienceFilters(), 1))
        assertFalse(one == RouteRequest(exp("b"), ExperienceFilters(), 0))
    }

    @Test
    fun waitsAMomentWhenFiltersMoveAndNotWhenTheRiderAsks() {
        assertEquals(500L, ExperiencesLogic.debounceMs(PlanReason.FILTERS))
        assertTrue(ExperiencesLogic.debounceMs(PlanReason.VISIBILITY) in 1L..499L)
        assertEquals(0L, ExperiencesLogic.debounceMs(PlanReason.OTHER_IDEAS))
        assertEquals(0L, ExperiencesLogic.debounceMs(PlanReason.OPEN))
    }

    @Test
    fun aPendingWaitIsNeverReplacedByAShorterOne() {
        val filters = ExperiencesLogic.debounceMs(PlanReason.FILTERS)
        val visibility = ExperiencesLogic.debounceMs(PlanReason.VISIBILITY)
        // Nothing pending: the wait counts from now; no wait is due at once.
        assertEquals(1_500L, ExperiencesLogic.dueAt(null, 1_000L, filters))
        assertEquals(1_000L, ExperiencesLogic.dueAt(null, 1_000L, 0L))
        // A slider moved at t=1000 (due 1500); a card scrolls into view at t=1100: the later deadline stays.
        assertEquals(1_500L, ExperiencesLogic.dueAt(1_500L, 1_100L, visibility))
        // Another slider move restarts the pause from its own moment.
        assertEquals(1_600L, ExperiencesLogic.dueAt(1_500L, 1_100L, filters))
        // A short wait pending and then a longer one: the longer wins.
        assertEquals(1_600L, ExperiencesLogic.dueAt(1_250L, 1_100L, filters))
        // The rider asking for something at once does not cut a pending pause short either.
        assertEquals(1_500L, ExperiencesLogic.dueAt(1_500L, 1_100L, 0L))
        // A deadline already behind us is no deadline.
        assertEquals(2_150L, ExperiencesLogic.dueAt(1_500L, 2_000L, visibility))
        assertEquals(2_000L, ExperiencesLogic.dueAt(1_500L, 2_000L, 0L))
    }

    // ---- offline

    @Test
    fun readsAMissingConnectionFromTheRoutesNote() {
        assertTrue(ExperiencesLogic.isOffline(route(error = true, note = RouteNotes.NO_NETWORK)))
        assertFalse(ExperiencesLogic.isOffline(route(error = true, note = RouteNotes.BUSY)))
        assertFalse(ExperiencesLogic.isOffline(route(note = RouteNotes.NO_DETOURS)))
        assertFalse(ExperiencesLogic.isOffline(route()))
    }

    @Test
    fun oneBannerWhenAnyShownCardIsOffline() {
        val cards = ten()
        val f = ExperienceFilters()
        val offline = route(error = true, note = RouteNotes.NO_NETWORK)
        assertFalse(ExperiencesLogic.offline(cards, emptyMap(), f, emptyMap()))
        assertFalse(ExperiencesLogic.offline(cards, emptyMap(), f, mapOf("xx-00" to result(f))))
        assertTrue(ExperiencesLogic.offline(cards, emptyMap(), f, mapOf("xx-00" to result(f), "xx-03" to result(f, 0, offline))))
        // An answer for other filters, or for a card that is not shown, says nothing about now.
        assertFalse(ExperiencesLogic.offline(cards, emptyMap(), f, mapOf("xx-03" to result(f.copy(detours = 1), 0, offline))))
        assertFalse(ExperiencesLogic.offline(cards, emptyMap(), f, mapOf("elsewhere" to result(f, 0, offline))))
    }

    @Test
    fun plainRoutesThatCouldHaveBeenBetterAreWorthAskingAgain() {
        assertTrue(ExperiencesLogic.isRetryable(route(error = true, note = RouteNotes.NO_NETWORK)))
        assertTrue(ExperiencesLogic.isRetryable(route(error = true, note = RouteNotes.OTHER)))
        assertTrue(ExperiencesLogic.isRetryable(route(error = true, note = RouteNotes.BUSY)))
        assertTrue(ExperiencesLogic.isRetryable(route(note = RouteNotes.SIGHTS_UNAVAILABLE)))
        assertTrue(ExperiencesLogic.isRetryable(route(note = RouteNotes.BUSY)))
        assertTrue(ExperiencesLogic.isRetryable(route(note = RouteNotes.OTHER)))
        assertTrue(ExperiencesLogic.isRetryable(route(note = RouteNotes.DETOURS_FAILED)))
        // Verdicts about the road and the limits stay.
        assertFalse(ExperiencesLogic.isRetryable(route()))
        assertFalse(ExperiencesLogic.isRetryable(route(note = RouteNotes.NO_DETOURS)))
        assertFalse(ExperiencesLogic.isRetryable(route(note = RouteNotes.PARTIAL_SEARCH)))
        assertFalse(ExperiencesLogic.isRetryable(route(note = RouteNotes.BASE_OVER_LIMIT)))
        assertFalse(ExperiencesLogic.isRetryable(route(note = RouteNotes.DETOURS_TOO_LONG)))
    }

    @Test
    fun theBannerSaysOfflineFirstThenThatSomeDetoursWereMissed() {
        val cards = ten()
        val f = ExperienceFilters()
        val offline = route(error = true, note = RouteNotes.NO_NETWORK)
        val noSights = route(note = RouteNotes.SIGHTS_UNAVAILABLE)
        val busy = route(error = true, note = RouteNotes.BUSY)
        val banner = { results: Map<String, CardResult> -> ExperiencesLogic.banner(cards, emptyMap(), f, results) }
        assertEquals(ExperiencesLogic.Banner.NONE, banner(emptyMap()))
        assertEquals(ExperiencesLogic.Banner.NONE, banner(mapOf("xx-00" to result(f), "xx-01" to result(f, 0, route(note = RouteNotes.NO_DETOURS)))))
        assertEquals(ExperiencesLogic.Banner.RETRY, banner(mapOf("xx-02" to result(f, 0, noSights))))
        assertEquals(ExperiencesLogic.Banner.RETRY, banner(mapOf("xx-02" to result(f, 0, busy))))
        assertEquals(ExperiencesLogic.Banner.RETRY, banner(mapOf("xx-02" to result(f, 0, route(error = true, note = RouteNotes.OTHER)))))
        assertEquals(ExperiencesLogic.Banner.OFFLINE, banner(mapOf("xx-02" to result(f, 0, noSights), "xx-05" to result(f, 0, offline))))
        // Answers for other filters, or for a card that is not shown, do not raise it; and `offline` is only the offline one.
        assertEquals(ExperiencesLogic.Banner.NONE, banner(mapOf("xx-02" to result(f.copy(detours = 1), 0, noSights), "elsewhere" to result(f, 0, noSights))))
        assertFalse(ExperiencesLogic.offline(cards, emptyMap(), f, mapOf("xx-02" to result(f, 0, noSights))))
        assertEquals("Some detours could not be found. Try again.", Strings.DETOURS_BANNER)
        assertEquals("Offline: detours and ride generation are not available.", Strings.OFFLINE_BANNER)
    }

    @Test
    fun retryAlsoDropsThePlainRoutesThatCouldHaveBeenBetter() {
        val f = ExperienceFilters()
        val keep = result(f)
        val verdict = result(f, 0, route(note = RouteNotes.NO_DETOURS))
        val left = ExperiencesLogic.withoutFailures(
            mapOf(
                "ok" to keep, "verdict" to verdict,
                "sights" to result(f, 0, route(note = RouteNotes.SIGHTS_UNAVAILABLE)),
                "busy" to result(f, 0, route(error = true, note = RouteNotes.BUSY)),
                "other" to result(f, 0, route(note = RouteNotes.OTHER)),
                "offline" to result(f, 0, route(error = true, note = RouteNotes.NO_NETWORK)),
            )
        )
        assertEquals(setOf("ok", "verdict"), left.keys)
        // Dropped, they are wanted again.
        val cards = ten()
        val have = mapOf("xx-00" to result(f, 0, route(note = RouteNotes.SIGHTS_UNAVAILABLE)))
        assertEquals(
            listOf("xx-00"),
            ExperiencesLogic.wanted(cards, emptyMap(), f, ExperiencesLogic.withoutFailures(have), setOf("xx-00"), lookAhead = 0).map { it.id },
        )
    }

    @Test
    fun retryDropsOnlyTheFailures() {
        val f = ExperienceFilters()
        val kept = result(f)
        val left = ExperiencesLogic.withoutFailures(mapOf("a" to kept, "b" to result(f, 0, route(error = true))))
        assertEquals(setOf("a"), left.keys)
        assertTrue(left["a"] === kept)
    }

    @Test
    fun anAnswerForOldFiltersOrAnotherIdeaDoesNotOpenTheCard() {
        val f = ExperienceFilters(detours = 3)
        val key = ExperiencesLogic.routeKey(f, 0)
        assertTrue(ExperiencesLogic.answerIsCurrent(key, f, 0))
        // Filters that do not change the route do not make it stale.
        assertTrue(ExperiencesLogic.answerIsCurrent(key, f.copy(nature = 80, region = "Alps"), 0))
        assertFalse(ExperiencesLogic.answerIsCurrent(key, f.copy(detours = 4), 0))
        assertFalse(ExperiencesLogic.answerIsCurrent(key, f.copy(maxKm = 200), 0))
        assertFalse(ExperiencesLogic.answerIsCurrent(key, f.copy(culture = 70), 0))
        assertFalse(ExperiencesLogic.answerIsCurrent(key, f, 1))
        assertEquals(key, RouteRequest(exp("a"), f, 0).key)
    }

    // ---- Plan by hand and the planner's road

    @Test
    fun planByHandKeepsTheRidersOwnStopsOnly() {
        // Stops made by hand (no experience title): kept.
        assertTrue(ExperiencesLogic.byHandKeepsPlanner(true, null))
        // Stops that came from an experience: a blank plan.
        assertFalse(ExperiencesLogic.byHandKeepsPlanner(true, "Dolomiti Classic"))
        // Nothing there: blank (which also forgets a plan id left behind).
        assertFalse(ExperiencesLogic.byHandKeepsPlanner(false, null))
        assertFalse(ExperiencesLogic.byHandKeepsPlanner(false, "Dolomiti Classic"))
    }

    @Test
    fun aPlannedRouteIsOnlyReusedForTheSamePreference() {
        val stops = "45.0,7.0;46.0,8.0"
        val fastest = ExperiencesLogic.preparedKeyFor(stops, false)
        val scenic = ExperiencesLogic.preparedKeyFor(stops, true)
        assertFalse(fastest == scenic)
        assertEquals(fastest, ExperiencesLogic.preparedKeyFor(stops, false))
        assertFalse(fastest == ExperiencesLogic.preparedKeyFor("45.0,7.0;46.0,8.1", false))
        assertEquals(io.motohub.android.module.ModuleRoutePreference.SCENIC, io.motohub.android.routesim.core.routePreferenceOf(true))
        assertEquals(io.motohub.android.module.ModuleRoutePreference.FASTEST, io.motohub.android.routesim.core.routePreferenceOf(false))
    }

    // ---- opening a card

    @Test
    fun opensWithTheRoutesStopsAndTheExperiencesName() {
        val e = exp("a")
        val r = route(note = RouteNotes.PARTIAL_SEARCH)
        val plan = ExperiencesLogic.planToOpen(e, r)
        assertEquals("Ride a", plan.title)
        assertEquals(r.stops, plan.stops)
        assertEquals(RouteNotes.PARTIAL_SEARCH, plan.note)
        assertNull(ExperiencesLogic.planToOpen(e, route()).note)
    }

    @Test
    fun fallsBackOnTheCataloguesStopsWithTheReason() {
        val via = listOf(ExperiencePlace("Pass", 46.5, 11.9))
        val e = exp("a", via = via)
        val failed = ExperiencesLogic.failedRoute(e).let {
            ExperienceRoute(it.stops, it.detours, it.km, it.minutes, it.maxElevationM, false, RouteNotes.NO_NETWORK, true)
        }
        val plan = ExperiencesLogic.planToOpen(e, failed)
        assertEquals(listOf("Start a", "Pass", "End a"), plan.stops.map { it.label })
        assertEquals(46.54, plan.stops[0].latitude, 0.0)
        assertEquals(11.35, plan.stops[2].longitude, 0.0)
        assertEquals(RouteNotes.NO_NETWORK, plan.note)
        assertEquals("Ride a", plan.title)
        // No route at all (cancelled, never computed) is the same as a failed one, without a reason.
        val none = ExperiencesLogic.planToOpen(e, null)
        assertEquals(3, none.stops.size)
        assertNull(none.note)
        // A route with fewer than two stops is no route.
        assertEquals(3, ExperiencesLogic.planToOpen(e, route(stops = listOf(Stop(1.0, 1.0, "x")))).stops.size)
    }

    @Test
    fun aFailedRouteOfTheCatalogueIsAnError() {
        val f = ExperiencesLogic.failedRoute(exp("a", km = 99.0, minutes = 120.0, elevation = 1700))
        assertTrue(f.error)
        assertEquals(99.0, f.km, 0.0)
        assertEquals(120.0, f.minutes, 0.0)
        assertEquals(1700, f.maxElevationM)
        assertEquals(2, f.stops.size)
    }

    // ---- the country

    @Test
    fun theCountryIsTheRidersThenTheLastChosenThenItaly() {
        val all = listOf("AT", "CH", "DE", "IT")
        // Zurich is in Switzerland.
        assertEquals("CH", ExperiencesLogic.initialCountry(doubleArrayOf(47.37, 8.54), all, "AT"))
        // Vienna would be Austria; it is not in this catalogue: the last chosen, then Italy.
        assertEquals("DE", ExperiencesLogic.initialCountry(doubleArrayOf(48.2, 16.37), listOf("CH", "DE", "IT"), "DE"))
        assertEquals("IT", ExperiencesLogic.initialCountry(doubleArrayOf(48.2, 16.37), listOf("CH", "DE", "IT"), null))
        // Somewhere with no catalogue country, no position, a last country that is not there any more.
        assertEquals("AT", ExperiencesLogic.initialCountry(doubleArrayOf(51.5, -0.12), all, "at"))
        assertEquals("IT", ExperiencesLogic.initialCountry(null, all, "FR"))
        assertEquals("IT", ExperiencesLogic.initialCountry(doubleArrayOf(), all, null))
        // Without Italy: the first one. With nothing: none.
        assertEquals("AT", ExperiencesLogic.initialCountry(null, listOf("AT", "CH"), null))
        assertNull(ExperiencesLogic.initialCountry(null, emptyList(), "IT"))
    }

    // ---- filters

    @Test
    fun countsTheFiltersThatAreSet() {
        assertEquals(0, ExperiencesLogic.activeCount(ExperienceFilters()))
        assertTrue(ExperiencesLogic.isDefault(ExperienceFilters()))
        assertEquals(1, ExperiencesLogic.activeCount(ExperienceFilters(minKm = 50, maxKm = 300)))
        assertEquals(2, ExperiencesLogic.activeCount(ExperienceFilters(maxMinutes = 300, nature = 20)))
        assertEquals(1, ExperiencesLogic.activeCount(ExperienceFilters(detours = 0)))
        assertEquals(8, ExperiencesLogic.activeCount(ExperienceFilters(10, 20, 10, 20, 1, 2, 3, 4, "x", 6)))
        assertFalse(ExperiencesLogic.isDefault(ExperienceFilters(region = "Alps")))
        assertEquals("Filters", Strings.filtersSet(0))
        assertEquals("Filters · 2 set", Strings.filtersSet(2))
    }

    @Test
    fun printsTheRanges() {
        assertEquals("Any distance", ExperiencesLogic.kmRangeText(ExperienceFilters()))
        assertEquals("Up to 200 km", ExperiencesLogic.kmRangeText(ExperienceFilters(maxKm = 200)))
        assertEquals("100 km or more", ExperiencesLogic.kmRangeText(ExperienceFilters(minKm = 100)))
        assertEquals("100 km – 200 km", ExperiencesLogic.kmRangeText(ExperienceFilters(minKm = 100, maxKm = 200)))
        assertEquals("Any time", ExperiencesLogic.minutesRangeText(ExperienceFilters()))
        assertEquals("Up to 3h 00m", ExperiencesLogic.minutesRangeText(ExperienceFilters(maxMinutes = 180)))
        assertEquals("1h 00m or more", ExperiencesLogic.minutesRangeText(ExperienceFilters(minMinutes = 60)))
        assertEquals("1h 00m – 3h 00m", ExperiencesLogic.minutesRangeText(ExperienceFilters(minMinutes = 60, maxMinutes = 180)))
    }

    @Test
    fun theTargetSlidersAreAnyUntilTouched() {
        val f = ExperienceFilters()
        for (axis in TargetAxis.values()) {
            assertNull(axis.read(f))
            val set = axis.write(f, 70)
            assertEquals(70, axis.read(set))
            assertEquals(ExperienceFilters(), axis.toggleAny(axis.toggleAny(f)).let { axis.write(it, null) })
            assertEquals(TargetAxis.MIDDLE, axis.read(axis.toggleAny(f)))
            assertNull(axis.read(axis.toggleAny(set)))
            assertEquals(100, axis.read(axis.write(f, 400)))
            assertEquals(0, axis.read(axis.write(f, -5)))
            assertTrue(axis.lowLabel().isNotBlank() && axis.highLabel().isNotBlank())
        }
        assertEquals(30, TargetAxis.GRAVEL.read(TargetAxis.GRAVEL.write(f, 30)))
        assertNull(TargetAxis.GRAVEL.read(TargetAxis.NATURE.write(f, 30)))
        assertEquals("City", TargetAxis.NATURE.lowLabel())
        assertEquals("Nature", TargetAxis.NATURE.highLabel())
        assertEquals("Scenery", TargetAxis.CULTURE.lowLabel())
        assertEquals("Culture", TargetAxis.CULTURE.highLabel())
        assertEquals("Nature 70%", Strings.targetValue(TargetAxis.NATURE.highLabel(), 70))
    }

    @Test
    fun stepsThroughTheRegionsAndStopsAtTheEnds() {
        val options = ExperiencesLogic.regionOptions(listOf("Alps", "Coast", "Hills"))
        assertEquals(listOf(null, "Alps", "Coast", "Hills"), options)
        assertEquals("Alps", ExperiencesLogic.stepRegion(options, null, 1))
        assertEquals("Coast", ExperiencesLogic.stepRegion(options, "Alps", 1))
        assertEquals("Hills", ExperiencesLogic.stepRegion(options, "Hills", 1))
        assertNull(ExperiencesLogic.stepRegion(options, "Alps", -1))
        assertNull(ExperiencesLogic.stepRegion(options, null, -1))
        // A region the pack does not have any more counts as "All".
        assertEquals("Alps", ExperiencesLogic.stepRegion(options, "Gone", 1))
        assertNull(ExperiencesLogic.stepRegion(emptyList(), "Alps", 1))
        assertEquals("All", ExperiencesLogic.regionText(null))
        assertEquals("Alps", ExperiencesLogic.regionText("Alps"))
        assertEquals("None", ExperiencesLogic.detoursText(0))
        assertEquals("4", ExperiencesLogic.detoursText(4))
    }

    // ---- the sliders and the picture

    @Test
    fun aTouchIsAValueOnTheStep() {
        assertEquals(0, SliderMath.valueOf(0f, 0, 600, 10))
        assertEquals(600, SliderMath.valueOf(1f, 0, 600, 10))
        assertEquals(300, SliderMath.valueOf(0.5f, 0, 600, 10))
        assertEquals(310, SliderMath.valueOf(0.52f, 0, 600, 10))
        assertEquals(0, SliderMath.valueOf(-3f, 0, 600, 10))
        assertEquals(600, SliderMath.valueOf(9f, 0, 600, 10))
        assertEquals(45, SliderMath.valueOf(0.46f, 0, 100, 5))
        assertEquals(0.5f, SliderMath.fractionOf(300, 0, 600), 1e-6f)
        assertEquals(0f, SliderMath.fractionOf(5, 5, 5), 0f)
        assertEquals(1f, SliderMath.fractionOf(900, 0, 600), 0f)
    }

    @Test
    fun aTouchTakesTheNearerThumbAndThumbsDoNotCross() {
        assertEquals(0, SliderMath.nearestThumb(0.1f, 0.2f, 0.8f))
        assertEquals(1, SliderMath.nearestThumb(0.7f, 0.2f, 0.8f))
        assertEquals(0, SliderMath.nearestThumb(0.1f, 0.5f, 0.5f))
        assertEquals(1, SliderMath.nearestThumb(0.9f, 0.5f, 0.5f))
        assertEquals(100 to 400, SliderMath.moveThumb(0, 100, 0, 400))
        assertEquals(400 to 400, SliderMath.moveThumb(0, 500, 0, 400))
        assertEquals(100 to 100, SliderMath.moveThumb(1, 50, 100, 400))
        assertEquals(100 to 300, SliderMath.moveThumb(1, 300, 100, 400))
    }

    @Test
    fun theSilhouetteIsNotStretchedAndStaysInsideTheBox() {
        // 1 degree of latitude tall, 1 degree of longitude wide at 60 N: the ground is twice as tall as wide.
        val shape = listOf(59.5 to 10.0, 60.5 to 11.0)
        val p = ShapeMath.project(shape, 100f, 100f, 10f)
        assertEquals(2, p.size)
        val width = Math.abs(p[1].first - p[0].first)
        val height = Math.abs(p[1].second - p[0].second)
        assertEquals(0.5f, width / height, 0.01f)
        assertEquals(80f, height, 0.01f)
        // North is up: the northern end has the smaller y. The picture is centred.
        assertTrue(p[1].second < p[0].second)
        assertEquals(50f, (p[0].first + p[1].first) / 2f, 0.01f)
        for ((x, y) in p) assertTrue(x in 10f..90f && y in 10f..90f)
    }

    @Test
    fun aFlatOrSingleShapeStillDraws() {
        val flat = ShapeMath.project(listOf(45.0 to 7.0, 45.0 to 8.0), 100f, 60f, 10f)
        assertEquals(flat[0].second, flat[1].second, 0.001f)
        assertEquals(10f, flat[0].first, 0.01f)
        assertEquals(90f, flat[1].first, 0.01f)
        assertEquals(30f, flat[0].second, 0.01f)
        val tall = ShapeMath.project(listOf(45.0 to 7.0, 46.0 to 7.0), 100f, 60f, 10f)
        assertEquals(tall[0].first, tall[1].first, 0.001f)
        val dot = ShapeMath.project(listOf(45.0 to 7.0), 100f, 60f, 10f)
        assertEquals(1, dot.size)
        assertEquals(50f, dot[0].first, 0.01f)
        assertTrue(ShapeMath.project(emptyList(), 100f, 100f, 10f).isEmpty())
        assertTrue(ShapeMath.project(listOf(45.0 to 7.0), 0f, 100f, 10f).isEmpty())
    }

    // ---- the opening deadline and the breaker's card state

    @Test
    fun aTappedCardWaitsFifteenSecondsAtMost() {
        assertEquals(15_000L, ExperiencesLogic.OPEN_DEADLINE_MS)
        assertEquals(15_000L, ExperiencesLogic.openRemainingMs(1_000L, 1_000L))
        assertEquals(10_000L, ExperiencesLogic.openRemainingMs(1_000L, 6_000L))
        assertEquals(1L, ExperiencesLogic.openRemainingMs(1_000L, 15_999L))
        assertEquals(0L, ExperiencesLogic.openRemainingMs(1_000L, 16_000L))
        assertEquals(0L, ExperiencesLogic.openRemainingMs(1_000L, 90_000L))
        // A clock that reads earlier than the tap does not stretch the wait.
        assertEquals(15_000L, ExperiencesLogic.openRemainingMs(5_000L, 1_000L))
        assertFalse(ExperiencesLogic.openTimedOut(1_000L, 15_999L))
        assertTrue(ExperiencesLogic.openTimedOut(1_000L, 16_000L))
        assertTrue(ExperiencesLogic.openTimedOut(1_000L, 600_000L))
    }

    @Test
    fun aTimedOutTapOpensTheCataloguesOwnStopsWithTheSlowNotice() {
        val e = exp("xx-slow", via = listOf(ExperiencePlace("Via", 46.5, 12.0)))
        val plan = ExperiencesLogic.slowPlan(e)
        assertEquals("Ride xx-slow", plan.title)
        assertEquals(listOf("Start xx-slow", "Via", "End xx-slow"), plan.stops.map { it.label })
        assertEquals("Detours were not added: the map service is slow. You can plan them by hand.", plan.note)
    }

    @Test
    fun aTimedOutCardIsMarkedForARetryAndSaysThatDetoursAreUnavailable() {
        val e = exp("xx-slow")
        val f = ExperienceFilters()
        val slow = ExperiencesLogic.slowRoute(e)
        assertTrue(slow.error)
        assertEquals(RouteNotes.SIGHTS_UNAVAILABLE, slow.note)
        assertTrue(ExperiencesLogic.isRetryable(slow))
        assertEquals(ExperiencesLogic.Banner.RETRY, ExperiencesLogic.banner(listOf(ranked(e)), emptyMap(), f, mapOf(e.id to result(f, 0, slow))))
        // Retry drops it, so the card is asked for again.
        assertTrue(ExperiencesLogic.withoutFailures(mapOf(e.id to result(f, 0, slow))).isEmpty())
        // Its numbers are the catalogue's, not shown as real.
        val view = ExperiencesLogic.cardView(ranked(e), f, 0, result(f, 0, slow), busy = false)
        assertFalse(view.real)
        assertTrue(view.detoursUnavailable)
        assertFalse(view.loading)
    }

    @Test
    fun aPlainRouteThatCouldNotLookUpDetoursReadsDetoursUnavailableNotFinding() {
        val e = exp("xx-plain")
        val f = ExperienceFilters()
        val unavailable = result(f, 0, route(note = RouteNotes.SIGHTS_UNAVAILABLE, stops = emptyList()))
        val view = ExperiencesLogic.cardView(ranked(e), f, 0, unavailable, busy = false)
        assertTrue(view.detoursUnavailable)
        assertFalse(view.loading)
        assertTrue(view.real)
        assertEquals("Detours unavailable", Strings.DETOURS_UNAVAILABLE)
        // Waiting for a route is still "finding"; a good route and other verdicts say nothing of the kind.
        assertFalse(ExperiencesLogic.cardView(ranked(e), f, 0, null, busy = true).detoursUnavailable)
        assertTrue(ExperiencesLogic.cardView(ranked(e), f, 0, null, busy = true).loading)
        assertFalse(ExperiencesLogic.cardView(ranked(e), f, 0, result(f), busy = false).detoursUnavailable)
        assertFalse(ExperiencesLogic.cardView(ranked(e), f, 0, result(f, 0, route(note = RouteNotes.NO_DETOURS)), busy = false).detoursUnavailable)
        // An answer for other filters is not this card's answer.
        assertFalse(ExperiencesLogic.cardView(ranked(e), f.copy(detours = 1), 0, unavailable, busy = false).detoursUnavailable)
    }
}
