// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.routesim.ui

import io.motohub.android.module.ModuleRouteError
import io.motohub.android.routesim.experiences.Experience
import io.motohub.android.routesim.experiences.ExperienceFilters
import io.motohub.android.routesim.experiences.ExperiencePlace
import io.motohub.android.routesim.experiences.ExperienceRanker
import io.motohub.android.routesim.experiences.ExperienceRoute
import io.motohub.android.routesim.experiences.ExperienceRoutes
import io.motohub.android.routesim.experiences.CountryPack
import io.motohub.android.routesim.experiences.ExpFakeRouting
import io.motohub.android.routesim.experiences.ExpFakeSights
import io.motohub.android.routesim.experiences.sightAt
import io.motohub.android.routesim.experiences.sightResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The page's route worker: one at a time, nearest the top first, a tapped card in front, offline noticed once. */
class RouteQueueTest {

    @Before fun forgetCaches() = ExperienceRoutes.clearCaches()

    /** An experience whose A is at longitude [lonA]: the fake router's calls can then be told apart. */
    private fun exp(id: String, lonA: Double) = Experience(
        id = id, name = "Ride $id", description = "d", region = "Test",
        from = ExperiencePlace("A $id", 45.0, lonA), to = ExperiencePlace("B $id", 45.0, lonA + 0.3), via = emptyList(),
        km = 24.0, minutes = 35.0, nature = 50, twisty = 50, gravel = 0, maxElevationM = 900,
        shape = List(12) { 45.0 to lonA + it * 0.02 },
    )

    private val filters = ExperienceFilters(detours = 0)

    private fun request(id: String, lonA: Double = 7.0, alt: Int = 0, f: ExperienceFilters = filters) =
        RouteRequest(exp(id, lonA), f, alt)

    private fun failedRoute(id: String) = ExperiencesLogic.failedRoute(exp(id, 7.0))

    private fun drainAll(
        queue: RouteQueue,
        compute: (RouteRequest) -> ExperienceRoute = { failedRoute(it.id) },
        shouldContinue: () -> Boolean = { true },
    ): List<String> {
        val order = ArrayList<String>()
        queue.drain(shouldContinue, compute, { r, _ -> order += r.id }, { })
        return order
    }

    // ---- the queue

    @Test
    fun takesTheWishListInOrderAndThenRunsDry() {
        val q = RouteQueue()
        assertTrue(q.replace(listOf(request("a"), request("b"), request("c"))))
        assertEquals(listOf("a", "b", "c"), drainAll(q))
        assertNull(q.next())
        assertTrue(q.busyIds().isEmpty())
    }

    @Test
    fun onlyOneWorkerIsEverAskedFor() {
        val q = RouteQueue()
        assertTrue(q.replace(listOf(request("a"), request("b"))))
        // The worker is "running" until it has seen the queue empty: more wishes do not start another.
        assertFalse(q.replace(listOf(request("a"), request("b"), request("c"))))
        assertFalse(q.pin(request("z")))
        assertEquals(listOf("z", "a", "b", "c"), drainAll(q))
        // Once it ran dry a new wish starts a new worker.
        assertTrue(q.replace(listOf(request("d"))))
        assertEquals(listOf("d"), drainAll(q))
    }

    @Test
    fun anEmptyWishListStartsNothing() {
        val q = RouteQueue()
        assertFalse(q.replace(emptyList()))
        assertNull(q.next())
        assertTrue(q.replace(listOf(request("a"))))
    }

    @Test
    fun aNewWishListDropsWhatWaitedAndWasNotWantedAnyMore() {
        val q = RouteQueue()
        q.replace(listOf(request("a"), request("b"), request("c")))
        q.replace(listOf(request("c"), request("d")))
        assertEquals(setOf("c", "d"), q.busyIds())
        assertEquals(listOf("c", "d"), drainAll(q))
    }

    @Test
    fun theOneRunningIsLeftAloneAndNotAskedForTwice() {
        val q = RouteQueue()
        q.replace(listOf(request("a"), request("b")))
        val running = q.next()!!
        assertEquals("a", running.id)
        // The cards scrolled: "a" is still on the list, "b" is not.
        q.replace(listOf(request("a"), request("c")))
        assertEquals(setOf("a", "c"), q.busyIds())
        assertEquals("c", q.next()!!.id)
        q.finished(running)
        assertEquals(setOf("c"), q.busyIds())
    }

    @Test
    fun aTappedCardGoesFirstAndSurvivesAScroll() {
        val q = RouteQueue()
        q.replace(listOf(request("a"), request("b")))
        q.pin(request("z"))
        assertEquals("z", q.busyIds().first())
        // The list changes under it: the tapped card is still wanted.
        q.replace(listOf(request("b"), request("c")))
        assertEquals(listOf("z", "b", "c"), drainAll(q))
    }

