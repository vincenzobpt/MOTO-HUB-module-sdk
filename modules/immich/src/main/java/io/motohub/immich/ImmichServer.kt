// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.immich

import io.motohub.android.module.MotoHubModuleHost
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Properties
import java.util.concurrent.Semaphore

/** Who the key belongs to, and which Immich answered: kept so the page can say it without asking again. */
class Identity(val user: String, val version: String?)

/**
 * The rider's Immich: where it is, the key MOTO-HUB uses on it, and the photos with a position.
 *
 * The same API calls MOTO-HUB Studio makes: `GET /api/map/markers` (map.read),
 * `GET /api/assets/{id}` (asset.read), `GET /api/assets/{id}/thumbnail` (asset.view),
 * `GET /api/users/me` (user.read), `GET /api/server/version`. Only https: the app allows no
 * plain HTTP, and a key sent in the clear is a key anyone on the network can read.
 *
 * Blocking: call it off the main thread.
 */
class ImmichServer(private val host: MotoHubModuleHost) {

    private val configFile = File(host.storage, "config.properties")
    private val markersFile = File(host.storage, "markers.json")
    private val imageDir = File(host.storage, "images")
    // A home server, not a CDN: a page of thumbnails must not open twenty connections at once.
    private val gate = Semaphore(4)
    private val lock = Any()

    @Volatile var serverUrl = ""
    @Volatile var apiKey = ""
    @Volatile var lastRefresh = 0L
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile var identity: Identity? = null
        private set
    @Volatile private var markers: List<Marker>? = null
    @Volatile private var refreshing = false

    init {
        load()
    }

    val configured: Boolean get() = serverUrl.isNotBlank() && apiKey.isNotBlank()

    /** "photos.example.com" and "https://photos.example.com/api/" both become "https://photos.example.com"; null for http://. */
    val base: String?
        get() {
            val url = serverUrl.trim().trimEnd('/').removeSuffix("/api").trimEnd('/')
            if (url.isEmpty() || url.startsWith("http://")) return null
            return if (url.startsWith("https://")) url else "https://$url"
        }

    fun save() {
        val p = Properties()
        p.setProperty("url", serverUrl.trim())
        p.setProperty("keyEnc", if (apiKey.isBlank()) "" else Secret.seal(apiKey.trim()))
        identity?.let { p.setProperty("user", it.user); it.version?.let { v -> p.setProperty("version", v) } }
        host.storage.mkdirs()
        configFile.outputStream().use { p.store(it, "Immich module") }
    }

    private fun load() {
        if (configFile.isFile) {
            val p = Properties()
            runCatching { configFile.inputStream().use { p.load(it) } }
            serverUrl = p.getProperty("url", "")
            apiKey = p.getProperty("keyEnc", "").takeIf { it.isNotEmpty() }?.let { Secret.open(it) }.orEmpty()
            identity = p.getProperty("user")?.takeIf { it.isNotBlank() }?.let { Identity(it, p.getProperty("version")) }
        }
        if (markersFile.isFile) {
            markers = runCatching { Markers.parse(markersFile.readText()) }.getOrNull()
            lastRefresh = markersFile.lastModified()
        }
    }

    /** A new address or key: the photos held are another server's, or another account's. */
    fun forget() {
        synchronized(lock) {
            markers = null
            markersFile.delete()
            lastRefresh = 0L
            lastError = null
            identity = null
        }
        imageDir.deleteRecursively()
    }

    /** Asks the server who the key belongs to: null when it answered, or why not. */
    fun check(): String? {
        if (!configured) return "Enter the address and the API key first."
        if (base == null) return "The address must start with https://"
        return try {
            val me = JSONObject(String(get("/api/users/me")))
            val version = runCatching {
                val v = JSONObject(String(get("/api/server/version")))
                "${v.optInt("major")}.${v.optInt("minor")}.${v.optInt("patch")}"
            }.getOrNull()
            identity = Identity(me.text("name") ?: me.text("email") ?: "?", version)
            save()
            null
        } catch (e: IOException) {
            e.message ?: "No answer."
        }
    }

    /** Photos with a position held now, or null before the first download. */
    fun held(): List<Marker>? = markers

