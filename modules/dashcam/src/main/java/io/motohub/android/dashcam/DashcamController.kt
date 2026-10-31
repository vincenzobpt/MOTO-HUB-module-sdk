// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.dashcam

import io.motohub.android.dashcam.net.CameraHttp
import io.motohub.android.dashcam.net.CameraWifi
import io.motohub.android.dashcam.net.Credentials
import io.motohub.android.dashcam.net.LocalNetworks
import io.motohub.android.dashcam.net.LoginRequired
import io.motohub.android.dashcam.probe.CameraProbe
import io.motohub.android.dashcam.probe.Redact
import io.motohub.android.dashcam.probe.Reports
import io.motohub.android.dashcam.protocol.CameraException
import io.motohub.android.dashcam.protocol.Answers
import io.motohub.android.dashcam.protocol.CameraFile
import io.motohub.android.dashcam.protocol.CameraIdentity
import io.motohub.android.dashcam.protocol.CameraProtocol
import io.motohub.android.dashcam.protocol.CameraStatus
import io.motohub.android.dashcam.video.LivePlayer
import io.motohub.android.module.MotoHubModuleHost
import java.io.File
import java.io.IOException
import java.net.DatagramSocket
import java.net.Socket
import java.util.Collections
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

/** The search for cameras on the phone's own network, for the page that shows it. */
data class ScanState(
    val running: Boolean = false,
    val progress: Float = 0f,
    /** The networks searched, as the rider reads them: "Wi-Fi", "Hotspot". */
    val networks: List<String> = emptyList(),
    val found: List<Candidate> = emptyList(),
    val searched: Boolean = false,
    /** When the last search ended, so a page reopened a moment later does not search again. */
    val at: Long = 0L
)

/** Working out how to show one address: found, wants a login, or the reason it cannot be shown. */
/** Where the camera that could not be shown was: the page that offers its report shows it. */
enum class ReportOrigin { CAMERA_WIFI, PHONE_NETWORK, LIVE }

enum class ReportPhase { NONE, PROBING, READY, SENDING, SENT, NOT_SENT }

/**
 * The probe of a camera this module could not show, and the report that carries it to us.
 * [canSend] when the app sends reports for modules (contract 38) and has a collector; otherwise
 * the page says how to send one from Diagnostics by hand.
 */
data class ReportState(
    val phase: ReportPhase = ReportPhase.NONE,
    val origin: ReportOrigin? = null,
    val canSend: Boolean = false,
    val supportId: String = ""
)

