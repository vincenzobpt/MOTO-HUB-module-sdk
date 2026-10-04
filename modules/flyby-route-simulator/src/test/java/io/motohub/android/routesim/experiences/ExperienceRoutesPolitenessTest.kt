// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.routesim.experiences

import io.motohub.android.module.ModuleSightResult
import io.motohub.android.module.ModuleSights
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The circuit breaker, the disk cache and the time guard around the host's sights search. */
class ExperienceRoutesPolitenessTest {

    private val routing = ExpFakeRouting()
    private val sights = ExpFakeSights()
    private var now = 1_700_000_000_000L
    private lateinit var dir: File

    private fun routes(
        host: ModuleSights = sights,
        storage: File? = null,
        radiusM: Int = DetourPlanner.DEFAULT_RADIUS_M,
    ) = ExperienceRoutes(routing, host, radiusM, storage) { now }

    @Before fun setUp() {
        ExperienceRoutes.clearCaches()
        dir = Files.createTempDirectory("routesim-sights").toFile()
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private fun experience(id: String = "xx-test", toLon: Double = 7.5) = Experience(
        id = id, name = "Test ride", description = "A test ride.", region = "Test",
        from = ExperiencePlace("A", 45.0, 7.0), to = ExperiencePlace("B", 45.0, toLon), via = emptyList(),
        km = 39.0, minutes = 59.0, nature = 50, twisty = 50, gravel = 0, maxElevationM = 1500,
        shape = List(12) { 45.0 to 7.0 + it * 0.04 },
    )

    private fun threeSights() = listOf(
        sightAt("Alpha", 7.10, 1.0), sightAt("Bravo", 7.25, 1.0, elevationM = 1234.0), sightAt("Charlie", 7.40, 1.0),
    )

    private val filters = ExperienceFilters(detours = 3)

    private fun sightsDir() = File(dir, SightsDiskCache.DIR_NAME)

    private fun cachedFiles() = sightsDir().listFiles().orEmpty().filter { it.name.endsWith(".json") }

    // --- the circuit breaker ----------------------------------------------------------------

    @Test fun afterAFailedSearchTheHostIsLeftAloneAndThePlainRouteComesAtOnce() {
        val r = routes()
        sights.result = sightFailure()
        val first = r.build(experience("xx-one"), filters, 0)
        assertEquals(RouteNotes.SIGHTS_UNAVAILABLE, first.note)
        assertEquals(1, sights.calls)
        assertTrue(r.isBreakerOpen())

        // Nine more cards: no host call, the plain road with the same note, never cached as a result.
        for (i in 2..10) {
            val other = r.build(experience("xx-$i"), filters, 0)
            assertFalse(other.error)
            assertEquals(RouteNotes.SIGHTS_UNAVAILABLE, other.note)
            assertTrue(other.detours.isEmpty())
        }
        assertEquals(1, sights.calls)
    }

    @Test fun theBreakerClosesByItselfAfterFiveMinutes() {
        val r = routes()
        sights.result = sightFailure()
        r.build(experience("xx-one"), filters, 0)
        assertEquals(5L * 60 * 1000, ExperienceRoutes.BREAKER_MILLIS)

        sights.result = sightResult(threeSights())
        now += ExperienceRoutes.BREAKER_MILLIS - 1
        assertEquals(RouteNotes.SIGHTS_UNAVAILABLE, r.build(experience("xx-two"), filters, 0).note)
        assertEquals(1, sights.calls)

        now += 1
        assertFalse(r.isBreakerOpen())
        val again = r.build(experience("xx-two"), filters, 0)
        assertEquals(2, sights.calls)
        assertEquals(3, again.detours.size)
        assertNull(again.note)
    }

    @Test fun aSuccessClosesTheBreaker() {
        val r = routes()
        sights.result = sightFailure()
        r.build(experience("xx-one"), filters, 0)
        assertTrue(r.isBreakerOpen())
        r.resetBreaker()
        sights.result = sightResult(threeSights())
        assertEquals(3, r.build(experience("xx-one"), filters, 0).detours.size)
        assertFalse(r.isBreakerOpen())
        // Later failures start a fresh window.
        sights.result = sightFailure()
        r.build(experience("xx-two"), filters, 0)
        assertTrue(r.isBreakerOpen())
    }

    @Test fun theRidersRetryBypassesTheBreaker() {
        val r = routes()
        sights.result = sightFailure()
        r.build(experience("xx-one"), filters, 0)
        r.build(experience("xx-two"), filters, 0)
        assertEquals(1, sights.calls)
        r.resetBreaker()
        assertFalse(r.isBreakerOpen())
        r.build(experience("xx-two"), filters, 0)
        assertEquals(2, sights.calls)
    }

    @Test fun anExceptionAndAnEmptyAnswerOpenTheBreakerToo() {
        val r = routes()
        sights.throws = true
        r.build(experience("xx-one"), filters, 0)
        assertTrue(r.isBreakerOpen())
        r.resetBreaker()
        sights.throws = false
        sights.result = sightResult(emptyList())
        r.build(experience("xx-two"), filters, 0)
        assertTrue(r.isBreakerOpen())
        assertEquals(2, sights.calls)
    }

    @Test fun aPartialSearchWithSightsIsASuccess() {
        val r = routes()
        sights.result = sightResult(threeSights(), complete = false)
        val built = r.build(experience(), filters, 0)
        assertEquals(3, built.detours.size)
        assertFalse(r.isBreakerOpen())
    }

    @Test fun theBreakerIsProcessWide() {
        sights.result = sightFailure()
        routes().build(experience("xx-one"), filters, 0)
        assertTrue(routes().isBreakerOpen())
        routes().build(experience("xx-two"), filters, 0)
        assertEquals(1, sights.calls)
    }

    @Test fun anOpenBreakerStillServesWhatIsAlreadyKnown() {
        val r = routes(storage = dir)
        sights.result = sightResult(threeSights())
        r.build(experience("xx-known"), filters, 0)
        sights.result = sightFailure()
        r.build(experience("xx-unknown"), filters, 0)
        assertTrue(r.isBreakerOpen())
        // A known road (memory), with other filters, still gets its detours.
        assertEquals(3, r.build(experience("xx-known"), ExperienceFilters(detours = 3, maxKm = 300), 0).detours.size)
        // And so does one only the disk knows, after a restart of the process.
        ExperienceRoutes.clearCaches()
        sights.result = sightFailure()
        r.build(experience("xx-unknown"), filters, 0)
        assertTrue(r.isBreakerOpen())
        assertEquals(3, routes(storage = dir).build(experience("xx-known"), filters, 0).detours.size)
        assertEquals(3, sights.calls)   // known, unknown, and unknown again after the restart: never the known one twice
    }

    @Test fun noRetryLoopMultipliesAFailedSearch() {
        val r = routes()
        sights.result = sightFailure()
        for (detours in 1..6) for (alt in 0..3) r.build(experience(), ExperienceFilters(detours = detours), alt)
        assertEquals(1, sights.calls)
    }

    @Test fun aHostThatAnswersFarTooLateGivesThePlainRouteAndKeepsTheAnswer() {
        val slow = object : ModuleSights {
            var calls = 0
            override fun along(latitudes: DoubleArray, longitudes: DoubleArray, radiusMeters: Int, kinds: Int): ModuleSightResult {
                calls++
                now += ExperienceRoutes.SEARCH_GUARD_MILLIS + 1
                return sightResult(threeSights())
            }
        }
        val r = routes(host = slow, storage = dir)
        val first = r.build(experience(), filters, 0)
        assertEquals(RouteNotes.SIGHTS_UNAVAILABLE, first.note)
        assertTrue(first.detours.isEmpty())
        assertTrue(r.isBreakerOpen())
        assertEquals(1, slow.calls)
        // The answer was good: it is there for the next build, with no new call.
        val next = r.build(experience(), filters, 0)
        assertEquals(3, next.detours.size)
        assertEquals(1, slow.calls)
    }

    // --- the disk cache ---------------------------------------------------------------------

    @Test fun foundSightsAreKeptOnDiskAndReadByTheNextProcess() {
        sights.result = sightResult(threeSights())
        routes(storage = dir).build(experience(), filters, 0)
        assertEquals(1, sights.calls)
        assertEquals(1, cachedFiles().size)
        assertTrue(sightsDir().listFiles().orEmpty().none { it.name.endsWith(".tmp") })

        ExperienceRoutes.clearCaches()   // the app was restarted
        val again = routes(storage = dir).build(experience(), filters, 0)
        assertEquals(1, sights.calls)
        assertEquals(listOf("Alpha", "Bravo", "Charlie"), again.detours.map { it.name })
        assertEquals(1234.0, again.detours[1].elevationM, 0.0)
        assertTrue(again.detours[0].elevationM.isNaN())
    }

    @Test fun nothingIsKeptFromAPartialAFailedOrAnEmptySearch() {
        val r = routes(storage = dir)
        sights.result = sightResult(threeSights(), complete = false)
        r.build(experience("xx-partial"), filters, 0)
        r.resetBreaker()
        sights.result = sightFailure()
        r.build(experience("xx-failed"), filters, 0)
        r.resetBreaker()
        sights.result = sightResult(emptyList())
        r.build(experience("xx-empty"), filters, 0)
        assertTrue(cachedFiles().isEmpty())
    }

    @Test fun withoutAStorageDirectoryNothingIsWritten() {
        sights.result = sightResult(threeSights())
        routes().build(experience(), filters, 0)
        assertFalse(sightsDir().exists())
    }

    @Test fun keptSightsExpireAfterFourteenDays() {
        sights.result = sightResult(threeSights())
        routes(storage = dir).build(experience(), filters, 0)
        assertEquals(14L * 24 * 60 * 60 * 1000, SightsDiskCache.TTL_MILLIS)

        now += SightsDiskCache.TTL_MILLIS - 1_000
        ExperienceRoutes.clearCaches()
        routes(storage = dir).build(experience(), filters, 0)
        assertEquals(1, sights.calls)

        now += 2_000
        ExperienceRoutes.clearCaches()
        routes(storage = dir).build(experience(), filters, 0)
        assertEquals(2, sights.calls)
        // The fresh answer replaced the old one.
        now += 1_000
        ExperienceRoutes.clearCaches()
        routes(storage = dir).build(experience(), filters, 0)
        assertEquals(2, sights.calls)
    }

    @Test fun aDamagedFileIsIgnoredAndSearchedAgain() {
        sights.result = sightResult(threeSights())
        routes(storage = dir).build(experience(), filters, 0)
        val file = cachedFiles().single()
        for (junk in listOf("", "not json", "{\"v\":1}", "[]", "{\"v\":1,\"sights\":[{\"n\":\"x\"}]}")) {
            file.writeText(junk)
            ExperienceRoutes.clearCaches()
            val calls = sights.calls
            val built = routes(storage = dir).build(experience(), filters, 0)
            assertEquals("for: $junk", calls + 1, sights.calls)
            assertEquals(3, built.detours.size)
        }
        // And the rewritten file is good again.
        ExperienceRoutes.clearCaches()
        val calls = sights.calls
        routes(storage = dir).build(experience(), filters, 0)
        assertEquals(calls, sights.calls)
    }

    @Test fun aFileForAnotherRoadOrRadiusIsNotUsed() {
        sights.result = sightResult(threeSights())
        routes(storage = dir).build(experience(), filters, 0)
        assertEquals(1, sights.calls)
        // Another radius is another file.
        ExperienceRoutes.clearCaches()
        routes(storage = dir, radiusM = 2_000).build(experience(), ExperienceFilters(detours = 1), 0)
        assertEquals(2, sights.calls)
        assertEquals(2, cachedFiles().size)
        // The catalogue moved the end of the same experience: its road is another road.
        ExperienceRoutes.clearCaches()
        routes(storage = dir).build(experience(toLon = 7.45), filters, 0)
        assertEquals(3, sights.calls)
        assertEquals(3, cachedFiles().size)
    }

    @Test fun aFileWhoseContentIsForAnotherExperienceIsNotUsed() {
        val cache = SightsDiskCache(dir) { now }
        val list = threeSights()
        cache.write("xx-a", 5, 4000, list)
        assertEquals(3, cache.read("xx-a", 5, 4000)!!.size)
        // Copied over the name another experience would look for: the content says whose it is.
        cache.fileFor("xx-a", 5, 4000).copyTo(cache.fileFor("xx-b", 5, 4000))
        assertNull(cache.read("xx-b", 5, 4000))
        cache.fileFor("xx-a", 5, 4000).copyTo(cache.fileFor("xx-a", 6, 4000))
        assertNull(cache.read("xx-a", 6, 4000))
    }

    @Test fun theFileHoldsTheSightsAsJson() {
        val cache = SightsDiskCache(dir) { now }
        cache.write("xx-a/b", 7, 4000, threeSights())
        val file = cache.fileFor("xx-a/b", 7, 4000)
        assertEquals(sightsDir(), file.parentFile)
        assertTrue(file.name.endsWith(".json"))
        assertFalse(file.name.contains('/'))
        val root = JSONObject(file.readText())
        assertEquals(3, root.getJSONArray("sights").length())
        assertEquals(now, root.getLong("savedAt"))
        assertFalse(root.getJSONArray("sights").getJSONObject(0).has("e"))
        assertTrue(root.getJSONArray("sights").getJSONObject(1).has("e"))
    }

    @Test fun anEmptyListIsNeverWritten() {
        val cache = SightsDiskCache(dir) { now }
        cache.write("xx-a", 1, 4000, emptyList())
        assertFalse(sightsDir().exists())
        assertNull(cache.read("xx-a", 1, 4000))
    }

    @Test fun oldFilesAreForgottenWhenANewOneIsWritten() {
        val cache = SightsDiskCache(dir) { now }
        cache.write("xx-old", 1, 4000, threeSights())
        val old = cache.fileFor("xx-old", 1, 4000)
        assertTrue(old.setLastModified(now - SightsDiskCache.TTL_MILLIS - 60_000))
        val leftover = File(sightsDir(), "xx-half.json.tmp")
        leftover.writeText("{")
        assertTrue(leftover.setLastModified(now - 2 * 60 * 60 * 1000))
        // A new process: its first write cleans up.
        SightsDiskCache(dir) { now }.write("xx-new", 2, 4000, threeSights())
        assertFalse(old.exists())
        assertFalse(leftover.exists())
        assertNotNull(SightsDiskCache(dir) { now }.read("xx-new", 2, 4000))
    }

    @Test fun anUnwritableStorageDoesNotBreakTheSearch() {
        val notADirectory = File(dir, "file").also { it.writeText("x") }
        sights.result = sightResult(threeSights())
        val built = routes(storage = notADirectory).build(experience(), filters, 0)
        assertEquals(3, built.detours.size)
    }
}
