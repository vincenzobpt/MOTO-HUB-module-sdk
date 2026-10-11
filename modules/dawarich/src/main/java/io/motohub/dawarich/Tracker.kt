// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Vincenzo Buonomano.
// Part of the MOTO-HUB module SDK; see module-api/LICENSE.
package io.motohub.dawarich

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.HandlerThread
import io.motohub.android.module.ModuleLiveState
import io.motohub.android.module.MotoHubModuleHost
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Follows the rider's GPS while MOTO-HUB has a session up (or the rider pressed "Track now") and
 * sends the points to Dawarich in batches. Points wait in a file until the server has taken them,
 * so a tunnel or a dead zone loses nothing.
 *
 * shortcut: the module cannot own a foreground service, so it only tracks while the app's own
 * service keeps the process alive; upgrade when the contract lends one.
 */
class Tracker(private val host: MotoHubModuleHost) {

    private val context: Context = host.context
    private val configFile = File(host.storage, "config.properties")
    private val queueFile = File(host.storage, "queue.jsonl")
    val sentRides = SentRides(File(host.storage, "sent-rides.properties"))
    private val lock = Any()
    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    // Uploads wait on the network; they must not hold up the tick that starts and stops the GPS.
    private val sender = Executors.newSingleThreadExecutor()
    private val kicked = AtomicBoolean(false)
    private var thread: HandlerThread? = null
    private var listener: LocationListener? = null

    @Volatile var serverUrl = ""
    @Volatile var apiKey = ""
    @Volatile var autoTrack = true
    @Volatile var live = true
    @Volatile var manual = false
    @Volatile var lastUpload = "Nothing sent yet"
    @Volatile var lastFixSeconds = 0L
    @Volatile var sent = 0L
    private var intervalMs = FAST_MS
    private var stillSince = 0L

    init {
        load()
        worker.scheduleWithFixedDelay({ safely { tick() } }, 3, 5, TimeUnit.SECONDS)
        worker.scheduleWithFixedDelay({ kick() }, 10, 20, TimeUnit.SECONDS)
    }

    val configured: Boolean get() = serverUrl.isNotBlank() && apiKey.isNotBlank()
    val tracking: Boolean get() = listener != null

    /** The Ride page's live pill (contract 27): there while the GPS is followed; it opens the module page. */
    val pill = MutableStateFlow<ModuleLiveState?>(null)
    val hasPermission: Boolean
        get() = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun queued(): Int = synchronized(lock) { readQueue().size }

    fun save() {
        val p = Properties()
        p.setProperty("url", serverUrl.trim())
        p.setProperty("keyEnc", if (apiKey.isBlank()) "" else Secret.seal(apiKey.trim()))
        p.setProperty("auto", autoTrack.toString())
        p.setProperty("live", live.toString())
        synchronized(lock) { configFile.outputStream().use { p.store(it, null) } }
    }

    fun release() {
        stopListening()
        worker.shutdownNow()
        sender.shutdownNow()
    }

    private fun load() {
        if (!configFile.isFile) return
        val p = Properties()
        configFile.inputStream().use { p.load(it) }
        serverUrl = p.getProperty("url", "")
        val sealed = p.getProperty("keyEnc", "")
        val plain = p.getProperty("key", "")
        apiKey = if (sealed.isNotEmpty()) Secret.open(sealed) ?: "" else plain
        autoTrack = p.getProperty("auto", "true") != "false"
        live = p.getProperty("live", "true") != "false"
        // The first version kept the key in clear: seal it now and drop the clear copy.
        if (plain.isNotEmpty()) save()
    }

    /** Asks for an upload now; asking again while one is already waiting its turn costs nothing. */
    private fun kick() {
        if (!kicked.compareAndSet(false, true)) return
        try {
            sender.execute { kicked.set(false); safely { upload() } }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            kicked.set(false)
        }
    }

