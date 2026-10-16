// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.immich

import io.motohub.android.module.MotoHubModuleHost
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.Properties
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Who the key belongs to, and which Immich answered: kept so the page can say it without asking again. */
class Identity(val user: String, val version: String?)

/**
 * The rider's Immich: where it is, the key MOTO-HUB uses on it, and the photos with a position.
 *
 * The same API calls MOTO-HUB Studio makes: `GET /api/map/markers` (map.read),
 * `GET /api/assets/{id}` (asset.read), `GET /api/assets/{id}/thumbnail` (asset.view),
 * `GET /api/users/me` (user.read), `GET /api/server/version`; for the map's filter also
 * `GET /api/albums` and `GET /api/albums/{id}/map-markers` (album.read) and
 * `GET /api/timeline/buckets` (asset.read). Only https: the app allows no plain HTTP, and a key
 * sent in the clear is a key anyone on the network can read.
 *
 * Blocking: call it off the main thread.
 */
class ImmichServer(private val host: MotoHubModuleHost) {

    private val configFile = File(host.storage, "config.properties")
    private val markersFile = File(host.storage, "markers.json")
    // When the markers were fetched, all of them and only the new ones: the next refresh depends on it.
    private val markersStateFile = File(host.storage, "markers.properties")
    // The ids the filter lets through, so a ride without signal still shows the chosen photos.
    private val filteredFile = File(host.storage, "filtered.txt")
    private val imageDir = File(host.storage, "images")
    // A home server, not a CDN: a page of thumbnails must not open twenty connections at once.
    private val gate = Semaphore(4)
    private val lock = Any()
    private val filterLock = Any()

    @Volatile var serverUrl = ""
    @Volatile var apiKey = ""
    /** The last time markers came in, all of them or only the new ones. */
    @Volatile var lastRefresh = 0L
        private set
    // The last time all of them did: only a whole download sees a photo deleted or moved.
    @Volatile private var lastFull = 0L
    // A failed refresh is not tried again on every pan of the map: offline, each would fail the same.
    @Volatile private var lastTry = 0L
    @Volatile private var lastFilterTry = 0L
    @Volatile var lastError: String? = null
        private set
    @Volatile var identity: Identity? = null
        private set
    /** Which of the photos the map shows; saved with [save]. */
    @Volatile var filter = MapFilter.ALL
        set(value) {
            field = value
            // A new filter is a new question: the last one failing says nothing about it.
            lastFilterTry = 0L
        }
    @Volatile private var markers: List<Marker>? = null
    @Volatile private var refreshing = false
    @Volatile private var filtering = false
    @Volatile private var filtered: Filtered? = null
    @Volatile private var shownMemo: Shown? = null
    private val imageWrites = AtomicInteger()
    @Volatile private var lastTrim = 0L
    private val trimming = AtomicBoolean()

    /** The ids [filter] [key] lets through, as fetched while [against] were the markers held. */
    private class Filtered(val key: String, val ids: Set<String>, val against: List<Marker>)