    @Test
    fun cancellingTheTapDropsItFromTheWaitingOnes() {
        val q = RouteQueue()
        q.replace(listOf(request("a")))
        q.pin(request("z"))
        q.unpin()
        assertEquals(setOf("a"), q.busyIds())
        assertEquals(listOf("a"), drainAll(q))
    }

    @Test
    fun aFinishedRequestIsNotWantedAgain() {
        val q = RouteQueue()
        q.replace(listOf(request("a"), request("b")))
        val a = q.next()!!
        q.finished(a)
        q.replace(emptyList())
        q.pin(request("z"))
        assertEquals("z", q.next()!!.id)
        assertNull(q.next())   // "b" was dropped by the empty wish list
    }

    @Test
    fun clearingForgetsWhatWaitsButNotWhatRuns() {
        val q = RouteQueue()
        q.replace(listOf(request("a"), request("b")))
        val a = q.next()!!
        q.pin(request("z"))
        q.clear()
        assertEquals(setOf("a"), q.busyIds())
        q.finished(a)
        assertTrue(q.busyIds().isEmpty())
        assertNull(q.next())
    }

    @Test
    fun theSameCardForOtherFiltersIsAnotherRequest() {
        val q = RouteQueue()
        q.replace(listOf(request("a", f = ExperienceFilters(detours = 3)), request("a", f = ExperienceFilters(detours = 4))))
        assertEquals(2, drainAll(q).size)
        // The same card and the same route key twice is one request.
        q.replace(listOf(request("a", f = ExperienceFilters(detours = 3, nature = 10)), request("a", f = ExperienceFilters(detours = 3, nature = 90))))
        assertEquals(listOf("a"), drainAll(q))
    }

    // ---- the worker's loop

    @Test
    fun aComputeThatThrowsIsAnsweredWithAFailedRoute() {
        val q = RouteQueue()
        q.replace(listOf(request("a"), request("b")))
        val answers = ArrayList<Pair<String, Boolean>>()
        q.drain({ true }, { if (it.id == "a") throw IllegalStateException("boom") else failedRoute("b") }, { r, route -> answers += r.id to route.error }, { })
        assertEquals(listOf("a" to true, "b" to true), answers)
        assertEquals(24.0, ExperiencesLogic.failedRoute(exp("a", 7.0)).km, 0.0)
    }

    @Test
    fun theWorkerStopsWhenTheModuleIsReleased() {
        val q = RouteQueue()
        q.replace(listOf(request("a"), request("b"), request("c")))
        var asked = 0
        val order = drainAll(q, shouldContinue = { asked++ < 1 })
        assertEquals(listOf("a"), order)
        // It can be started again after that.
        assertTrue(q.replace(listOf(request("b"))))
    }

    @Test
    fun theAnswerIsDeliveredBeforeTheRequestCountsAsFinished() {
        val q = RouteQueue()
        q.replace(listOf(request("a")))
        var busyWhenDelivered: Set<String>? = null
        q.drain({ true }, { failedRoute("a") }, { _, _ -> busyWhenDelivered = q.busyIds() }, { })
        assertEquals(setOf("a"), busyWhenDelivered)
        assertTrue(q.busyIds().isEmpty())
    }

    @Test
    fun reportsEveryChangeOfWhatIsBusy() {
        val q = RouteQueue()
        q.replace(listOf(request("a"), request("b")))
        val seen = ArrayList<Set<String>>()
        q.drain({ true }, { failedRoute(it.id) }, { _, _ -> }, { seen += q.busyIds() })
        assertEquals(listOf(setOf("a", "b"), setOf("b"), setOf("b"), emptySet<String>()), seen)
    }

    // ---- with the real routes and a fake router

    private fun pack(): CountryPack =
        CountryPack("XX", "Testland", 1, List(10) { exp("xx-%02d".format(it), 7.0 + it * 0.5) })

