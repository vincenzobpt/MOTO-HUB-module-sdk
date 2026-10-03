// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.routesim.learn

import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LearnedProfileStoreTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = java.nio.file.Files.createTempDirectory("learned-profile").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun full() = LearnedProfile(
        learnedAtMillis = 1_760_000_000_123L, ridesUsed = 7, kmUsed = 312.5, rpmRidesUsed = 3,
        maxLeanDeg = 47.25, lateralAccelMs2 = 5.125, accelMs2 = 2.75, brakeMs2 = 3.5, overLimitFactor = 1.04,
        gearRatios = doubleArrayOf(760.5, 560.25, 420.0, 311.5, 231.0, 171.0),
        idleRpm = 1250.0, maxRpm = 9800.5, shiftUpRpm = 6400.0, shiftDownRpm = 2900.0,
    )

    @Test
    fun nothingSavedMeansNothingLoaded() {
        assertNull(LearnedProfileStore(dir).load())
        assertNull(LearnedProfileStore(File(dir, "missing")).load())
    }

    @Test
    fun saveThenLoadGivesTheSameProfile() {
        val store = LearnedProfileStore(dir)
        val p = full()
        store.save(p)
        val q = LearnedProfileStore(dir).load()
        assertNotNull(q)
        q!!
        assertEquals(p.learnedAtMillis, q.learnedAtMillis)
        assertEquals(p.ridesUsed, q.ridesUsed)
        assertEquals(p.kmUsed, q.kmUsed, 0.0)
        assertEquals(p.rpmRidesUsed, q.rpmRidesUsed)
        assertEquals(p.maxLeanDeg, q.maxLeanDeg)
        assertEquals(p.lateralAccelMs2, q.lateralAccelMs2)
        assertEquals(p.accelMs2, q.accelMs2)
        assertEquals(p.brakeMs2, q.brakeMs2)
        assertEquals(p.overLimitFactor, q.overLimitFactor)
        assertArrayEquals(p.gearRatios, q.gearRatios, 0.0)
        assertEquals(p.idleRpm, q.idleRpm)
        assertEquals(p.maxRpm, q.maxRpm)
        assertEquals(p.shiftUpRpm, q.shiftUpRpm)
        assertEquals(p.shiftDownRpm, q.shiftDownRpm)
    }

    @Test
    fun fieldsThatWereNotLearnedStayNull() {
        val store = LearnedProfileStore(dir)
        store.save(LearnedProfile(5L, 3, 40.0, 0, 44.0, 4.5, null, null, null, null, null, null, null, null))
        val q = store.load()!!
        assertEquals(44.0, q.maxLeanDeg!!, 0.0)
        assertNull(q.accelMs2)
        assertNull(q.brakeMs2)
        assertNull(q.overLimitFactor)
        assertNull(q.gearRatios)
        assertNull(q.idleRpm)
        assertNull(q.maxRpm)
        assertNull(q.shiftUpRpm)
        assertNull(q.shiftDownRpm)
        assertEquals(0, q.rpmRidesUsed)
    }

    @Test
    fun savingAgainReplacesTheProfile() {
        val store = LearnedProfileStore(dir)
        store.save(full())
        store.save(LearnedProfile(9L, 3, 30.0, 0, 30.0, null, null, null, null, null, null, null, null, null))
        val q = store.load()!!
        assertEquals(9L, q.learnedAtMillis)
        assertNull(q.gearRatios)
    }

    @Test
    fun clearGoesBackToGeneric() {
        val store = LearnedProfileStore(dir)
        store.save(full())
        assertNotNull(store.load())
        store.clear()
        assertNull(store.load())
        store.clear() // twice is fine
        assertNull(store.load())
    }

    @Test
    fun aDamagedFileLoadsAsNothing() {
        val store = LearnedProfileStore(dir)
        val file = File(dir, "learned-profile.json")
        for (junk in listOf("", "not json at all", "{", "[]", "{\"version\":1}", "{\"learnedAtMillis\":\"x\"}")) {
            file.writeText(junk)
            assertNull("for <$junk>", store.load())
        }
    }

    @Test
    fun aFileWithNonsenseGearsLoadsAsNothing() {
        val file = File(dir, "learned-profile.json")
        file.writeText(
            "{\"version\":1,\"learnedAtMillis\":1,\"ridesUsed\":3,\"kmUsed\":30.0,\"rpmRidesUsed\":1,\"gearRatios\":[700.0,-5.0]}",
        )
        assertNull(LearnedProfileStore(dir).load())
    }

    @Test
    fun aDamagedFileCanBeOverwritten() {
        val store = LearnedProfileStore(dir)
        File(dir, "learned-profile.json").writeText("garbage")
        store.save(full())
        assertNotNull(store.load())
        assertFalse(File(dir, "learned-profile.json.tmp").exists())
    }

    @Test
    fun theDirectoryIsCreatedWhenMissing() {
        val nested = File(dir, "a/b")
        val store = LearnedProfileStore(nested)
        store.save(full())
        assertTrue(File(nested, "learned-profile.json").isFile)
        assertNotNull(store.load())
    }
}
