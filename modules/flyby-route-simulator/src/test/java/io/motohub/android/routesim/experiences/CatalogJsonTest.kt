// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.routesim.experiences

import io.motohub.android.routesim.experiences.CatalogTestPacks.experienceJson
import io.motohub.android.routesim.experiences.CatalogTestPacks.packJson
import io.motohub.android.routesim.experiences.CatalogTestPacks.place
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogJsonTest {

    private fun parseOne(edit: JSONObject.() -> Unit): ParsedPack =
        CatalogJson.parsePack(packJson(experiences = listOf(experienceJson("it-ok"), experienceJson("it-test", edit))))

    private fun assertDropped(edit: JSONObject.() -> Unit) {
        val parsed = parseOne(edit)
        assertEquals(listOf("it-ok"), parsed.pack.experiences.map { it.id })
        assertEquals(1, parsed.problems.size)
    }

    @Test
    fun goodPackParsesEveryField() {
        val parsed = CatalogJson.parsePack(packJson(version = 3, experiences = listOf(experienceJson("it-dolomiti-classic"))))
        assertTrue(parsed.problems.isEmpty())
        val pack = parsed.pack
        assertEquals("IT", pack.country)
        assertEquals("Italy", pack.name)
        assertEquals(3, pack.version)
        val e = pack.experiences.single()
        assertEquals("it-dolomiti-classic", e.id)
        assertEquals("Alps", e.region)
        assertEquals(ExperiencePlace("Start", 46.5, 11.3), e.from)
        assertEquals(ExperiencePlace("End", 46.9, 12.0), e.to)
        assertEquals(listOf(ExperiencePlace("Pass", 46.7, 11.6)), e.via)
        assertEquals(120.0, e.km, 0.0)
        assertEquals(180.0, e.minutes, 0.0)
        assertEquals(80, e.nature)
        assertEquals(70, e.twisty)
        assertEquals(0, e.gravel)
        assertEquals(2100, e.maxElevationM)
        assertEquals(listOf(46.5 to 11.3, 46.7 to 11.6, 46.9 to 12.0), e.shape)
    }

    @Test
    fun packRoundTripsThroughJson() {
        val pack = CatalogJson.parsePack(CatalogTestPacks.goodPack(version = 7, count = 3)).pack
        val again = CatalogJson.parsePack(CatalogJson.packToJson(pack))
        assertTrue(again.problems.isEmpty())
        assertEquals(pack, again.pack)
    }

    @Test
    fun viaIsOptional() {
        val parsed = CatalogJson.parsePack(packJson(experiences = listOf(experienceJson("it-direct") { remove("via") })))
        assertTrue(parsed.problems.isEmpty())
        assertTrue(parsed.pack.experiences.single().via.isEmpty())
    }

    @Test
    fun unreadableTextGivesAnEmptyPackAndAProblem() {
        for (text in listOf("", "not json", "[]", "{\"schema\":2}", "{\"schema\":1}", "{\"schema\":1,\"country\":\"it\",\"version\":1,\"experiences\":[]}")) {
            val parsed = CatalogJson.parsePack(text)
            assertTrue(text, parsed.pack.experiences.isEmpty())
            assertEquals(text, "", parsed.pack.country)
            assertEquals(text, 1, parsed.problems.size)
        }
    }

    @Test
    fun badVersionIsUnreadable() {
        val text = packJson(version = 0, experiences = listOf(experienceJson("it-a")))
        assertTrue(CatalogJson.parsePack(text).pack.experiences.isEmpty())
    }

    @Test
    fun anExperienceThatIsNotAnObjectIsDropped() {
        val root = JSONObject(CatalogTestPacks.goodPack(count = 2))
        root.getJSONArray("experiences").put(5).put("text")
        val parsed = CatalogJson.parsePack(root.toString())
        assertEquals(2, parsed.pack.experiences.size)
        assertEquals(2, parsed.problems.size)
    }

    @Test
    fun idRules() {
        assertDropped { remove("id") }
        assertDropped { put("id", "") }
        assertDropped { put("id", "IT-Upper") }
        assertDropped { put("id", "it_snake") }
        assertDropped { put("id", "it-") }
        assertDropped { put("id", "fr-wrong-country") }
        assertDropped { put("id", "it-two--dashes") }
        assertDropped { put("id", 12) }
    }

    @Test
    fun duplicateIdsKeepTheFirst() {
        val a = experienceJson("it-same") { put("name", "First") }
        val b = experienceJson("it-same") { put("name", "Second") }
        val parsed = CatalogJson.parsePack(packJson(experiences = listOf(a, b, experienceJson("it-other"))))
        assertEquals(listOf("First", "Ride it-other"), parsed.pack.experiences.map { it.name })
        assertEquals(listOf("it-same: duplicate id"), parsed.problems)
    }

    @Test
    fun coordinateRules() {
        assertDropped { put("from", place("Start", 91.0, 11.0)) }
        assertDropped { put("from", place("Start", 46.0, 181.0)) }
        assertDropped { put("to", place("End", -91.0, 0.0)) }
        assertDropped { put("from", JSONObject().put("name", "x").put("lat", 46.0)) }
        assertDropped { put("from", place("", 46.0, 11.0)) }
        assertDropped { remove("to") }
        assertDropped { put("via", JSONArray().put(place("Pass", 95.0, 11.0))) }
        assertDropped { put("via", "pass") }
        assertDropped {
            put("via", JSONArray().apply { (1..5).forEach { put(place("P$it", 46.5 + it / 100.0, 11.4)) } })
        }
    }

    @Test
    fun kmAndMinutesRanges() {
        assertDropped { put("km", 4.9) }
        assertDropped { put("km", 500.1) }
        assertDropped { put("km", "far") }
        assertDropped { remove("km") }
        assertDropped { put("minutes", 9) }
        assertDropped { put("minutes", 901) }
        assertDropped { remove("minutes") }
        val edges = CatalogJson.parsePack(packJson(experiences = listOf(
            experienceJson("it-low") { put("km", 5); put("minutes", 10) },
            experienceJson("it-high") { put("km", 500); put("minutes", 900) },
        )))
        assertEquals(2, edges.pack.experiences.size)
    }

    @Test
    fun valuesRunZeroToHundredAndAreIntegers() {
        assertDropped { put("nature", 101) }
        assertDropped { put("twisty", -1) }
        assertDropped { put("gravel", 50.5) }
        assertDropped { remove("gravel") }
        assertDropped { put("maxElevationM", 20000) }
        assertDropped { remove("maxElevationM") }
        val edges = CatalogJson.parsePack(packJson(experiences = listOf(
            experienceJson("it-zero") { put("nature", 0); put("twisty", 0); put("gravel", 0) },
            experienceJson("it-full") { put("nature", 100); put("twisty", 100); put("gravel", 100.0) },
        )))
        assertEquals(2, edges.pack.experiences.size)
    }

    @Test
    fun shapeNeedsTwoValidPoints() {
        assertDropped { remove("shape") }
        assertDropped { put("shape", JSONArray().put(JSONArray().put(46.5).put(11.3))) }
        assertDropped { put("shape", JSONArray().put(JSONArray().put(46.5).put(11.3)).put(JSONArray().put(46.6))) }
        assertDropped { put("shape", JSONArray().put(JSONArray().put(46.5).put(11.3)).put(JSONArray().put(99.0).put(11.3))) }
        assertDropped { put("shape", JSONArray().put(JSONArray().put(46.5).put(11.3)).put("x")) }
        val two = CatalogJson.parsePack(packJson(experiences = listOf(experienceJson("it-two") {
            put("shape", JSONArray().put(JSONArray().put(46.5).put(11.3)).put(JSONArray().put(46.9).put(12.0)))
        })))
        assertEquals(1, two.pack.experiences.size)
    }

    @Test
    fun endsMustDifferUnlessALoopSaysHow() {
        val here = place("Here", 46.5, 11.3)
        val almostHere = place("Almost", 46.5004, 11.3)
        assertDropped { put("from", here); put("to", here); put("via", JSONArray()) }
        assertDropped { put("from", here); put("to", almostHere); remove("via") }
        val loop = CatalogJson.parsePack(packJson(experiences = listOf(experienceJson("it-loop") { put("from", here); put("to", here) })))
        assertEquals(1, loop.pack.experiences.size)
        assertTrue(loop.problems.isEmpty())
        val apart = CatalogJson.parsePack(packJson(experiences = listOf(experienceJson("it-apart") {
            put("from", here); put("to", place("Two km", 46.52, 11.3)); put("via", JSONArray())
        })))
        assertEquals(1, apart.pack.experiences.size)
    }

    @Test
    fun aProblemNamesTheExperienceItIsAbout() {
        val parsed = parseOne { put("km", 9999) }
        assertTrue(parsed.problems.single().startsWith("it-test: "))
    }

    @Test
    fun missingNameIsDropped() {
        assertDropped { remove("name") }
        assertDropped { put("name", "  ") }
    }

    @Test
    fun indexParses() {
        val sha = "a".repeat(64)
        val text = CatalogTestPacks.indexJson(
            CatalogTestPacks.IndexLine("IT", "Italy", 2, sha, 1234),
            CatalogTestPacks.IndexLine("FR", "France", 1, sha.uppercase(), 99),
        )
        val index = CatalogJson.parseIndex(text)
        assertEquals(listOf(IndexEntry("IT", "Italy", 2, sha, 1234), IndexEntry("FR", "France", 1, sha, 99)), index.packs)
    }

    @Test
    fun indexDropsBadLinesAndNeverThrows() {
        val sha = "b".repeat(64)
        val text = CatalogTestPacks.indexJson(
            CatalogTestPacks.IndexLine("IT", "Italy", 1, sha, 1),
            CatalogTestPacks.IndexLine("it", "lower", 1, sha, 1),
            CatalogTestPacks.IndexLine("FR", "France", 0, sha, 1),
            CatalogTestPacks.IndexLine("DE", "Germany", 1, "short", 1),
            CatalogTestPacks.IndexLine("IT", "Italy again", 5, sha, 1),
            CatalogTestPacks.IndexLine("ES", "Spain", 1, sha, -1),
        )
        assertEquals(listOf("IT"), CatalogJson.parseIndex(text).packs.map { it.country })
        for (bad in listOf("", "{", "[]", "{\"schema\":9,\"packs\":[]}", "{\"schema\":1}")) {
            assertTrue(bad, CatalogJson.parseIndex(bad).packs.isEmpty())
        }
    }

    @Test
    fun nullAndBooleanFieldsDoNotCrash() {
        val parsed = parseOne { put("km", JSONObject.NULL); put("name", JSONObject.NULL) }
        assertFalse(parsed.pack.experiences.any { it.id == "it-test" })
    }
}
