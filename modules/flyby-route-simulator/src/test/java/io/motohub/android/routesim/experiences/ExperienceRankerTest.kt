// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.routesim.experiences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperienceRankerTest {

    private fun exp(
        id: String,
        km: Double = 150.0,
        minutes: Double = 180.0,
        nature: Int = 50,
        twisty: Int = 50,
        gravel: Int = 0,
        elevation: Int = 800,
        region: String = "North",
    ) = Experience(
        id = id, name = "Ride $id", description = "d", region = region,
        from = ExperiencePlace("A", 45.0, 7.0), to = ExperiencePlace("B", 46.0, 8.0), via = emptyList(),
        km = km, minutes = minutes, nature = nature, twisty = twisty, gravel = gravel, maxElevationM = elevation,
        shape = List(12) { 45.0 + it * 0.1 to 7.0 + it * 0.1 },
    )

    /** 30 rides: kms 60..350, minutes 80..400, sliders spread, three regions. */
    private fun pack(): CountryPack = CountryPack(
        "XX", "Testland", 1,
        List(30) { i ->
            exp(
                id = "xx-ride-%02d".format(i),
                km = 60.0 + i * 10,
                minutes = 80.0 + i * 11,
                nature = (i * 7) % 101,
                twisty = (i * 13) % 101,
                gravel = (i * 5) % 60,
                elevation = 200 + i * 80,
                region = listOf("North", "South", "Coast")[i % 3],
            )
        },
    )

    private fun longPack(): CountryPack = CountryPack(
        "XX", "Longland", 1, List(15) { exp("xx-long-%02d".format(it), km = 300.0 + it * 10, minutes = 400.0 + it * 5) },
    )

    @Test
    fun returnsTheLimitBestFirst() {
        val r = ExperienceRanker.rank(pack(), ExperienceFilters(minKm = 100, maxKm = 200))
        assertEquals(10, r.size)
        assertEquals(r.sortedByDescending { it.score }.map { it.experience.id }, r.map { it.experience.id })
        assertTrue(r.all { it.experience.km in 100.0..200.0 })
    }

    @Test
    fun limitAndPackSizeBoundTheCount() {
        assertEquals(5, ExperienceRanker.rank(pack(), ExperienceFilters(), limit = 5).size)
        assertEquals(30, ExperienceRanker.rank(pack(), ExperienceFilters(), limit = 100).size)
        val small = CountryPack("XX", "S", 1, pack().experiences.take(3))
        assertEquals(3, ExperienceRanker.rank(small, ExperienceFilters()).size)
        assertTrue(ExperienceRanker.rank(CountryPack("XX", "E", 1, emptyList()), ExperienceFilters()).isEmpty())
    }

    @Test
    fun isDeterministicAndBreaksTiesById() {
        val twins = CountryPack("XX", "T", 1, listOf("xx-c", "xx-a", "xx-b").map { exp(it) })
        val a = ExperienceRanker.rank(twins, ExperienceFilters())
        val b = ExperienceRanker.rank(CountryPack("XX", "T", 1, twins.experiences.reversed()), ExperienceFilters())
        assertEquals(listOf("xx-a", "xx-b", "xx-c"), a.map { it.experience.id })
        assertEquals(a.map { it.experience.id }, b.map { it.experience.id })
        assertEquals(a, ExperienceRanker.rank(twins, ExperienceFilters()))
    }

    @Test
    fun extremeFiltersStillGiveTenWithKmMismatches() {
        val r = ExperienceRanker.rank(longPack(), ExperienceFilters(maxKm = 10))
        assertEquals(10, r.size)
        r.forEach { rx ->
            val km = rx.mismatches.filterIsInstance<Mismatch.Km>().single().km
            assertEquals(Math.round(rx.experience.km - 10).toInt(), km)
            assertTrue(km > 0)
        }
        // the shortest ones are the nearest
        assertEquals((0 until 10).map { "xx-long-%02d".format(it) }, r.map { it.experience.id })
        assertTrue(r.all { it.score in 0.0..1.0 })
    }

    @Test
    fun tooShortGivesNegativeKm() {
        val r = ExperienceRanker.rank(pack(), ExperienceFilters(minKm = 500, maxKm = 600))
        assertEquals(10, r.size)
        assertEquals("xx-ride-29", r.first().experience.id)
        assertEquals(-(500 - 350), r.first().mismatches.filterIsInstance<Mismatch.Km>().single().km)
    }

    @Test
    fun minutesMismatchHasTheSameSign() {
        val e = exp("xx-m", minutes = 300.0)
        val p = CountryPack("XX", "M", 1, listOf(e))
        assertEquals(listOf(Mismatch.Minutes(100)), ExperienceRanker.rank(p, ExperienceFilters(maxMinutes = 200)).single().mismatches)
        assertEquals(listOf(Mismatch.Minutes(-60)), ExperienceRanker.rank(p, ExperienceFilters(minMinutes = 360)).single().mismatches)
    }

    @Test
    fun exactMatchHasNoMismatchesAndTopScore() {
        val e = exp("xx-exact", km = 150.0, minutes = 180.0, nature = 80, twisty = 70, gravel = 10, region = "North")
        val f = ExperienceFilters(minKm = 100, maxKm = 200, minMinutes = 120, maxMinutes = 240, nature = 80, twisty = 70, gravel = 10, region = "North")
        val r = ExperienceRanker.rank(CountryPack("XX", "E", 1, listOf(e) + pack().experiences), f)
        assertEquals("xx-exact", r.first().experience.id)
        assertTrue(r.first().mismatches.isEmpty())
        assertEquals(1.0, r.first().score, 1e-9)
    }

    @Test
    fun slidersMismatchOnlyBeyondTwentyFivePoints() {
        val p = CountryPack("XX", "S", 1, listOf(exp("xx-s", nature = 50, twisty = 50, gravel = 0)))
        val near = ExperienceRanker.rank(p, ExperienceFilters(nature = 75, twisty = 25, gravel = 25)).single()
        assertTrue(near.mismatches.isEmpty())
        val far = ExperienceRanker.rank(p, ExperienceFilters(nature = 76, twisty = 24, gravel = 26)).single()
        assertEquals(listOf(Mismatch.Nature, Mismatch.Twisty, Mismatch.Gravel), far.mismatches)
        assertTrue(far.score < near.score)
    }

    @Test
    fun slidersSteerTheOrder() {
        val p = CountryPack("XX", "S", 1, listOf(exp("xx-city", nature = 10), exp("xx-wild", nature = 95), exp("xx-mid", nature = 50)))
        assertEquals(listOf("xx-wild", "xx-mid", "xx-city"), ExperienceRanker.rank(p, ExperienceFilters(nature = 100)).map { it.experience.id })
        assertEquals(listOf("xx-city", "xx-mid", "xx-wild"), ExperienceRanker.rank(p, ExperienceFilters(nature = 0)).map { it.experience.id })
    }

    @Test
    fun nullSlidersAndCultureAndDetoursAreIgnored() {
        val base = ExperienceRanker.rank(pack(), ExperienceFilters())
        val other = ExperienceRanker.rank(pack(), ExperienceFilters(culture = 100, detours = 0))
        assertEquals(base, other)
        assertTrue(base.all { it.mismatches.isEmpty() })
        assertTrue(base.all { it.score == 1.0 })
    }

    @Test
    fun regionWithEnoughOwnRidesExcludesTheOthers() {
        // 10 of the 30 are "South"
        val r = ExperienceRanker.rank(pack(), ExperienceFilters(region = "South"))
        assertEquals(10, r.size)
        assertTrue(r.all { it.experience.region == "South" && Mismatch.Region !in it.mismatches })
    }

    @Test
    fun regionWithFewOwnRidesIsFilledInAndLabelled() {
        val p = CountryPack(
            "XX", "R", 1,
            List(3) { exp("xx-own-$it", region = "Alps", km = 400.0 + it) } + List(20) { exp("xx-other-%02d".format(it), region = "Plain", km = 120.0) },
        )
        val r = ExperienceRanker.rank(p, ExperienceFilters(region = "alps", maxKm = 200))
        assertEquals(10, r.size)
        val own = r.filter { it.experience.region == "Alps" }
        assertEquals(3, own.size)
        assertTrue(own.all { Mismatch.Region !in it.mismatches })
        val fill = r.filter { it.experience.region == "Plain" }
        assertEquals(7, fill.size)
        assertTrue(fill.all { Mismatch.Region in it.mismatches })
        // a different region is a strong penalty: it never beats an equally fitting own ride
        val same = CountryPack("XX", "R", 1, listOf(exp("xx-a", region = "Plain"), exp("xx-z", region = "Alps")))
        assertEquals("xx-z", ExperienceRanker.rank(same, ExperienceFilters(region = "Alps")).first().experience.id)
        assertFalse(Mismatch.Region in ExperienceRanker.rank(same, ExperienceFilters(region = "Alps")).first().mismatches)
    }

    @Test
    fun blankRegionMeansAll() {
        val r = ExperienceRanker.rank(pack(), ExperienceFilters(region = "  "))
        assertTrue(r.all { Mismatch.Region !in it.mismatches })
    }

    @Test
    fun regionsAreDistinctAndSorted() {
        val p = CountryPack("XX", "R", 1, listOf("south", "North", "Coast", "North", " ").map { exp("xx-$it", region = it) })
        assertEquals(listOf("Coast", "North", "south"), ExperienceRanker.regions(p))
    }

    @Test
    fun scoreFallsSmoothlyWithDistance() {
        val p = CountryPack("XX", "K", 1, listOf(exp("xx-1", km = 210.0), exp("xx-2", km = 250.0), exp("xx-3", km = 400.0)))
        val scores = ExperienceRanker.rank(p, ExperienceFilters(minKm = 100, maxKm = 200)).map { it.score }
        assertTrue(scores[0] > scores[1] && scores[1] > scores[2] && scores[2] > 0.0)
    }

    @Test
    fun tagThresholds() {
        fun tags(
            km: Double = 150.0, nature: Int = 50, twisty: Int = 50, gravel: Int = 0, elevation: Int = 800,
        ) = ExperienceRanker.tagsOf(exp("xx-t", km = km, nature = nature, twisty = twisty, gravel = gravel, elevation = elevation))
        assertTrue(tags().isEmpty())
        assertEquals(listOf("Nature"), tags(nature = 65))
        assertTrue(tags(nature = 64).isEmpty())
        assertEquals(listOf("City"), tags(nature = 35))
        assertTrue(tags(nature = 36).isEmpty())
        assertEquals(listOf("Bends"), tags(twisty = 65))
        assertTrue(tags(twisty = 64).isEmpty())
        assertEquals(listOf("Fast"), tags(twisty = 30))
        assertTrue(tags(twisty = 31).isEmpty())
        assertEquals(listOf("Gravel"), tags(gravel = 40))
        assertEquals(listOf("Mixed"), tags(gravel = 39))
        assertEquals(listOf("Mixed"), tags(gravel = 15))
        assertTrue(tags(gravel = 14).isEmpty())
        assertEquals(listOf("Mountain"), tags(elevation = 1500))
        assertTrue(tags(elevation = 1499).isEmpty())
        assertEquals(listOf("Long"), tags(km = 300.0))
        assertTrue(tags(km = 299.0).isEmpty())
        assertEquals(listOf("Short"), tags(km = 80.0))
        assertTrue(tags(km = 81.0).isEmpty())
    }

    @Test
    fun tagsAreCappedAtFourMostTellingFirst() {
        val t = ExperienceRanker.tagsOf(exp("xx-all", km = 400.0, nature = 90, twisty = 90, gravel = 60, elevation = 2000))
        assertEquals(listOf("Gravel", "Mountain", "Bends", "Nature"), t)
    }

    @Test
    fun rankedExperienceCarriesItsTags() {
        val r = ExperienceRanker.rank(CountryPack("XX", "T", 1, listOf(exp("xx-t", nature = 90, km = 50.0))), ExperienceFilters()).single()
        assertEquals(listOf("Nature", "Short"), r.tags)
    }
}
