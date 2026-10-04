// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// The packs shipped inside the module, read from the source tree: what the rider gets must be valid.
package io.motohub.android.routesim.experiences

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledCatalogTest {

    private val dir = File("src/main/resources/experiences")

    private fun pack(code: String): ParsedPack = CatalogJson.parsePack(File(dir, "${code.lowercase()}.json").readText())

    @Test
    fun everyCatalogCountryHasAPackWithAtLeastTenValidExperiences() {
        for (code in CATALOG_COUNTRIES) {
            val parsed = pack(code)
            assertTrue("$code: ${parsed.problems}", parsed.problems.isEmpty())
            assertEquals(code, parsed.pack.country)
            assertTrue("$code has ${parsed.pack.experiences.size}", parsed.pack.experiences.size >= 10)
        }
    }

    @Test
    fun idsAreUniqueAcrossTheWholeCatalogue() {
        val ids = CATALOG_COUNTRIES.flatMap { code -> pack(code).pack.experiences.map { it.id } }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun everyPackHasRegionsAndASpreadOfLengthsAndCharacter() {
        for (code in CATALOG_COUNTRIES) {
            val list = pack(code).pack.experiences
            assertTrue("$code regions", ExperienceRanker.regions(CountryPack(code, code, 1, list)).size >= 3)
            assertTrue("$code short rides", list.any { it.km <= 130 })
            assertTrue("$code long rides", list.any { it.km >= 230 })
            assertTrue("$code twisty spread", list.any { it.twisty >= 75 } && list.any { it.twisty <= 40 })
            assertTrue("$code nature spread", list.any { it.nature >= 70 } && list.any { it.nature <= 45 })
        }
    }

    @Test
    fun theIndexMatchesTheFilesByteForByte() {
        val index = CatalogJson.parseIndex(File(dir, "index.json").readText())
        assertEquals(CATALOG_COUNTRIES.toSet(), index.packs.map { it.country }.toSet())
        for (entry in index.packs) {
            val bytes = File(dir, "${entry.country.lowercase()}.json").readBytes()
            assertEquals("${entry.country} size", entry.bytes, bytes.size.toLong())
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals("${entry.country} sha256", entry.sha256, hash)
            assertEquals("${entry.country} version", entry.version, pack(entry.country).pack.version)
        }
    }

    @Test
    fun theRankerAlwaysFillsTenCardsFromEveryBundledPack() {
        for (code in CATALOG_COUNTRIES) {
            val p = pack(code).pack
            assertEquals(10, ExperienceRanker.rank(p, ExperienceFilters()).size)
            assertEquals(10, ExperienceRanker.rank(p, ExperienceFilters(maxKm = 20, maxMinutes = 30, nature = 100, twisty = 100, gravel = 100)).size)
        }
    }
}