    private fun safely(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            host.log.log("Dawarich: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun tick() {
        val want = configured && hasPermission && (manual || (autoTrack && host.session.isActive()))
        if (want && listener == null) startListening()
        if (!want && listener != null) stopListening()
    }

    private fun startListening() {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val t = HandlerThread("dawarich-gps")
        t.start()
        val l = LocationListener { loc -> accept(loc) }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, FAST_MS, 0f, l, t.looper)
        } catch (e: SecurityException) {
            t.quitSafely()
            return
        }
        thread = t
        listener = l
        intervalMs = FAST_MS
        stillSince = 0L
        pill.value = ModuleLiveState("Dawarich", "dawarich-settings")
        host.log.log("Dawarich: tracking started.")
    }

    private fun stopListening() {
        val l = listener ?: return
        (context.getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(l)
        thread?.quitSafely()
        thread = null
        listener = null
        pill.value = null
        host.log.log("Dawarich: tracking stopped.")
    }

    /** Stopped at a light is 3 s of fixes; stopped for a coffee is a drain. Slow down after a minute. */
    private fun adapt(loc: Location) {
        val l = listener ?: return
        val moving = loc.hasSpeed() && loc.speed > 1.5f
        val wanted = when {
            moving -> FAST_MS
            stillSince == 0L -> { stillSince = loc.time; intervalMs }
            loc.time - stillSince > STILL_MS -> SLOW_MS
            else -> intervalMs
        }
        if (moving) stillSince = 0L
        if (wanted == intervalMs) return
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm.removeUpdates(l)
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, wanted, 0f, l, thread?.looper)
        intervalMs = wanted
    }

    private fun accept(loc: Location) {
        adapt(loc)
        // A fix worse than this is a tunnel mouth or a cold start, and draws a spike on the map.
        if (loc.hasAccuracy() && loc.accuracy > 100f) return
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val fix = Fix(
            loc.latitude, loc.longitude, loc.time / 1000L,
            if (loc.hasAccuracy()) loc.accuracy else -1f,
            if (loc.hasAltitude()) loc.altitude else Double.NaN,
            if (loc.hasSpeed()) loc.speed else -1f,
            if (loc.hasBearing()) loc.bearing else -1f,
            if (level >= 0) level / scale.toFloat() else -1f,
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        )
        lastFixSeconds = fix.epochSeconds
        synchronized(lock) {
            java.io.FileOutputStream(queueFile, true).use { it.write((Overland.feature(fix).toString() + "\n").toByteArray()) }
            // shortcut: a ride offline for days drops the oldest points past this, not the newest.
            if (queueFile.length() > MAX_QUEUE_BYTES) {
                val keep = readQueue().takeLast(MAX_QUEUE_LINES)
                writeQueue(keep)
            }
        }
        if (live) kick()
    }

    /** Sends one batch; called every 20 s, repeats immediately while a full batch is waiting. */
    fun upload() {
        if (!configured) return
        while (true) {
            val batch = synchronized(lock) { readQueue().take(BATCH) }
            if (batch.isEmpty()) return
            val outcome = post(Overland.batch(batch.map { JSONObject(it) }))
            when (outcome) {
                Outcome.OK, Outcome.REJECTED -> synchronized(lock) {
                    writeQueue(readQueue().drop(batch.size))
                    if (outcome == Outcome.OK) sent += batch.size
                }
                Outcome.RETRY -> return
            }
            if (batch.size < BATCH) return
        }
    }

    private enum class Outcome { OK, REJECTED, RETRY }

    private fun endpoint(path: String): URL? {
        val base = serverUrl.trim().trimEnd('/')
        if (!base.startsWith("https://")) return null
        return URL("$base$path?api_key=" + URLEncoder.encode(apiKey.trim(), "UTF-8"))
    }

    /** Asks the server who the key belongs to. Blocks: call it off the main thread. */
    fun check(): String {
        if (!configured) return "Enter the address and the API key first."
        val url = endpoint("/api/v1/users/me") ?: return "The server address must start with https://"
        return try {
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            val code = c.responseCode
            c.disconnect()
            when {
                code in 200..299 -> "Connected: the server accepts this key."
                code == 401 || code == 403 -> "The server refused the API key ($code)."
                // Older Dawarich has no users/me: the server answers, the key is untested.
                code == 404 -> "The server answers, but this version cannot check the key."
                else -> "The server answered $code."
            }
        } catch (e: java.io.IOException) {
            "Cannot reach the server: ${e.message}"
        }
    }

    /**
     * Sends a recorded ride straight to the server, [BATCH] points at a time, without touching the
     * live queue. Dawarich ignores a point it already has, so sending a ride twice is harmless.
     * Blocks: call it off the main thread.
     */
    /**
     * How many points the server already holds between [startMillis] and [endMillis], or null when
     * it cannot say. Blocks: call it off the main thread. One point per page, so the page count
     * Dawarich returns is the point count; ISO dates, which old servers parse as well as new ones.
     */
    fun pointsOnServer(startMillis: Long, endMillis: Long): Int? {
        if (!configured) return null
        val iso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val base = endpoint("/api/v1/points") ?: return null
        val url = URL(
            "$base&per_page=1&slim=true" +
                "&start_at=" + URLEncoder.encode(iso.format(java.util.Date(startMillis)), "UTF-8") +
                "&end_at=" + URLEncoder.encode(iso.format(java.util.Date(endMillis)), "UTF-8")
        )
        return try {
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            val code = c.responseCode
            val pages = c.getHeaderField("X-Total-Pages")?.toIntOrNull()
            drain(c, code)
            if (code in 200..299) pages else null
        } catch (e: java.io.IOException) {
            null
        }
    }

    /** [SendResult.complete] only when every point reached the server. */
    fun sendTrack(fixes: List<Fix>, onProgress: (Int) -> Unit): SendResult {
        if (!configured) return SendResult(false, "Enter the address and the API key first.")
        var done = 0
        for (chunk in fixes.chunked(BATCH)) {
            val outcome = post(Overland.batch(chunk.map { Overland.feature(it) }))
            if (outcome == Outcome.RETRY) return SendResult(false, "Stopped after $done of ${fixes.size} points: $lastUpload")
            done += chunk.size
            onProgress(done)
        }
        return SendResult(true, "Sent $done points.")
    }

    private fun post(body: String): Outcome {
        return try {
            val url = endpoint("/api/v1/overland/batches")
            if (url == null) {
                lastUpload = "The server address must start with https://"
                return Outcome.RETRY
            }
            val c = url.openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            drain(c, code)
            when {
                code in 200..299 -> { lastUpload = "Sent at " + java.text.DateFormat.getTimeInstance().format(java.util.Date()); Outcome.OK }
                code == 401 || code == 403 -> { lastUpload = "Server refused the API key ($code)"; Outcome.RETRY }
                // A request the server will never accept: keep it and it blocks every later batch.
                code in 400..499 && code != 408 && code != 429 -> { lastUpload = "Server rejected a batch ($code), dropped it"; Outcome.REJECTED }
                else -> { lastUpload = "Server answered $code, will retry"; Outcome.RETRY }
            }
        } catch (e: java.io.IOException) {
            lastUpload = "No connection, will retry"
            Outcome.RETRY
        }
    }

    /** Reads the answer to the end, so the connection goes back to the pool instead of being torn down. */
    private fun drain(c: HttpURLConnection, code: Int) {
        val stream = (if (code >= 400) c.errorStream else c.inputStream) ?: return
        val buffer = ByteArray(1024)
        stream.use { while (it.read(buffer) >= 0) { } }
    }

    private fun readQueue(): List<String> =
        if (queueFile.isFile) queueFile.readLines().filter { it.isNotBlank() } else emptyList()

    private fun writeQueue(lines: List<String>) {
        val tmp = File(queueFile.parentFile, "queue.tmp")
        tmp.writeText(if (lines.isEmpty()) "" else lines.joinToString("\n", postfix = "\n"))
        tmp.renameTo(queueFile)
    }

    private companion object {
        const val FAST_MS = 3_000L
        const val SLOW_MS = 30_000L
        const val STILL_MS = 60_000L
        const val BATCH = 50
        const val MAX_QUEUE_BYTES = 8_000_000L
        const val MAX_QUEUE_LINES = 20_000
    }
}

class SendResult(val complete: Boolean, val message: String)