    @Test
    fun theCardsOnScreenAreRoutedOneByOneNearestTheTopFirst() {
        val routing = ExpFakeRouting()
        val routes = ExperienceRoutes(routing, ExpFakeSights())
        val cards = ExperienceRanker.rank(pack(), filters, 10)
        val visible = cards.subList(3, 5).map { it.experience.id }.toSet()
        val wanted = ExperiencesLogic.wanted(cards, emptyMap(), filters, emptyMap(), visible)
        assertEquals(cards.subList(3, 6).map { it.experience.id }, wanted.map { it.id })

        val q = RouteQueue()
        assertTrue(q.replace(wanted))
        val results = HashMap<String, CardResult>()
        var inCompute = 0
        var mostAtOnce = 0
        q.drain(
            { true },
            {
                inCompute++
                mostAtOnce = maxOf(mostAtOnce, inCompute)
                try { routes.build(it.experience, it.filters, it.alt) } finally { inCompute-- }
            },
            { r, route -> results[r.id] = CardResult(r.key, route) },
            { },
        )
        assertEquals(1, mostAtOnce)
        // The router was called once per card, in the order of the list: the longitude of A grows with the rank.
        val starts = routing.routeCalls.map { it.second[0] }
        assertEquals(wanted.map { it.experience.from.longitude }, starts)
        assertEquals(wanted.map { it.id }.toSet(), results.keys)
        assertTrue(results.values.none { it.route.error })
        // The cards now show the router's numbers.
        val shown = ExperiencesLogic.cardView(cards[3], filters, 0, results[cards[3].experience.id], busy = false)
        assertTrue(shown.real)
        // Nothing is left to ask for.
        assertTrue(ExperiencesLogic.wanted(cards, emptyMap(), filters, results, visible).isEmpty())
    }

    @Test
    fun withoutAConnectionOneBannerAndNoRetryLoop() {
        val routing = ExpFakeRouting()
        routing.handler = { _, _, _ -> routing.refuse(ModuleRouteError.NO_NETWORK) }
        val routes = ExperienceRoutes(routing, ExpFakeSights())
        val cards = ExperienceRanker.rank(pack(), filters, 10)
        val visible = cards.take(3).map { it.experience.id }.toSet()
        val results = HashMap<String, CardResult>()

        fun pump() {
            val q = RouteQueue()
            q.replace(ExperiencesLogic.wanted(cards, emptyMap(), filters, results, visible))
            q.drain({ true }, { routes.build(it.experience, it.filters, it.alt) }, { r, route -> results[r.id] = CardResult(r.key, route) }, { })
        }
        pump()
        assertEquals(4, results.size)
        assertTrue(results.values.all { it.route.error })
        assertTrue(ExperiencesLogic.offline(cards, emptyMap(), filters, results))
        // The failed ones are answers: asking again finds nothing to do, so no calls are spent.
        val calls = routing.routeCalls.size
        pump()
        assertEquals(calls, routing.routeCalls.size)
        // The cards still show the catalogue's numbers.
        assertEquals("24 km", ExperiencesLogic.cardView(cards[0], filters, 0, results[cards[0].experience.id], busy = false).kmText)

        // The connection is back: "Try again" drops the failures and the routes are asked for again.
        routing.handler = null
        val kept = ExperiencesLogic.withoutFailures(results)
        results.clear(); results.putAll(kept)
        pump()
        assertTrue(results.values.none { it.route.error })
        assertFalse(ExperiencesLogic.offline(cards, emptyMap(), filters, results))
    }

    @Test
    fun anotherIdeaIsRecomputedOnItsOwnAndTheOthersKeepTheirRoutes() {
        val routing = ExpFakeRouting()
        val sights = ExpFakeSights(sightResult(listOf(sightAt("Alpha", 7.1, 1.0), sightAt("Bravo", 7.2, 1.0), sightAt("Charlie", 7.25, 1.0))))
        val routes = ExperienceRoutes(routing, sights)
        val f = ExperienceFilters(detours = 1)
        val cards = ExperienceRanker.rank(pack(), f, 10)
        val first = cards[0]
        val visible = cards.take(2).map { it.experience.id }.toSet()
        val results = HashMap<String, CardResult>()
        val alts = HashMap<String, Int>()

        fun pump() {
            val q = RouteQueue()
            q.replace(ExperiencesLogic.wanted(cards, alts, f, results, visible))
            q.drain({ true }, { routes.build(it.experience, it.filters, it.alt) }, { r, route -> results[r.id] = CardResult(r.key, route) }, { })
        }
        pump()
        val before = results.getValue(first.experience.id)
        val untouched = results.getValue(cards[1].experience.id)
        assertTrue(before.route.stops.size >= 2)

        alts[first.experience.id] = ExperiencesLogic.nextAlt(0)
        val wanted = ExperiencesLogic.wanted(cards, alts, f, results, visible)
        assertEquals(listOf(first.experience.id), wanted.map { it.id })
        assertEquals(1, wanted.single().alt)
        pump()
        assertEquals(1, results.getValue(first.experience.id).key.alt)
        assertTrue(results.getValue(cards[1].experience.id) === untouched)
    }
}
