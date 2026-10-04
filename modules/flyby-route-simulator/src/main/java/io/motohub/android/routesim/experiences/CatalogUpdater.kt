// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// Fetches newer country packs from the catalogue site into the module's storage. Blocking: the
// caller runs it off the main thread. Nothing here throws; the report says what happened.
package io.motohub.android.routesim.experiences

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * [updated] are the countries whose pack was replaced, [failed] maps a country (or "index") to why it
 * was not, and [offline] says the index itself could not be reached, so nothing else was tried.
 */
data class UpdateReport(val updated: List<String>, val failed: Map<String, String>, val offline: Boolean)

/**
 * Downloads `<baseUrl>/index.json`, and for every country whose version there is higher than the one
 * installed, `<baseUrl>/<cc>.json`. A pack is written to `<storageDir>/experiences/<cc>.json` only
 * after it matches the index's sha256, parses, carries the index's version and holds at least
 * [MIN_EXPERIENCES] valid experiences; it goes through a temporary file and a rename, so a failure
 * leaves the previous file as it was. [fetch] returns a response body or throws [IOException].
 */
class CatalogUpdater(
    private val storageDir: File,
    baseUrl: String = DEFAULT_BASE_URL,
    private val fetch: (String) -> ByteArray = Companion::httpGet,
) {

    private val base = baseUrl.trimEnd('/')

    /** [installedVersions] maps a country code to the version of the pack in use; absent means none. */
    fun updateAll(installedVersions: Map<String, Int>): UpdateReport {
        val updated = ArrayList<String>()
        val failed = LinkedHashMap<String, String>()
        try {
            val index = try {
                CatalogJson.parseIndex(fetch("$base/index.json").toString(Charsets.UTF_8))
            } catch (e: HttpStatusException) {
                failed[INDEX] = e.message ?: "HTTP error"
                return UpdateReport(updated, failed, offline = false)
            } catch (e: IOException) {
                failed[INDEX] = e.message ?: e.javaClass.simpleName
                return UpdateReport(updated, failed, offline = true)
            }
            val installed = installedVersions.mapKeys { it.key.uppercase() }
            for (entry in index.packs) {
                if (entry.version <= (installed[entry.country] ?: 0)) continue
                val problem = try {
                    install(entry)
                } catch (e: Exception) {
                    e.message ?: e.javaClass.simpleName
                }
                if (problem == null) updated += entry.country else failed[entry.country] = problem
            }
        } catch (e: Exception) {
            failed.putIfAbsent(INDEX, e.message ?: e.javaClass.simpleName)
        }
        return UpdateReport(updated, failed, offline = false)
    }

    /** Null when the pack is installed, otherwise why not. */
    private fun install(entry: IndexEntry): String? {
        if (entry.bytes > MAX_BODY_BYTES) return "pack is larger than the limit"
        val code = entry.country.lowercase()
        val body = fetch("$base/$code.json")
        if (body.size > MAX_BODY_BYTES) return "pack is larger than the limit"
        if (sha256(body) != entry.sha256) return "checksum does not match the index"
        val parsed = CatalogJson.parsePack(body.toString(Charsets.UTF_8))
        if (parsed.pack.country != entry.country) return "pack is for \"${parsed.pack.country}\""
        if (parsed.pack.version != entry.version) return "pack version ${parsed.pack.version}, index says ${entry.version}"
        if (parsed.pack.experiences.size < MIN_EXPERIENCES) {
            return "only ${parsed.pack.experiences.size} valid experiences, need $MIN_EXPERIENCES"
        }

        val dir = File(storageDir, "experiences")
        if (!dir.isDirectory && !dir.mkdirs()) return "storage directory cannot be created"
        val target = File(dir, "$code.json")
        val tmp = File(dir, "$code.json.tmp")
        try {
            tmp.writeBytes(body)
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            tmp.delete()
        }
        return null
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** A server answer that is not a 200: the network works, so this is not "offline". */
    class HttpStatusException(message: String) : IOException(message)

    companion object {
        const val DEFAULT_BASE_URL = "https://motohub.techub.eu/experiences"
        const val MIN_EXPERIENCES = 10
        const val MAX_BODY_BYTES = 2 * 1024 * 1024
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 20_000
        const val USER_AGENT = "MOTO-HUB-FlybyRouteSimulator/0.1"
        private const val INDEX = "index"

        /** A plain GET; throws [IOException] on any failure, [HttpStatusException] on a non-200 answer. */
        fun httpGet(url: String): ByteArray {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("Accept", "application/json")
                val status = connection.responseCode
                if (status != HttpURLConnection.HTTP_OK) throw HttpStatusException("HTTP $status")
                return connection.inputStream.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (out.size() + n > MAX_BODY_BYTES) throw IOException("response is larger than the limit")
                        out.write(buffer, 0, n)
                    }
                    out.toByteArray()
                }
            } finally {
                connection.disconnect()
            }
        }
    }
}
