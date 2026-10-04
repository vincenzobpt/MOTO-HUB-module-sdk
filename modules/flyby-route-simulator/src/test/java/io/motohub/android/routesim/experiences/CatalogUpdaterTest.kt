// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.routesim.experiences

import io.motohub.android.routesim.experiences.CatalogTestPacks.experienceJson
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CatalogUpdaterTest {

    private lateinit var dir: File
    private val base = "https://example.test/experiences"

    @Before fun setUp() { dir = Files.createTempDirectory("updater").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    /** A fake server: a map from URL to body; anything else throws like a dead connection. */
    private class Server(val files: MutableMap<String, ByteArray> = HashMap()) {
        val requests = ArrayList<String>()
        val fetch: (String) -> ByteArray = { url ->
            requests += url
            files[url] ?: throw IOException("no route to $url")
        }
        fun put(url: String, text: String) { files[url] = text.toByteArray(Charsets.UTF_8) }
    }

    private fun updater(server: Server) = CatalogUpdater(dir, base, server.fetch)

    private fun stored(code: String) = File(dir, "experiences/$code.json")

    private fun serve(server: Server, vararg packs: Triple<String, String, Int>) {
        val lines = packs.map { (code, text, version) -> CatalogTestPacks.lineFor(code.uppercase(), code, version, text) }
        server.put("$base/index.json", CatalogTestPacks.indexJson(*lines.toTypedArray()))
        packs.forEach { (code, text, _) -> server.put("$base/$code.json", text) }
    }

    @Test
    fun installsPacksThatAreNewerOrAbsent() {
        val server = Server()
        val it2 = CatalogTestPacks.goodPack("IT", 2, 12)
        val fr1 = CatalogTestPacks.goodPack("FR", 1, 10)
        serve(server, Triple("it", it2, 2), Triple("fr", fr1, 1))
        val report = updater(server).updateAll(mapOf("IT" to 1))
        assertEquals(listOf("IT", "FR"), report.updated)
        assertTrue(report.failed.isEmpty())
        assertFalse(report.offline)
        assertEquals(it2, stored("it").readText())
        assertEquals(fr1, stored("fr").readText())
        assertFalse(File(dir, "experiences/it.json.tmp").exists())
    }

    @Test
    fun theInstalledPackIsReadByTheCatalogue() {
        val server = Server()
        serve(server, Triple("it", CatalogTestPacks.goodPack("IT", 3, 12), 3))
        updater(server).updateAll(emptyMap())
        val catalog = ExperienceCatalog(dir, { null })
        assertEquals(3, catalog.pack("IT")?.version)
    }

    @Test
    fun aPackThatIsNotNewerIsNotFetched() {
        val server = Server()
        serve(server, Triple("it", CatalogTestPacks.goodPack("IT", 2, 12), 2))
        for (installed in listOf(2, 3)) {
            server.requests.clear()
            val report = updater(server).updateAll(mapOf("IT" to installed))
            assertTrue(report.updated.isEmpty())
            assertTrue(report.failed.isEmpty())
            assertEquals(listOf("$base/index.json"), server.requests)
        }
        assertFalse(stored("it").exists())
    }

    @Test
    fun installedVersionKeysAreCaseInsensitive() {
        val server = Server()
        serve(server, Triple("it", CatalogTestPacks.goodPack("IT", 2, 12), 2))
        assertTrue(updater(server).updateAll(mapOf("it" to 2)).updated.isEmpty())
    }

    @Test
    fun checksumMismatchIsRefusedAndKeepsTheOldFile() {
        val server = Server()
        val good = CatalogTestPacks.goodPack("IT", 2, 12)
        serve(server, Triple("it", good, 2))
        server.put("$base/it.json", CatalogTestPacks.goodPack("IT", 2, 13)) // not what the index vouches for
        File(dir, "experiences").mkdirs()
        stored("it").writeText("OLD")
        val report = updater(server).updateAll(mapOf("IT" to 1))
        assertTrue(report.updated.isEmpty())
        assertEquals(setOf("IT"), report.failed.keys)
        assertTrue(report.failed.getValue("IT"), report.failed.getValue("IT").contains("checksum"))
        assertFalse(report.offline)
        assertEquals("OLD", stored("it").readText())
        assertEquals(listOf("it.json"), File(dir, "experiences").list()!!.toList())
    }

    @Test
    fun aPackWithTooFewValidExperiencesIsRefused() {
        val server = Server()
        val thin = CatalogTestPacks.packJson("IT", 2, (1..12).map { n ->
            experienceJson("it-r-$n") { if (n > 3) put("km", 1) } // 9 of them break a rule
        })
        serve(server, Triple("it", thin, 2))
        val report = updater(server).updateAll(emptyMap())
        assertEquals(setOf("IT"), report.failed.keys)
        assertFalse(stored("it").exists())
    }

    @Test
    fun aPackWhoseVersionDiffersFromTheIndexIsRefused() {
        val server = Server()
        val text = CatalogTestPacks.goodPack("IT", 1, 12)
        server.put("$base/index.json", CatalogTestPacks.indexJson(CatalogTestPacks.lineFor("IT", "Italy", 2, text)))
        server.put("$base/it.json", text)
        val report = updater(server).updateAll(emptyMap())
        assertEquals(setOf("IT"), report.failed.keys)
        assertFalse(stored("it").exists())
    }

    @Test
    fun aPackForAnotherCountryIsRefused() {
        val server = Server()
        val text = CatalogTestPacks.goodPack("FR", 2, 12)
        server.put("$base/index.json", CatalogTestPacks.indexJson(CatalogTestPacks.lineFor("IT", "Italy", 2, text)))
        server.put("$base/it.json", text)
        assertEquals(setOf("IT"), updater(server).updateAll(emptyMap()).failed.keys)
    }

    @Test
    fun unreachableIndexMeansOffline() {
        val server = Server()
        val report = updater(server).updateAll(emptyMap())
        assertTrue(report.offline)
        assertTrue(report.updated.isEmpty())
        assertEquals(setOf("index"), report.failed.keys)
    }

    @Test
    fun aServerErrorIsNotOffline() {
        val report = CatalogUpdater(dir, base, { throw CatalogUpdater.HttpStatusException("HTTP 503") }).updateAll(emptyMap())
        assertFalse(report.offline)
        assertEquals("HTTP 503", report.failed["index"])
    }

    @Test
    fun oneFailingCountryDoesNotStopTheOthers() {
        val server = Server()
        val fr = CatalogTestPacks.goodPack("FR", 1, 10)
        val it1 = CatalogTestPacks.goodPack("IT", 1, 10)
        serve(server, Triple("it", it1, 1), Triple("fr", fr, 1))
        server.files.remove("$base/it.json")
        val report = updater(server).updateAll(emptyMap())
        assertEquals(listOf("FR"), report.updated)
        assertEquals(setOf("IT"), report.failed.keys)
    }

    @Test
    fun garbageIndexAndUnexpectedExceptionsNeverThrow() {
        val server = Server()
        server.put("$base/index.json", "<html>captive portal</html>")
        val report = updater(server).updateAll(emptyMap())
        assertTrue(report.updated.isEmpty())
        assertFalse(report.offline)
        val boom = CatalogUpdater(dir, base, { throw IllegalStateException("bug") }).updateAll(emptyMap())
        assertTrue(boom.updated.isEmpty())
        assertEquals(setOf("index"), boom.failed.keys)
    }

    @Test
    fun anOversizedPackIsRefusedBeforeItIsFetched() {
        val server = Server()
        val text = CatalogTestPacks.goodPack("IT", 1, 12)
        server.put("$base/index.json", CatalogTestPacks.indexJson(
            CatalogTestPacks.IndexLine("IT", "Italy", 1, CatalogTestPacks.sha256(text), 5L * 1024 * 1024)))
        server.put("$base/it.json", text)
        val report = updater(server).updateAll(emptyMap())
        assertEquals(setOf("IT"), report.failed.keys)
        assertEquals(listOf("$base/index.json"), server.requests)
    }

    @Test
    fun aTrailingSlashOnTheBaseUrlIsHarmless() {
        val server = Server()
        serve(server, Triple("it", CatalogTestPacks.goodPack("IT", 1, 10), 1))
        assertEquals(listOf("IT"), CatalogUpdater(dir, "$base/", server.fetch).updateAll(emptyMap()).updated)
    }

    @Test
    fun aFailedWriteLeavesTheOldFileAndNoTemporaryFile() {
        val server = Server()
        serve(server, Triple("it", CatalogTestPacks.goodPack("IT", 2, 12), 2))
        // The target is a non-empty directory: the rename onto it cannot succeed.
        val target = stored("it")
        File(target, "keep").mkdirs()
        val report = updater(server).updateAll(mapOf("IT" to 1))
        assertTrue(report.updated.isEmpty())
        assertEquals(setOf("IT"), report.failed.keys)
        assertTrue(File(target, "keep").isDirectory)
        assertFalse(File(dir, "experiences/it.json.tmp").exists())
    }

    @Test
    fun anUnwritableStorageDirectoryIsAFailureNotACrash() {
        val blocked = File(dir, "blocked").apply { writeText("a file where a directory should be") }
        val server = Server()
        serve(server, Triple("it", CatalogTestPacks.goodPack("IT", 2, 12), 2))
        val report = CatalogUpdater(blocked, base, server.fetch).updateAll(emptyMap())
        assertEquals(setOf("IT"), report.failed.keys)
        assertArrayEquals("a file where a directory should be".toByteArray(), blocked.readBytes())
    }

    @Test
    fun defaultsMatchTheContract() {
        assertEquals("https://motohub.techub.eu/experiences", CatalogUpdater.DEFAULT_BASE_URL)
        assertEquals(10, CatalogUpdater.MIN_EXPERIENCES)
        assertEquals(2 * 1024 * 1024, CatalogUpdater.MAX_BODY_BYTES)
    }
}
