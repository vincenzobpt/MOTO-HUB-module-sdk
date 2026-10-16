// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.dashcam

import io.motohub.android.dashcam.net.CameraHttp
import io.motohub.android.dashcam.net.CameraWifi
import io.motohub.android.dashcam.protocol.Answers
import io.motohub.android.dashcam.protocol.CameraFile
import io.motohub.android.dashcam.protocol.CameraIdentity
import io.motohub.android.dashcam.protocol.CameraProtocol
import io.motohub.android.dashcam.protocol.CameraStatus
import io.motohub.android.dashcam.video.LivePlayer
import io.motohub.android.module.MotoHubModuleHost
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class Phase { IDLE, JOINING, FINDING, CONNECTED, FAILED }

/** Everything the module's pages show about the camera. */
data class DashcamState(
    val phase: Phase = Phase.IDLE,
    val message: String = "",
    val family: String = "",
    val experimental: Boolean = false,
    val identity: CameraIdentity? = null,
    val status: CameraStatus? = null,
    val address: String = ""
) {
    val connected: Boolean get() = phase == Phase.CONNECTED
    val busy: Boolean get() = phase == Phase.JOINING || phase == Phase.FINDING
}

/**
 * The one connection to the camera, shared by the module's pages.
 *
 * Held while one of them is on screen and let go a little after the last one closes - long enough
 * to survive going from the About page to the live view, short enough not to keep the phone off
 * the internet for nothing.
 */
class DashcamController(private val host: MotoHubModuleHost) {
    val store = DashcamStore(host.storage)
    private val wifi = CameraWifi(host.context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(DashcamState())
    val state: StateFlow<DashcamState> = _state

    /** A short line the live view shows for a few seconds: "Photo taken", "Card formatted". */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice

    /** Downloads in progress, by camera path, 0..1 (negative while the size is unknown). */
    private val _downloads = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloads: StateFlow<Map<String, Float>> = _downloads

    @Volatile private var protocol: CameraProtocol? = null
    @Volatile private var http: CameraHttp? = null
    private var connectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var releaseJob: Job? = null
    private var users = 0

    /**
     * Keeps the phone's Wi-Fi out of power saving while connected to the camera. Without it the
     * radio sleeps between beacons and the camera's stream arrives in bursts - two seconds of
     * picture, half a second of nothing - and files load at a fraction of the camera's speed.
     * The camera's own app does the same (ExoPlayer's WifiLockManager, high-performance mode).
     * Low-latency mode where the phone has it; it falls back to high-performance by itself when
     * the screen is off or the app is in the background.
     */
    private val wifiLock = host.context.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
        ?.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "MOTO-HUB:dashcam")
        ?.apply { setReferenceCounted(false) }


    fun log(line: String) = host.log.log(line)

    /** Whether the phone is connected to the motorcycle, whose Wi-Fi the camera would take. */
    fun motorcycleConnected(): Boolean = runCatching { host.dashboardNetwork() != null }.getOrDefault(false)

    // ---------------------------------------------------------------- who is looking

    @Synchronized
    fun attach() {
        users++
        releaseJob?.cancel()
        releaseJob = null
    }