    private class Shown(val all: List<Marker>, val filtered: Filtered, val list: List<Marker>)

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
        filter.store(p)
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
            filter = MapFilter.load(p)
        }
        if (markersFile.isFile) {
            val held = runCatching { Markers.parse(markersFile.readText()) }.getOrNull()
            markers = held
            val s = Properties()
            runCatching { markersStateFile.inputStream().use { s.load(it) } }
            // Before the state file existed the markers were only ever fetched whole.
            lastRefresh = s.getProperty("checked")?.toLongOrNull() ?: markersFile.lastModified()
            lastFull = s.getProperty("full")?.toLongOrNull() ?: markersFile.lastModified()
            if (held != null && filteredFile.isFile) {
                runCatching {
                    val lines = filteredFile.readLines()
                    if (lines.firstOrNull() == filter.key) filtered = Filtered(filter.key, lines.drop(1).toHashSet(), held)
                }
            }
        }
    }

    /** A new address or key: the photos held are another server's, or another account's. */
    fun forget() {
        synchronized(lock) {
            markers = null
            markersFile.delete()
            markersStateFile.delete()
            filteredFile.delete()
            filtered = null
            shownMemo = null
            lastRefresh = 0L
            lastFull = 0L
            lastTry = 0L
            lastFilterTry = 0L
            lastError = null
            identity = null
        }
        // Another server's album is not one of this server's.
        if (filter.albumId != null) {
            filter = filter.copy(albumId = null, albumName = null)
            save()
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
        } catch (e: org.json.JSONException) {
            // A web page where the server should be - a typo, a parked domain, a login wall.
            "This address answers, but not as an Immich server."
        }
    }

    /** Photos with a position held now, or null before the first download. */
    fun held(): List<Marker>? = markers

    /**
     * The photos with a position: downloaded the first time (blocking), then served from what is
     * held while the new ones come in behind, at most every [CHECK_MILLIS].
     */
    fun markers(): List<Marker> {
        val now = markers ?: return refresh() ?: emptyList()
        val t = System.currentTimeMillis()
        if ((t - lastRefresh > CHECK_MILLIS || t - lastFull > FULL_MILLIS) && t - lastTry > RETRY_MILLIS && !refreshing) {
            Thread({ update() }, "immich-markers").start()
        }
        return now
    }

    /** Whatever is due: every photo again once a day, otherwise only the recent ones. */
    fun update(): List<Marker>? = refresh(whole = markers == null || System.currentTimeMillis() - lastFull > FULL_MILLIS)

    /**
     * Fetches the photos with a position: all of them when [whole], otherwise only those taken since
     * a while before the last whole download, laid over what is held. Null when it failed, and what
     * is held stays; [lastError] says why.
     *
     * The markers carry no date, and fileCreatedAt is when a photo was taken, not uploaded: a ride's
     * photos uploaded days later are still caught by the [OVERLAP_MILLIS] before the last whole
     * download, and anything older that turns up late waits for the next whole one.
     */
    fun refresh(whole: Boolean = true): List<Marker>? {
        if (!configured || base == null) return null
        synchronized(lock) {
            if (refreshing) return markers
            refreshing = true
        }
        val started = System.currentTimeMillis()
        lastTry = started
        try {
            val held = markers
            val since = if (whole || held == null) null else lastFull - OVERLAP_MILLIS
            val json = String(get(MARKERS + (since?.let { "&fileCreatedAfter=" + Instant.ofEpochMilli(it) } ?: "")))
            val fresh = Markers.parse(json)
            val next = if (held == null || since == null) fresh else Markers.merge(held, fresh)
            host.storage.mkdirs()
            if (next !== held) write(markersFile, if (since == null) json else Markers.toJson(next))
            markers = next
            lastRefresh = started
            if (since == null) lastFull = started
            val s = Properties()
            s.setProperty("checked", lastRefresh.toString())
            s.setProperty("full", lastFull.toString())
            markersStateFile.outputStream().use { s.store(it, "Immich markers") }
            lastError = null
            return next
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            host.log.log("immich: markers failed: $lastError")
            return null
        } finally {
            refreshing = false
        }
    }

    /**
     * The photos the map shows: those held, less what [filter] leaves out. Only the server knows
     * which are favourites, of when, or in an album, so it is asked once per filter and set of
     * markers; until a fresh answer comes in, the last one serves.
     */
    fun shown(): List<Marker> {
        val all = markers()
        val f = filter
        if (f.everything) return all
        var set = filtered?.takeIf { it.key == f.key }
        if (set == null) {
            if (System.currentTimeMillis() - lastFilterTry < RETRY_MILLIS) return emptyList()
            set = narrow(f, all) ?: return emptyList()
        } else if (set.against !== all && !filtering && System.currentTimeMillis() - lastFilterTry > RETRY_MILLIS) {
            Thread({ narrow(f, all) }, "immich-filter").start()
        }
        shownMemo?.let { if (it.all === all && it.filtered === set) return it.list }
        val ids = set.ids
        val list = all.filter { it.id in ids }
        shownMemo = Shown(all, set, list)
        return list
    }

    /** How many photos the map shows now, or null while that is not known. */
    fun shownCount(): Int? {
        val all = markers ?: return null
        if (filter.everything) return all.size
        return shownMemo?.takeIf { it.all === all && it.filtered.key == filter.key }?.list?.size
    }

    private fun narrow(f: MapFilter, against: List<Marker>): Filtered? = synchronized(filterLock) {
        filtered?.let { if (it.key == f.key && it.against === against) return it }
        filtering = true
        lastFilterTry = System.currentTimeMillis()
        try {
            var ids: Set<String>? = null
            f.markerQuery(System.currentTimeMillis())?.let { q ->
                ids = Markers.parse(String(get(MARKERS + q))).mapTo(HashSet()) { it.id }
            }
            f.albumId?.let { album ->
                val inAlbum = albumIds(album)
                ids = ids?.let { it intersect inAlbum } ?: inAlbum
            }
            val done = Filtered(f.key, ids ?: emptySet(), against)
            filtered = done
            runCatching { write(filteredFile, (sequenceOf(done.key) + done.ids.asSequence()).joinToString("\n")) }
            lastFilterTry = 0L
            done
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            host.log.log("immich: filter failed: $lastError")
            null
        } finally {
            filtering = false
        }
    }

    /** The ids of the photos in one album: Immich 3 gives their markers, older ones the album with its photos. */
    private fun albumIds(id: String): Set<String> {
        if (!SAFE_ID.matches(id)) throw IOException("Not an Immich album.")
        return try {
            Markers.parse(String(get("/api/albums/$id/map-markers"))).mapTo(HashSet()) { it.id }
        } catch (e: HttpProblem) {
            if (e.status != 404) throw e
            val assets = JSONObject(String(get("/api/albums/$id?withoutAssets=false"))).optJSONArray("assets") ?: JSONArray()
            (0 until assets.length()).mapNotNullTo(HashSet()) { assets.optJSONObject(it)?.text("id") }
        }
    }

    /** The rider's albums, by name. */
    fun albums(): List<Album> = MapFilter.albums(String(get("/api/albums")))

    /** The years the library holds photos from, newest first; the last ten when the server will not say. */
    fun years(): List<Int> =
        runCatching { MapFilter.years(String(get("/api/timeline/buckets?visibility=timeline"))) }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: java.time.Year.now().value.let { y -> (y downTo y - 9).toList() }

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
        if (file.isFile) runCatching {
            val bytes = file.readBytes()
            // The cache drops what was read longest ago, so a read counts as use.
            file.setLastModified(System.currentTimeMillis())
            return bytes
        }
        val bytes = get("/api/assets/$id/thumbnail?size=$size")
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "$id.tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(file)
        }
        trimSoon()
        return bytes
    }

    // Listing thousands of cached files costs more than one image: trimmed every few writes or once a minute, on its own thread.
    private fun trimSoon() {
        val now = System.currentTimeMillis()
        if (imageWrites.incrementAndGet() < TRIM_EVERY_WRITES && now - lastTrim < TRIM_EVERY_MILLIS) return
        if (!trimming.compareAndSet(false, true)) return
        imageWrites.set(0)
        lastTrim = now
        Thread({
            try {
                trimCache(imageDir, IMAGE_CACHE_BYTES)
            } finally {
                trimming.set(false)
            }
        }, "immich-trim").start()
    }

    fun imageCacheBytes(): Long = imageDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun clearImages() {
        imageDir.deleteRecursively()
    }

    /** The photo in Immich's own web page. */
    fun webUrl(id: String): String? = base?.let { "$it/photos/$id" }

    private fun write(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) throw IOException("Could not save ${file.name}.")
    }

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
                    throw HttpProblem(code, listOfNotNull(problem(code), said?.takeIf { it.isNotBlank() }).joinToString(" "))
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

    private class HttpProblem(val status: Int, message: String) : IOException(message)

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
        /** What the saved images may take on the phone. */
        const val IMAGE_CACHE_BYTES = 300L * 1024 * 1024
        private const val MARKERS = "/api/map/markers?isArchived=false&withPartners=false&withSharedAlbums=false"
        private const val CHECK_MILLIS = 60 * 60_000L
        private const val FULL_MILLIS = 24 * 60 * 60_000L
        private const val OVERLAP_MILLIS = 7 * 24 * 60 * 60_000L
        private const val RETRY_MILLIS = 5 * 60_000L
        private const val TRIM_EVERY_WRITES = 20
        private const val TRIM_EVERY_MILLIS = 60_000L

        /** Immich ids are UUIDs: anything else never becomes part of a path or a file name. */
        private val SAFE_ID = Regex("[A-Za-z0-9-]{8,64}")
    }
}

/**
 * Deletes the images in [dir] read longest ago until they take less than [cap], with some room
 * to spare so the next few writes do not trim again at once. A file being written is left alone.
 */
internal fun trimCache(dir: File, cap: Long) {
    val files = dir.walkTopDown().filter { it.isFile && it.name.endsWith(".img") }.map { Triple(it, it.length(), it.lastModified()) }.toMutableList()
    var total = files.sumOf { it.second }
    if (total <= cap) return
    files.sortBy { it.third }
    for ((file, length, _) in files) {
        if (total <= cap / 10 * 9) break
        if (file.delete()) total -= length
    }
}
