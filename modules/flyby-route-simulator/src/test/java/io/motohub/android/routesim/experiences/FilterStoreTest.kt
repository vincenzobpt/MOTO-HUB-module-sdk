// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.routesim.experiences

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FilterStoreTest {

    private lateinit var dir: File

    @Before fun setUp() { dir = Files.createTempDirectory("filters").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    private val file get() = File(dir, "experience-filters.json")

    private val sample = ExperienceFilters(
        minKm = 80, maxKm = 240, minMinutes = 90, maxMinutes = 300,
        nature = 70, twisty = null, gravel = 20, culture = 0, region = "Dolomites", detours = 5,
    )

    @Test
    fun emptyDirectoryGivesDefaults() {
        val s = FilterStore(dir)
        assertEquals(ExperienceFilters.DEFAULT, s.load("IT"))
        assertNull(s.lastCountry())
        assertEquals(ExperienceFilters.DEFAULT, FilterStore(File(dir, "not/yet/there")).load("IT"))
    }

    @Test
    fun roundTripKeepsEverythingIncludingNulls() {
        FilterStore(dir).save("IT", sample)
        assertEquals(sample, FilterStore(dir).load("IT"))
        FilterStore(dir).save("IT", ExperienceFilters.DEFAULT)
        assertEquals(ExperienceFilters.DEFAULT, FilterStore(dir).load("IT"))
        assertFalse(File(dir, "experience-filters.json.tmp").exists())
    }

    @Test
    fun countriesAreIsolated() {
        val s = FilterStore(dir)
        s.save("IT", sample)
        s.save("FR", sample.copy(maxKm = 100, region = null))
        assertEquals(sample, s.load("IT"))
        assertEquals(100, s.load("FR").maxKm)
        assertNull(s.load("FR").region)
        assertEquals(ExperienceFilters.DEFAULT, s.load("CH"))
        s.reset("IT")
        assertEquals(ExperienceFilters.DEFAULT, s.load("IT"))
        assertEquals(100, s.load("FR").maxKm)
    }

    @Test
    fun countryCodeIsCaseInsensitive() {
        val s = FilterStore(dir)
        s.save("it", sample)
        assertEquals(sample, s.load("IT"))
    }

    @Test
    fun valuesAreClampedOnSave() {
        val s = FilterStore(dir)
        s.save("IT", ExperienceFilters(minKm = -5, maxKm = 9999, minMinutes = 500, maxMinutes = 100, nature = 150, twisty = -3, gravel = 100, culture = 101, detours = 99))
        assertEquals(
            ExperienceFilters(minKm = 0, maxKm = 500, minMinutes = 100, maxMinutes = 500, nature = 100, twisty = 0, gravel = 100, culture = 100, detours = 6),
            s.load("IT"),
        )
        s.save("IT", ExperienceFilters(detours = -2))
        assertEquals(0, s.load("IT").detours)
    }

    @Test
    fun valuesAreSanitisedOnLoadFromAHandEditedFile() {
        file.writeText(
            """{"version":1,"countries":{"IT":{"minKm":900,"maxKm":50,"minMinutes":"x","maxMinutes":null,"nature":"high","twisty":250,"gravel":null,"region":"  ","detours":-4}}}""",
        )
        val f = FilterStore(dir).load("IT")
        assertEquals(50, f.minKm)
        assertEquals(500, f.maxKm)
        assertEquals(0, f.minMinutes)
        assertEquals(900, f.maxMinutes)
        assertNull(f.nature)
        assertEquals(100, f.twisty)
        assertNull(f.gravel)
        assertNull(f.region)
        assertEquals(0, f.detours)
    }

    @Test
    fun damagedFileCountsAsEmptyAndIsReplacedOnSave() {
        file.writeText("{not json at all")
        val s = FilterStore(dir)
        assertEquals(ExperienceFilters.DEFAULT, s.load("IT"))
        assertNull(s.lastCountry())
        s.reset("IT")
        s.save("IT", sample)
        assertEquals(sample, s.load("IT"))
        file.writeText("""{"countries":{"IT":"oops"}}""")
        assertEquals(ExperienceFilters.DEFAULT, s.load("IT"))
        file.writeText("[1,2,3]")
        assertEquals(ExperienceFilters.DEFAULT, s.load("IT"))
        s.saveLastCountry("FR")
        assertEquals("FR", s.lastCountry())
    }

    @Test
    fun resetOfUnknownCountryAndWithoutFileIsHarmless() {
        val s = FilterStore(dir)
        s.reset("IT")
        assertFalse(file.exists())
        s.save("FR", sample)
        s.reset("IT")
        assertEquals(sample, s.load("FR"))
    }

    @Test
    fun lastCountryIsKeptAlongsideTheFilters() {
        val s = FilterStore(dir)
        s.saveLastCountry("HR")
        s.save("IT", sample)
        assertEquals("HR", FilterStore(dir).lastCountry())
        assertEquals(sample, FilterStore(dir).load("IT"))
        s.reset("IT")
        assertEquals("HR", s.lastCountry())
        s.saveLastCountry("SI")
        assertEquals("SI", s.lastCountry())
    }

    @Test
    fun unwritableStorageNeverThrows() {
        val blocker = File(dir, "blocker").apply { writeText("a file where a directory should be") }
        val s = FilterStore(File(blocker, "sub"))
        s.save("IT", sample)
        s.saveLastCountry("IT")
        s.reset("IT")
        assertEquals(ExperienceFilters.DEFAULT, s.load("IT"))
        assertNull(s.lastCountry())
        assertTrue(blocker.isFile)
    }
}