    @Synchronized
    fun detach() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) {
            releaseJob?.cancel()
            releaseJob = scope.launch {
                delay(20_000)
                if (users == 0) disconnect()
            }
        }
    }

    // ---------------------------------------------------------------- connecting

    fun connect() {
        if (_state.value.busy || _state.value.connected) return
        connectJob = scope.launch { doConnect() }
    }

    /** Leaves the camera it is on, if any, and joins the one in the store - one after the other. */
    fun reconnect() {
        connectJob?.cancel()
        heartbeatJob?.cancel()
        val proto = protocol
        val client = http
        val joined = wifi.current
        protocol = null
        http = null
        connectJob = scope.launch {
            if (proto != null && client != null) runCatching { proto.logout(client, wifi.phoneAddress()) }
            releaseWifiLock()
            // Only the join this one replaces: a newer reconnect may already be joining.
            wifi.release(joined)
            if (isActive) doConnect()
        }
    }

    private suspend fun doConnect() {
        if (motorcycleConnected()) {
            fail("The phone is connected to the motorcycle. Disconnect from it first: the phone has one Wi-Fi " +
                "connection, and joining the camera would take it from the bike.")
            return
        }
        val ssid = store.ssid
        if (ssid.isBlank()) {
            fail("Enter the camera's Wi-Fi name first.")
            return
        }
        _state.value = DashcamState(phase = Phase.JOINING, message = "Joining \"$ssid\"…")
        try {
            wifi.join(ssid, store.password) { onWifiLost() }
        } catch (e: Exception) {
            // Disconnect pressed while joining: not a failure to show, and it must not overwrite the next state.
            if (e is kotlinx.coroutines.CancellationException) throw e
            fail(e.message ?: "The phone could not join \"$ssid\".")
            return
        }
        val joined = wifi.current
        _state.value = _state.value.copy(phase = Phase.FINDING, message = "Looking for the camera…")
        val found = findCamera()
        // Disconnected or replaced while looking: the Wi-Fi and the state belong to whoever came next.
        if (!currentCoroutineContext().isActive) return
        if (found == null) {
            wifi.release(joined)
            fail("The phone joined \"$ssid\" but did not recognise the camera on it. It may run a firmware this " +
                "module does not speak yet: send a report from Diagnostics and say which camera it is.")
            return
        }
        val (proto, client, identity) = found
        protocol = proto
        http = client
        log("dashcam: ${proto.family} at ${client.host}, ${identity.maker} ${identity.model} ${identity.firmware}" +
            identity.details.joinToString("") { (k, v) -> ", $k: $v" })
        runCatching { proto.logon(client, wifi.phoneAddress()) }.onFailure { log("dashcam: logon: ${it.message}") }
        // The live view lost the camera before it could switch recording back on.
        if (pausedForLive && livePlayer == null && proto.liveNeedsRecording) {
            log("dashcam: recording was left off by the live view; switching it back on")
            runCatching { setRecordingForLive(proto, client, true) }.onFailure { log("dashcam: recording on: ${it.message}") }
        }
        runCatching { proto.syncClock(client) }
        val status = runCatching { proto.status(client) }.getOrNull()
        _state.value = DashcamState(
            phase = Phase.CONNECTED,
            family = proto.family,
            experimental = proto.experimental,
            identity = identity,
            status = status,
            address = client.host
        )
        startHeartbeat(proto, client)
        runCatching { wifiLock?.acquire() }.onSuccess { log("dashcam: Wi-Fi held in low-latency mode") }
            .onFailure { log("dashcam: could not hold the Wi-Fi awake: ${it.message}") }
    }

    /** The camera's network gateway first, with every family; then each family's usual address. */
    private fun findCamera(): Triple<CameraProtocol, CameraHttp, CameraIdentity>? {
        val gateway = wifi.gateway()
        val attempts = buildList {
            if (gateway != null) CameraProtocol.all.forEach { add(it to gateway) }
            CameraProtocol.all.forEach { p -> p.defaultHosts.filter { it != gateway }.forEach { add(p to it) } }
        }
        for ((proto, address) in attempts) {
            val client = CameraHttp(address, wifi::bind)
            val identity = try { proto.detect(client) } catch (e: Exception) { null }
            if (identity != null) return Triple(proto, client, identity)
        }
        return null
    }

    private fun startHeartbeat(proto: CameraProtocol, client: CameraHttp) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            var beat = 0
            var misses = 0
            while (isActive) {
                delay(4000)
                try {
                    proto.heartbeat(client)
                    misses = 0
                    if (++beat % 3 == 0) refreshStatus()
                } catch (e: Exception) {
                    // A camera busy formatting or changing codec skips a beat; three in a row is gone.
                    if (++misses >= 3) {
                        log("dashcam: heartbeat lost: ${e.message}")
                        _state.value = _state.value.copy(message = "The camera is not answering.")
                    }
                }
            }
        }
    }

    fun refreshStatus() {
        val proto = protocol ?: return
        val client = http ?: return
        runCatching { proto.status(client) }.onSuccess { s -> _state.value = _state.value.copy(status = s, message = "") }
    }

    private fun onWifiLost() {
        releaseWifiLock()
        heartbeatJob?.cancel()
        protocol = null
        http = null
        fail("The camera's Wi-Fi went away. Is the camera still on?")
    }

    private fun fail(message: String) {
        log("dashcam: $message")
        _state.value = DashcamState(phase = Phase.FAILED, message = message)
    }

    private fun releaseWifiLock() {
        runCatching { if (wifiLock?.isHeld == true) wifiLock.release() }
    }

    fun disconnect() {
        releaseWifiLock()
        connectJob?.cancel()
        heartbeatJob?.cancel()
        val proto = protocol
        val client = http
        val joined = wifi.current
        protocol = null
        http = null
        scope.launch {
            if (proto != null && client != null) runCatching { proto.logout(client, wifi.phoneAddress()) }
            // Only this connection's join: Connect pressed meanwhile has a new one.
            wifi.release(joined)
        }
        _state.value = DashcamState()
    }

    // ---------------------------------------------------------------- what the pages ask for

    /**
     * One place the live picture is shown: the full-screen view, or the dashboard's panel.
     *
     * They share a single stream. The camera serves one RTSP client comfortably and two badly, and
     * a second session would also mean switching recording on to open it and off again - a clip cut
     * short each time. So the picture goes to whichever viewer opened last; when it closes, the
     * one underneath gets it back without the stream ever stopping.
     */
    class LiveViewer internal constructor(val player: LivePlayer) {
        @Volatile internal var surface: android.view.Surface? = null
    }

    @Volatile private var livePlayer: LivePlayer? = null
    private val viewers = ArrayList<LiveViewer>()

    /**
     * The live picture switched the camera's recording off and nobody has switched it back on yet.
     * Unlike [protocol] it outlives a Wi-Fi drop, so the next connection can put recording back.
     */
    @Volatile private var pausedForLive = false
    private val recordingLock = Any()

    /**
     * Each side re-checks under the lock: a view closed just as the first picture landed must not
     * have its "off" reach the camera after the "on".
     */
    private fun setRecordingForLive(p: CameraProtocol, h: CameraHttp, on: Boolean) = synchronized(recordingLock) {
        if (on && pausedForLive) {
            p.setRecording(h, true)
            pausedForLive = false
        } else if (!on && livePlayer != null) {
            p.setRecording(h, false)
            pausedForLive = true
        }
    }

    // By index: the app keeps only the library calls it uses itself, and this module may not borrow more.
    private fun top(): LiveViewer? = if (viewers.isEmpty()) null else viewers[viewers.size - 1]

    @Synchronized
    fun openLive(): LiveViewer {
        val player = livePlayer ?: newLivePlayer().also { livePlayer = it }
        return LiveViewer(player).also {
            viewers.add(it)
            player.setSurface(null)
        }
    }

    @Synchronized
    fun setLiveSurface(viewer: LiveViewer, surface: android.view.Surface?) {
        viewer.surface = surface
        if (top() === viewer) viewer.player.setSurface(surface)
    }

    /** Starts the stream once connected; every viewer asks, the first one to do so starts it. */
    @Synchronized
    fun startLive() { if (viewers.isNotEmpty()) livePlayer?.start() }

    @Synchronized
    fun pauseLive() { livePlayer?.stop() }

    @Synchronized
    fun closeLive(viewer: LiveViewer) {
        if (!viewers.remove(viewer)) return
        val player = livePlayer ?: return
        val next = top()
        if (next != null) {
            player.setSurface(next.surface)
            return
        }
        player.stop()
        livePlayer = null
        // The last viewer gone: a camera whose recording the live picture paused records again.
        // Asked even while the "off" is still on its way: the lock puts this one after it.
        if (protocol?.liveNeedsRecording == true) {
            log("live: view closed; recording back on")
            run(null, { p, h -> setRecordingForLive(p, h, true) }) { refreshStatus() }
        }
    }

    private fun newLivePlayer(): LivePlayer = LivePlayer(
        prepare = { misses ->
            val proto = protocol ?: throw IOException("Not connected to the camera.")
            val client = http ?: throw IOException("Not connected to the camera.")
            proto.prepareLive(client, misses).also { if (misses > 0) log("live: no picture in $misses session(s); trying $it") }
        },
        bind = wifi::bind,
        log = ::log,
        onFirstPicture = {
            if (protocol?.liveNeedsRecording == true) {
                log("live: picture up; switching recording off so the stream is not throttled")
                run(null, { p, h -> setRecordingForLive(p, h, false) }) { refreshStatus() }
            }
        }
    )

    /** The camera's HTTP side, for ExoPlayer to read recordings through; null when not connected. */
    fun cameraHttp(): CameraHttp? = http

    val currentProtocol: CameraProtocol? get() = protocol

    /**
     * Runs [block] against the camera off the main thread; [done] gets its result, or the reason it
     * failed, back on the caller's side through [notice] when [announce] is set.
     */
    fun <T> run(announce: String?, block: (CameraProtocol, CameraHttp) -> T, done: (Result<T>) -> Unit = {}) {
        scope.launch {
            val proto = protocol
            val client = http
            val result = if (proto == null || client == null) {
                Result.failure(IOException("Not connected to the camera."))
            } else {
                runCatching { block(proto, client) }
            }
            result.exceptionOrNull()?.let {
                log("dashcam: ${it.message}")
                _notice.value = it.message ?: "The camera did not do it."
            } ?: announce?.let { _notice.value = it }
            done(result)
        }
    }

    fun clearNotice() { _notice.value = null }

    fun say(text: String) { _notice.value = text }

    /** Copies [file] off the camera into the module's own directory. */
    fun download(file: CameraFile) {
        if (_downloads.value.containsKey(file.path)) return
        val client = http ?: return
        // The camera names the file: "../" in it would write outside the downloads, over the app's own files.
        val name = Answers.fileName(file.name)
        if (name.isEmpty() || name == "." || name == "..") {
            _notice.value = "The camera gave this file a name that cannot be saved."
            return
        }
        _downloads.value = HashMap(_downloads.value).apply { put(file.path, -1f) }
        // A download counts as someone using the camera: leaving the module must not cut it off.
        attach()
        scope.launch {
            val target = File(store.downloads, name)
            val partial = File(store.downloads, "$name.part")
            try {
                client.open(file.path, emptyMap(), 20_000).use { s ->
                    if (s.code !in 200..299) throw IOException("The camera would not send ${file.name} (HTTP ${s.code}).")
                    val total = s.contentLength.takeIf { it > 0 } ?: file.sizeBytes
                    partial.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        var lastReport = 0L
                        while (true) {
                            val r = s.body.read(buffer)
                            if (r < 0) break
                            out.write(buffer, 0, r)
                            copied += r
                            if (total > 0 && copied - lastReport > 512 * 1024) {
                                lastReport = copied
                                _downloads.value = HashMap(_downloads.value).apply { put(file.path, (copied.toFloat() / total).coerceIn(0f, 1f)) }
                            }
                        }
                        // A camera that drops the connection mid-file ends the stream like a finished one;
                        // a cut MP4 has no index at its end and does not play.
                        if (s.contentLength > 0 && copied != s.contentLength) {
                            throw IOException("The camera stopped sending ${file.name} at $copied of ${s.contentLength} bytes.")
                        }
                    }
                }
                target.delete()
                if (!partial.renameTo(target)) throw IOException("${file.name} could not be saved on the phone.")
                _notice.value = "Saved ${file.name}"
            } catch (e: Exception) {
                partial.delete()
                _notice.value = e.message ?: "The download of ${file.name} failed."
            } finally {
                _downloads.value = HashMap(_downloads.value).apply { remove(file.path) }
                detach()
            }
        }
    }

    fun localFiles(): List<File> =
        store.downloads.listFiles { f -> f.isFile && !f.name.endsWith(".part") }?.sortedByDescending { it.lastModified() }.orEmpty()

    fun release() {
        disconnect()
        // disconnect() lets go of the Wi-Fi on the scope, which is about to be cancelled.
        wifi.release()
        scope.cancel()
    }
}