data class CheckState(
    val running: Boolean = false,
    val target: String = "",
    val message: String = "",
    val needsLogin: Boolean = false,
    val done: Boolean = false
)

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
    private val networks = LocalNetworks(host.context)
    private val lanSearch = LanSearch(networks) { log(it) }
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

    /** How this connection's sockets reach the camera: its Wi-Fi, or the phone's own network. */
    @Volatile private var binder: (Socket) -> Unit = wifi::bind

    /** The same for the UDP sockets of a camera that sends its RTP over UDP only; none on the hotspot. */
    @Volatile private var udpBinder: (DatagramSocket) -> Unit = { d -> wifi.network?.bindSocket(d) }

    /** The phone's address where the camera sees it; some cameras register their client by it. */
    @Volatile private var phoneAddress: String? = null

    private val _scan = MutableStateFlow(ScanState())
    val scan: StateFlow<ScanState> = _scan

    private val _check = MutableStateFlow(CheckState())
    val check: StateFlow<CheckState> = _check

    private val reports = Reports.of(host)
    private val _report = MutableStateFlow(ReportState())
    val report: StateFlow<ReportState> = _report
    private var liveWatch: Job? = null
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
        _report.value = ReportState()
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
            if (proto != null && client != null) runCatching { proto.logout(client, phoneAddress) }
            releaseWifiLock()
            // Only the join this one replaces: a newer reconnect may already be joining.
            wifi.release(joined)
            if (isActive) doConnect()
        }
    }

    private suspend fun doConnect() {
        if (motorcycleConnected()) {
            fail(
                if (store.lan) LAN_ON_BIKE
                else "The phone is connected to the motorcycle. Disconnect from it first: the phone has one Wi-Fi " +
                    "connection, and joining the camera would take it from the bike."
            )
            return
        }
        // Whose name to keep when it answers: the rider may pick another camera meanwhile.
        val camera = store.active
        if (store.lan) {
            doConnectLan(camera)
            return
        }
        binder = wifi::bind
        udpBinder = { d -> wifi.network?.bindSocket(d) }
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
            _state.value = _state.value.copy(phase = Phase.FINDING, message = "Studying the camera…")
            probeOnce("wifi:$ssid", ReportOrigin.CAMERA_WIFI) {
                val gateway = wifi.gateway()
                if (gateway == null) log("probe: the camera's Wi-Fi gives no gateway address to ask")
                else CameraProbe(::log).run(
                    gateway, wifi::bind, "not recognised on its own Wi-Fi",
                    context = "Wi-Fi \"${Redact.ssid(ssid)}\", phone at ${wifi.phoneAddress()}"
                )
            }
            if (!currentCoroutineContext().isActive) return
            val locked = wifi.gateway()?.let { g -> LanSearch.rtspServer(wifi::bind, g, 554) }?.let { LanSearch.lockedStream(it) }
            wifi.release(joined)
            fail(locked ?: ("The phone joined \"$ssid\" but did not recognise the camera on it. It may run a firmware this " +
                "module does not speak yet."))
            return
        }
        val (proto, client, identity) = found
        onConnected(proto, client, identity, wifi.phoneAddress(), camera)
    }

    /**
     * A camera on the phone's own network - its Wi-Fi or the hotspot the camera joined - at the
     * address found before: nothing to join, only to check that it is still there and as it was.
     */
    private suspend fun doConnectLan(camera: String) {
        val address = store.lanHost
        if (address.isBlank()) {
            fail("Find the camera on the network first.")
            return
        }
        _state.value = DashcamState(phase = Phase.FINDING, message = "Looking for the camera at $address…")
        val found = try {
            lanSearch.reopen(address, store.lanFamily, store.lanUrl, store.lanOnvif.ifEmpty { null }, store.lanName, lanLogin())
        } catch (e: Exception) {
            if (currentCoroutineContext().isActive) fail(e.message ?: "The camera did not answer at $address.")
            return
        }
        if (!currentCoroutineContext().isActive) return
        val net = networks.forHost(address)
        val bind = net?.bind ?: {}
        binder = bind
        udpBinder = { d -> net?.network?.bindSocket(d) }
        onConnected(found.protocol, CameraHttp(address, bind, 80, lanLogin()), found.identity, net?.phone?.hostAddress, camera)
    }

    /** The camera's own login, for the IP cameras that ask; null when none was ever given. */
    private fun lanLogin(): Credentials? =
        if (store.lanUser.isEmpty() && store.lanPassword.isEmpty()) null else Credentials(store.lanUser, store.lanPassword)

    /** What every connection does once the camera has answered, whichever way it was reached. */
    private fun onConnected(proto: CameraProtocol, client: CameraHttp, identity: CameraIdentity, phone: String?, camera: String) {
        if (_report.value.origin != ReportOrigin.LIVE) _report.value = ReportState()
        protocol = proto
        http = client
        phoneAddress = phone
        log("dashcam: ${proto.family} at ${client.host}, ${identity.maker} ${identity.model} ${identity.firmware}" +
            identity.details.joinToString("") { (k, v) -> ", $k: $v" })
        runCatching { proto.logon(client, phone) }.onFailure { log("dashcam: logon: ${it.message}") }
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
        val name = listOf(identity.maker, identity.model).filter(String::isNotBlank).joinToString(" ")
        if (name.isNotEmpty() && camera == store.active) {
            store.setLabel(camera, name)
            runCatching { store.save() }
        }
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
            if (proto != null && client != null) runCatching { proto.logout(client, phoneAddress) }
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
    fun startLive() {
        if (viewers.isEmpty()) return
        val player = livePlayer ?: return
        player.start()
        watchLive(player)
    }

    @Synchronized
    fun pauseLive() {
        liveWatch?.cancel()
        livePlayer?.stop()
    }

    /**
     * A camera whose family answered but whose video never came: after [NO_PICTURE_MS] of a live
     * view with no picture, the probe asks it what its video side says, and the page offers the report.
     */
    private fun watchLive(player: LivePlayer) {
        liveWatch?.cancel()
        liveWatch = scope.launch {
            delay(NO_PICTURE_MS)
            val proto = protocol
            val client = http
            if (livePlayer !== player || player.everPictured || proto == null || client == null) return@launch
            probeOnce("live:${client.host}", ReportOrigin.LIVE) {
                CameraProbe(::log).run(
                    client.host, binder, "no picture in ${NO_PICTURE_MS / 1000} s of live view", player.lastUrl,
                    "family ${proto.family}${if (proto.experimental) " (experimental)" else ""}"
                )
            }
        }
    }

    @Synchronized
    fun closeLive(viewer: LiveViewer) {
        if (!viewers.remove(viewer)) return
        val player = livePlayer ?: return
        val next = top()
        if (next != null) {
            player.setSurface(next.surface)
            return
        }
        liveWatch?.cancel()
        if (_report.value.origin == ReportOrigin.LIVE) _report.value = ReportState()
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
        bind = { s -> binder(s) },
        log = ::log,
        credentials = { if (store.lan) lanLogin() else null },
        bindUdp = { d -> udpBinder(d) },
        onFirstPicture = {
            if (protocol?.liveNeedsRecording == true) {
                log("live: picture up; switching recording off so the stream is not throttled")
                run(null, { p, h -> setRecordingForLive(p, h, false) }) { refreshStatus() }
            }
        }
    )

    /** Whether the camera has buttons, settings and files; a stream-only camera on the network has none. */
    val controllable: Boolean get() = protocol?.controllable ?: !(store.lan && store.lanFamily.isEmpty())

    // ---------------------------------------------------------------- a camera on the phone's network

    /** Searches the phone's Wi-Fi and hotspot for cameras; the page follows [scan]. */
    fun search() {
        if (_scan.value.running) return
        _scan.value = ScanState(running = true)
        scope.launch {
            // On the camera's own Wi-Fi: no place to look, and on most phones it has taken the phone's
            // own. Let it go, and give the phone's Wi-Fi a moment to come back before looking. One the
            // rider joined in Android's settings stays when let go, and is searched like any other.
            val cameraWifi = if (wifi.borrowed) null else wifi.network
            if (wifi.current != null) {
                disconnect()
                var waited = 0
                while (cameraWifi != null && waited < WIFI_RETURN_MS && networks.list().none { it.network != cameraWifi }) {
                    delay(500)
                    waited += 500
                }
            }
            val nets = networks.list().filter { it.network == null || it.network != cameraWifi }
            val kinds = ArrayList<String>()
            for (n in nets) if (n.kind !in kinds) kinds.add(n.kind)
            _scan.value = ScanState(running = true, networks = kinds)
            val found = if (nets.isEmpty() || motorcycleConnected()) emptyList() else {
                runCatching { lanSearch.search(nets) { p -> _scan.value = _scan.value.copy(progress = p) } }
                    .onFailure { log("scan: ${it.message}") }.getOrDefault(emptyList())
            }
            _scan.value = ScanState(progress = 1f, networks = kinds, found = found, searched = true, at = System.currentTimeMillis())
        }
    }

    /**
     * Works out how to show the camera at [target] (found by the search, or typed), and on success
     * keeps it and connects to it. [name] is what the search called it, kept when the camera
     * itself says nothing better.
     */
    fun check(target: String, onvif: String?, login: Credentials?, name: String = "") {
        if (_check.value.running) return
        _report.value = ReportState()
        _check.value = CheckState(running = true, target = target, message = "Checking $target…")
        scope.launch {
            if (motorcycleConnected()) {
                _check.value = CheckState(target = target, message = LAN_ON_BIKE)
                return@launch
            }
            // The phone's own address, taken for the camera's: nothing to check, and no probe of the phone.
            val typed = Target.parse(target)?.host
            val own = typed?.let { networks.ownedBy(it) }
            if (typed != null && own != null) {
                val message = "$typed is this phone's own address. The camera has another one on the same network: " +
                    "on a camera's own Wi-Fi it is usually ${LocalNetworks.firstHost(own.phone, own.prefix)}."
                log("scan: $target: $message")
                _check.value = CheckState(target = target, message = message)
                return@launch
            }
            // The login kept for this camera, if it is the one set up before.
            val auth = login ?: if (store.lan && Target.parse(target)?.host == store.lanHost) lanLogin() else null
            try {
                val found = lanSearch.resolve(target, onvif, auth)
                val given = auth ?: Target.parse(target)?.login
                store.lan = true
                store.lanHost = found.host
                store.lanFamily = if (found.url.isEmpty()) found.protocol.family else ""
                store.lanUrl = found.url
                store.lanOnvif = found.onvif.orEmpty()
                store.lanName = listOf(found.identity.maker, found.identity.model).filter(String::isNotBlank).joinToString(" ").ifEmpty { name }
                store.lanUser = given?.user.orEmpty()
                store.lanPassword = given?.password.orEmpty()
                runCatching { store.save() }
                _check.value = CheckState(target = target, done = true)
                reconnect()
            } catch (e: LoginRequired) {
                _check.value = CheckState(target = target, needsLogin = true, message = if (e.rejected) e.message.orEmpty() else "")
            } catch (e: Exception) {
                log("scan: $target: ${e.message}")
                val host = Target.parse(target)?.host
                // Something answered there and showed nothing this module can play: worth a probe.
                if (e is CameraException && host != null) {
                    probeOnce("lan:$host", ReportOrigin.PHONE_NETWORK) {
                        CameraProbe(::log).run(host, networks.bindFor(host), "nothing shown on the phone's network", context = "checked as $target")
                    }
                }
                _check.value = CheckState(target = target, message = e.message ?: "Nothing at $target shows a picture.")
            }
        }
    }

    // ---------------------------------------------------------------- the report of a camera not shown

    /**
     * Runs [work] - a probe - the first time camera [key] fails since the app started, then offers
     * the report. Once per app start rather than once ever: the log keeps only its last events,
     * and a report sent days later would no longer carry a probe made the first time.
     */
    private fun probeOnce(key: String, origin: ReportOrigin, work: () -> Unit) {
        if (PROBED.add(key)) {
            _report.value = ReportState(ReportPhase.PROBING, origin)
            runCatching(work).onFailure { log("probe: stopped: ${it.message}") }
        }
        _report.value = ReportState(ReportPhase.READY, origin, canSend = reports?.canSend == true)
    }

    /** Sends the report through the app, [model] - what the rider typed, if anything - written above it. */
    fun sendReport(model: String) {
        val r = reports ?: return
        val current = _report.value
        if (current.phase != ReportPhase.READY && current.phase != ReportPhase.NOT_SENT) return
        model.trim().takeIf { it.isNotEmpty() }?.let { log("dashcam: the rider says the camera is: ${it.take(80)}") }
        _report.value = current.copy(phase = ReportPhase.SENDING)
        r.send("dashcam: a camera it could not show (${current.origin?.name?.lowercase(java.util.Locale.ROOT)})") { sent ->
            _report.value = _report.value.copy(phase = if (sent) ReportPhase.SENT else ReportPhase.NOT_SENT, supportId = r.supportId())
        }
    }

    // ---------------------------------------------------------------- several cameras

    /**
     * Makes [id] the camera in use. One at a time - most cameras take the phone's only Wi-Fi - so
     * a connection to the one before is left and the new one joined in its place.
     */
    fun switchTo(id: String) {
        if (id == store.active) return
        store.select(id)
        runCatching { store.save() }
        if (_state.value.connected || _state.value.busy) reconnect() else _state.value = DashcamState()
    }

    /** Forgets camera [id]; leaving it first when it is the one connected. Files saved on the phone stay. */
    fun removeCamera(id: String) {
        if (id == store.active) disconnect()
        store.remove(id)
        runCatching { store.save() }
    }

    fun clearCheck() {
        if (!_check.value.running) _check.value = CheckState()
    }

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

    private companion object {
        const val LAN_ON_BIKE = "The phone is connected to the motorcycle, and Android sends the app's connections " +
            "there: a camera on the phone's network cannot be reached. Disconnect from the motorcycle first."

        const val WIFI_RETURN_MS = 8_000

        /** How long a live view may show nothing before the camera is probed. */
        const val NO_PICTURE_MS = 20_000L

        /** The cameras probed since the app started, by how they were reached: once each. */
        val PROBED: MutableSet<String> = Collections.synchronizedSet(HashSet())
    }

    fun release() {
        disconnect()
        // disconnect() lets go of the Wi-Fi on the scope, which is about to be cancelled.
        wifi.release()
        scope.cancel()
    }
}