    /**
     * The photos with a position: downloaded the first time (blocking), then served from what is
     * held while a fresh copy comes in behind, at most every [REFRESH_MILLIS].
     */
    fun markers(): List<Marker> {
        val now = markers
        if (now == null) return refresh() ?: emptyList()
        if (System.currentTimeMillis() - lastRefresh > REFRESH_MILLIS && !refreshing) {
            Thread({ refresh() }, "immich-markers").start()
        }
        return now
    }

    /** Downloads every photo with a position again. Null when it failed; [lastError] says why. */
    fun refresh(): List<Marker>? {
        if (!configured || base == null) return null
        synchronized(lock) {
            if (refreshing) return markers
            refreshing = true
        }
        try {
            val json = String(get("/api/map/markers?isArchived=false&withPartners=false&withSharedAlbums=false"))
            val parsed = Markers.parse(json)
            host.storage.mkdirs()
            val tmp = File(host.storage, "markers.tmp")
            tmp.writeText(json)
            tmp.renameTo(markersFile)
            markers = parsed
            lastRefresh = System.currentTimeMillis()
            lastError = null
            return parsed
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            host.log.log("immich: markers failed: $lastError")
            return null
        } finally {
            refreshing = false
        }
    }

    /** The whole of one photo, and the albums that hold it (left out when the key may not read albums). */
    fun details(id: String): PhotoDetails {
        if (!SAFE_ID.matches(id)) throw IOException("Not an Immich photo.")
        val a = JSONObject(String(get("/api/assets/$id")))
        val albums = runCatching { PhotoDetails.albums(String(get("/api/albums?assetId=$id"))) }.getOrDefault(emptyList())
        return PhotoDetails.parse(a, albums)
    }

    /** A "thumbnail" or "preview" image of a photo, from the disk when it was fetched before. */
    fun image(id: String, size: String): ByteArray? {
        if (!SAFE_ID.matches(id)) return null
        val file = File(imageDir, "$size/$id.img")
        if (file.isFile) runCatching { return file.readBytes() }
        val bytes = get("/api/assets/$id/thumbnail?size=$size")
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "$id.tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(file)
        }
        return bytes
    }

    fun imageCacheBytes(): Long = imageDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun clearImages() {
        imageDir.deleteRecursively()
    }

    /** The photo in Immich's own web page. */
    fun webUrl(id: String): String? = base?.let { "$it/photos/$id" }

    private fun get(path: String): ByteArray {
        val root = base ?: throw IOException("The address must start with https://")
        if (apiKey.any { it.isWhitespace() || it.code < 0x21 || it.code > 0x7E }) throw IOException("The key has characters a key cannot have.")
        gate.acquire()
        try {
            val c = URL(root + path).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 30_000
            c.setRequestProperty("x-api-key", apiKey.trim())
            c.setRequestProperty("Accept", if ("/thumbnail" in path) "image/*" else "application/json")
            try {
                val code = c.responseCode
                if (code !in 200..299) {
                    val said = runCatching { JSONObject(c.errorStream.bufferedReader().readText()).optString("message") }.getOrNull()
                    throw IOException(listOfNotNull(problem(code), said?.takeIf { it.isNotBlank() }).joinToString(" "))
                }
                return c.inputStream.use { it.readBytes() }
            } finally {
                c.disconnect()
            }
        } catch (e: java.net.SocketTimeoutException) {
            throw IOException("Immich did not answer in time.")
        } catch (e: java.net.UnknownHostException) {
            throw IOException("Immich is not reachable at $root.")
        } catch (e: java.net.ConnectException) {
            throw IOException("Immich is not reachable at $root.")
        } finally {
            gate.release()
        }
    }

    private fun problem(status: Int) = when (status) {
        401 -> "Immich refused the key."
        403 -> "The key is not allowed to do this; give it the permission in Immich."
        404 -> "Immich does not know this address. Is it the one of the web page?"
        else -> "Immich answered $status."
    }

    companion object {
        const val THUMBNAIL = "thumbnail"
        const val PREVIEW = "preview"
        /** The whole picture, for a zoom past what the preview holds. */
        const val FULLSIZE = "fullsize"
        private const val REFRESH_MILLIS = 6 * 60 * 60_000L

        /** Immich ids are UUIDs: anything else never becomes part of a path or a file name. */
        private val SAFE_ID = Regex("[A-Za-z0-9-]{8,64}")
    }
}
