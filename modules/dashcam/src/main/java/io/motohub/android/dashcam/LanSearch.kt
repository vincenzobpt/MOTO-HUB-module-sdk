// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.dashcam

import io.motohub.android.dashcam.net.CameraHttp
import io.motohub.android.dashcam.net.Credentials
import io.motohub.android.dashcam.net.Discovery
import io.motohub.android.dashcam.net.LocalNet
import io.motohub.android.dashcam.net.LocalNetworks
import io.motohub.android.dashcam.net.LoginRequired
import io.motohub.android.dashcam.net.Onvif
import io.motohub.android.dashcam.protocol.CameraException
import io.motohub.android.dashcam.protocol.CameraIdentity
import io.motohub.android.dashcam.protocol.CameraProtocol
import io.motohub.android.dashcam.protocol.StreamOnlyCamera
import io.motohub.android.dashcam.video.MjpegReader
import io.motohub.android.dashcam.video.RtspClient
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

/** Something on the phone's networks that may be a camera, and what answered there. */
class Candidate(
    val host: String,
    val name: String,
    /** What was found on it, as the rider reads it: "eeasytech", "ONVIF · RTSP". */
    val services: String,
    /** Its ONVIF device service, when it answered the discovery probe. */
    val onvif: String?,
    /**
     * What to check when it is picked: the address, or the stream itself when the search already
     * found it somewhere the address alone would not lead (an MJPEG page, RTSP on an odd port).
     */
    val target: String = host
)

/** A camera ready to connect to: a dashcam family the module speaks, or a stream and nothing else. */
class Resolved(
    val host: String,
    val protocol: CameraProtocol,
    val identity: CameraIdentity,
    /** The stream address of a stream-only camera (rtsp:// or MJPEG http://), no login in it; empty for a family. */
    val url: String,
    val onvif: String?
)

/** What the rider typed: an address, perhaps with a port, or a whole `rtsp://` or MJPEG `http://` stream address. */
class Target(val host: String, val port: Int?, val url: String?, val login: Credentials?) {
    companion object {
        /**
         * Reads the address field. A login written into an `rtsp://user:pass@host/…` address is
         * taken out of it - the address is stored and logged, the password must not be.
         */
        fun parse(text: String): Target? {
            val t = text.trim()
            if (t.isEmpty()) return null
            if (t.startsWith("rtsp://", ignoreCase = true) || t.startsWith("http://", ignoreCase = true)) {
                val u = runCatching { URI(t) }.getOrNull() ?: return null
                val host = u.host ?: return null
                val login = u.rawUserInfo?.let { info ->
                    Credentials(decode(info.substringBefore(':')), decode(info.substringAfter(':', "")))
                }
                val port = u.port.takeIf { it > 0 }
                val http = t.startsWith("http", ignoreCase = true)
                val path = u.rawPath.orEmpty()
                // A web address with no path says only where the camera is; with one, it is an MJPEG stream's.
                if (http && (path.isEmpty() || path == "/") && u.rawQuery == null) return Target(host, port, null, login)
                val clean = buildString {
                    append(if (http) "http://" else "rtsp://").append(host)
                    if (port != null) append(':').append(port)
                    append(path.ifEmpty { "/" })
                    u.rawQuery?.let { append('?').append(it) }
                }
                return Target(host, port, clean, login)
            }
            val host = t.substringBefore(':').trim()
            val port = if (t.contains(':')) t.substringAfter(':').trim().toIntOrNull() ?: return null else null
            if (host.isEmpty() || host.contains('/') || host.contains(' ')) return null
            return Target(host, port, null, null)
        }

        private fun decode(text: String) = runCatching { java.net.URLDecoder.decode(text, "UTF-8") }.getOrDefault(text)
    }
}

/**
 * Finding a camera on the network the phone is already on - its Wi-Fi, or the hotspot the camera
 * has joined - and working out how to show it.
 *
 * The search knocks on the usual video and web ports of every address around the phone and, at the
 * same time, asks the network for ONVIF cameras and for whatever announces itself over mDNS or
 * SSDP. What it finds is then asked, in order, whether it is a dashcam family this module speaks
 * (every button then works), an ONVIF camera (which tells where its stream is), an RTSP server
 * with video at one of the addresses cameras commonly use, or at least an MJPEG stream over HTTP.
 *
 * Everything blocks: call it off the main thread.
 */
class LanSearch(private val networks: LocalNetworks, private val log: (String) -> Unit) {

    /** Every address around the phone on [nets]; [progress] runs 0..1 as the knocks come back. */
    fun search(nets: List<LocalNet>, progress: (Float) -> Unit): List<Candidate> {
        val pool = Executors.newFixedThreadPool(THREADS)
        try {
            val onvif = pool.submit(Callable { Onvif.discover(nets, ONVIF_WAIT_MS, log) })
            val announced = pool.submit(Callable { Discovery.listen(nets, ONVIF_WAIT_MS, log) })
            val hits = ConcurrentLinkedQueue<Pair<String, Int>>()
            val knocks = ArrayList<Future<*>>()
            val done = AtomicInteger()
            val where = HashMap<String, LocalNet>()
            for (net in nets) for (ip in LocalNetworks.sweep(LocalNetworks.toInt(net.phone), net.prefix)) {
                val host = LocalNetworks.toText(ip)
                where[host] = net
            }
            val total = (where.size * SWEEP_PORTS.size).coerceAtLeast(1)
            for ((host, net) in where) for (port in SWEEP_PORTS) {
                knocks.add(pool.submit(Runnable {
                    if (knock(net.bind, host, port, KNOCK_MS)) hits.add(host to port)
                    progress(done.incrementAndGet().toFloat() / total)
                }))
            }
            for (k in knocks) k.get()

            val ports = LinkedHashMap<String, ArrayList<Int>>()
            for ((host, port) in hits) ports.getOrPut(host) { ArrayList() }.add(port)
            val devices = runCatching { onvif.get() }.getOrDefault(emptyList())
            val onvifByHost = HashMap<String, String>()
            val onvifNames = HashMap<String, String>()
            for (d in devices) {
                onvifByHost[d.host] = d.xaddr
                onvifNames[d.host] = d.name
                if (!ports.containsKey(d.host)) ports[d.host] = ArrayList()
            }

            // What announced itself: its name and RTSP port kept, and the few outside the swept
            // /24 knocked on now, like every address inside it was.
            val heard = runCatching { announced.get() }.getOrDefault(emptyList())
            val heardNames = HashMap<String, String>()
            val rtspAt = HashMap<String, Int>()
            val upnp = HashMap<String, String>()
            val outside = ConcurrentLinkedQueue<Pair<String, Int>>()
            val late = ArrayList<Future<*>>()
            for (h in heard) {
                if (h.name.isNotBlank()) heardNames[h.host] = h.name
                h.rtspPort?.let { rtspAt[h.host] = it }
                h.location?.let { upnp[h.host] = it }
                if (where.containsKey(h.host) || ports.containsKey(h.host)) continue
                ports[h.host] = ArrayList()
                val bind = networks.bindFor(h.host)
                for (port in SWEEP_PORTS) late.add(pool.submit(Runnable { if (knock(bind, h.host, port, KNOCK_MS)) outside.add(h.host to port) }))
            }
            for (k in late) k.get()
            for ((host, port) in outside) ports[host]?.add(port)

            // Who answers on port 80 may be a dashcam of a family spoken here; asked in parallel.
            val families = HashMap<String, Future<Pair<CameraProtocol, CameraIdentity>?>>()
            for ((host, open) in ports) {
                if (80 !in open) continue
                val bind = where[host]?.bind ?: networks.bindFor(host)
                families[host] = pool.submit(Callable { detectFamily(CameraHttp(host, bind)) })
            }
            val familyOf = HashMap<String, Pair<CameraProtocol, CameraIdentity>>()
            for ((host, f) in families) runCatching { f.get() }.getOrNull()?.let { familyOf[host] = it }

            // A web server and nothing else may still be a camera: one serving MJPEG at the usual addresses.
            val mjpeg = HashMap<String, Future<String?>>()
            for ((host, open) in ports) {
                if (familyOf.containsKey(host) || onvifByHost.containsKey(host) || rtspAt.containsKey(host)) continue
                if (open.any { it in RTSP_PORTS }) continue
                val web = MJPEG_PORTS.filter { it in open }
                if (web.isEmpty()) continue
                val bind = where[host]?.bind ?: networks.bindFor(host)
                mjpeg[host] = pool.submit(Callable { mjpegGuesses(host, web).firstOrNull { tryMjpeg(it, bind, null, quiet = true) } })
            }

            // Families first (every button works), then ONVIF, then bare RTSP, then MJPEG; by address within each.
            val ranked = listOf(ArrayList<Candidate>(), ArrayList<Candidate>(), ArrayList<Candidate>(), ArrayList<Candidate>())
            val unnamed = HashMap<Candidate, Future<String?>>()
            for ((host, open) in ports) {
                val family = familyOf[host]
                val xaddr = onvifByHost[host]
                val rtspPort = rtspAt[host]
                val rtsp = open.any { it in RTSP_PORTS } || rtspPort != null
                val stream = if (family == null && xaddr == null && !rtsp) mjpeg[host]?.let { runCatching { it.get() }.getOrNull() } else null
                if (family == null && xaddr == null && !rtsp && stream == null) continue
                val identityName = family?.second?.let { listOf(it.maker, it.model).filter(String::isNotBlank).joinToString(" ") }.orEmpty()
                val name = identityName.ifEmpty { onvifNames[host].orEmpty() }.ifEmpty { heardNames[host].orEmpty() }
                val services = listOfNotNull(
                    family?.first?.family, if (xaddr != null) "ONVIF" else null, if (rtsp) "RTSP" else null, if (stream != null) "MJPEG" else null
                ).joinToString(" · ")
                // RTSP heard of only on a port of its own: checked there, where the usual ports would miss it.
                val target = when {
                    stream != null -> stream
                    family == null && xaddr == null && rtspPort != null && open.none { it in RTSP_PORTS } -> "$host:$rtspPort"
                    else -> host
                }
                val candidate = Candidate(host, name.ifEmpty { "Camera" }, services, xaddr, target)
                ranked[if (family != null) 0 else if (xaddr != null) 1 else if (rtsp) 2 else 3].add(candidate)
                val location = upnp[host]
                if (name.isEmpty() && location != null) {
                    val bind = where[host]?.bind ?: networks.bindFor(host)
                    unnamed[candidate] = pool.submit(Callable { Discovery.describe(location, bind) })
                }
            }
            // A camera that said nothing better about itself goes by the name in its UPnP description.
            for (group in ranked) for (i in 0 until group.size) {
                val c = group[i]
                val described = unnamed[c]?.let { runCatching { it.get() }.getOrNull() } ?: continue
                group[i] = Candidate(c.host, described, c.services, c.onvif, c.target)
            }
            val byAddress = Comparator<Candidate> { a, b -> Integer.compareUnsigned(address(a.host), address(b.host)) }
            val out = ArrayList<Candidate>()
            for (group in ranked) {
                Collections.sort(group, byAddress)
                out.addAll(group)
            }
            // What answered and was not taken for a camera: in a report, the only trace of a camera
            // the rider says is there and the search passed by.
            val others = StringBuilder()
            var listed = 0
            for ((host, open) in ports) {
                if (open.isEmpty() || out.any { it.host == host }) continue
                if (listed++ == MAX_OTHERS) { others.append(", …"); break }
                Collections.sort(open)
                others.append(if (others.isEmpty()) "; also open: " else ", ").append(host).append(':').append(open.joinToString("/"))
            }
            log("scan: ${nets.joinToString { it.label }}: ${hits.size} open ports, ${devices.size} ONVIF, ${heard.size} announced, ${out.size} camera(s)" +
                out.joinToString("") { "; ${it.host} ${it.services}" } + others)
            return out
        } finally {
            pool.shutdownNow()
        }
    }

    private fun address(host: String): Int = LocalNetworks.literal(host)?.let { LocalNetworks.toInt(it) } ?: 0

    /**
     * Works out how to show the camera at [text] (an address, `address:port` or `rtsp://…`), using
     * its ONVIF service [onvif] when the search found one. Throws [LoginRequired] when the camera
     * wants a login, [CameraException] with a reason when nothing there shows a picture.
     */
    fun resolve(text: String, onvif: String?, login: Credentials?): Resolved {
        val target = Target.parse(text) ?: throw CameraException("\"$text\" is not an address. Type it like 192.168.1.50, or rtsp://192.168.1.50/stream1.")
        val host = target.host
        val bind = networks.bindFor(host)
        val auth = login ?: target.login

        target.url?.let { url ->
            val mjpeg = isMjpeg(url)
            if (!tryUrl(url, bind, auth)) {
                throw CameraException(if (mjpeg) "There is no MJPEG video at ${safe(url)}." else "There is no H.264 or H.265 video at ${safe(url)}.")
            }
            return Resolved(host, StreamOnlyCamera(url, if (mjpeg) "MJPEG" else "RTSP"), streamIdentity("", url), url, null)
        }

        if ((target.port == null || target.port == 80) && knock(bind, host, 80, 1500)) {
            detectFamily(CameraHttp(host, bind))?.let { (proto, identity) ->
                log("scan: $host is a ${proto.family} camera")
                return Resolved(host, proto, identity, "", null)
            }
        }

        // ONVIF: the address the search heard from, or the device service at the usual ports.
        var onvifLogin: LoginRequired? = null
        val services = if (onvif != null) listOf(onvif) else {
            ONVIF_PORTS.filter { p -> (target.port == null || target.port == p) && knock(bind, host, p, 1000) }
                .map { p -> "http://$host:$p/onvif/device_service" }
        }
        for (service in services) {
            val u = runCatching { URI(service) }.getOrNull() ?: continue
            val http = CameraHttp(host, bind, if (u.port > 0) u.port else 80, auth)
            val path = u.rawPath.orEmpty().ifEmpty { "/onvif/device_service" }
            try {
                val url = Onvif.streamUri(http, path, auth)
                val info = runCatching { Onvif.info(http, path, auth) }.getOrNull()
                val name = info?.let { listOf(it.maker, it.model).filter(String::isNotBlank).joinToString(" ") }.orEmpty()
                log("scan: $host ONVIF ${name.ifEmpty { "camera" }}, stream ${safe(url)}")
                if (tryStream(url, bind, auth)) return Resolved(host, StreamOnlyCamera(url, "ONVIF"), streamIdentity(name, url), url, service)
            } catch (e: LoginRequired) {
                // Kept for later: some cameras lock ONVIF and leave their stream open.
                onvifLogin = e
            } catch (e: IOException) {
                log("scan: $host ONVIF at $service: ${e.message}")
            }
        }

        // RTSP at the addresses cameras commonly use.
        val rtspPorts = if (target.port != null && target.port !in ONVIF_PORTS) listOf(target.port) else RTSP_PORTS
        val guesses = ArrayList<String>()
        for (port in rtspPorts) {
            if (!knock(bind, host, port, 1500)) continue
            rtspServer(bind, host, port)?.let { server ->
                lockedStream(server)?.let {
                    log("scan: $host:$port RTSP server \"$server\"")
                    throw CameraException(it)
                }
            }
            for (path in RTSP_PATHS) guesses.add("rtsp://$host${if (port == 554) "" else ":$port"}$path")
        }
        // Then MJPEG over HTTP, at the web ports that answer: after RTSP, whose H.264 looks better.
        val webPorts = ArrayList<Int>()
        for (port in if (target.port != null) listOf(target.port) else MJPEG_PORTS) if (knock(bind, host, port, 1500)) webPorts.add(port)
        guesses.addAll(mjpegGuesses(host, webPorts))
        firstPlaying(guesses, PATH_GAP_MS) { tryUrl(it, bind, auth) }?.let { url ->
            log("scan: $host streams at ${safe(url)}")
            return Resolved(host, StreamOnlyCamera(url, if (isMjpeg(url)) "MJPEG" else "RTSP"), streamIdentity("", url), url, null)
        }
        onvifLogin?.let { throw it }
        throw CameraException(
            "Nothing at $host shows a picture this module can play. If the camera's manual gives a stream " +
                "address (rtsp://… or http://…), type that instead."
        )
    }

    /**
     * Back to a camera found before, quickly: the family it was, or the stream it had - asked of
     * ONVIF again when the old address no longer plays, since some cameras put a session in it.
     */
    fun reopen(host: String, familyName: String, url: String, onvif: String?, name: String, login: Credentials?): Resolved {
        val bind = networks.bindFor(host)
        if (familyName.isNotEmpty()) {
            val proto = CameraProtocol.all.firstOrNull { it.family == familyName }
                ?: throw CameraException("This module no longer speaks to $familyName cameras.")
            val identity = detectFamily(CameraHttp(host, bind), listOf(proto))?.second
                ?: throw CameraException("The camera did not answer at $host. If it has a new address, find it again under Change.")
            return Resolved(host, proto, identity, "", null)
        }
        val family = if (onvif != null) "ONVIF" else if (isMjpeg(url)) "MJPEG" else "RTSP"
        if (url.isNotEmpty() && tryUrl(url, bind, login)) return Resolved(host, StreamOnlyCamera(url, family), streamIdentity(name, url), url, onvif)
        if (onvif != null) {
            val u = runCatching { URI(onvif) }.getOrNull()
            if (u != null) {
                val http = CameraHttp(host, bind, if (u.port > 0) u.port else 80, login)
                val fresh = runCatching { Onvif.streamUri(http, u.rawPath.orEmpty(), login) }
                    .onFailure { if (it is LoginRequired) throw it }.getOrNull()
                if (fresh != null && tryStream(fresh, bind, login)) {
                    return Resolved(host, StreamOnlyCamera(fresh, family), streamIdentity(name, fresh), fresh, onvif)
                }
            }
        }
        throw CameraException("The camera did not answer at $host. Is it on? If it has a new address, find it again under Change.")
    }

    private fun detectFamily(http: CameraHttp, families: List<CameraProtocol> = CameraProtocol.all): Pair<CameraProtocol, CameraIdentity>? {
        for (proto in families) {
            val identity = try { proto.detect(http) } catch (e: Exception) { null }
            if (identity != null) return proto to identity
        }
        return null
    }

    /**
     * True when [url] describes a video track; a login asked for and missing or wrong is thrown.
     * Every miss is logged with its reason: in a rider's report it is the only trace of a camera
     * that answers on its video port and still shows nothing.
     */
    private fun tryStream(url: String, bind: (Socket) -> Unit, login: Credentials?): Boolean = try {
        // The client logs the SDP of a stream without H.264 or H.265 (an MJPEG camera, say).
        RtspClient(url, bind, { log(safe(it)) }, login).use { it.describe() != null }
    } catch (e: LoginRequired) {
        log("scan: ${safe(url)}: ${e.message}")
        throw e
    } catch (e: Exception) {
        // Refused, closed, timed out, or an address that is not one.
        log("scan: ${safe(url)}: ${e.message ?: e.javaClass.simpleName}")
        false
    }

    /** [tryStream] or [tryMjpeg], by the address's scheme. */
    private fun tryUrl(url: String, bind: (Socket) -> Unit, login: Credentials?): Boolean =
        if (isMjpeg(url)) tryMjpeg(url, bind, login) else tryStream(url, bind, login)

    /**
     * True when [url] serves MJPEG and a whole picture arrives from it; a login asked for and
     * missing or wrong is thrown. [quiet] keeps the misses out of the log: the search tries a
     * dozen addresses on every web server around the phone.
     */
    private fun tryMjpeg(url: String, bind: (Socket) -> Unit, login: Credentials?, quiet: Boolean = false): Boolean = try {
        val u = URI(url)
        val path = u.rawPath.orEmpty().ifEmpty { "/" } + (u.rawQuery?.let { "?$it" } ?: "")
        CameraHttp(u.host, bind, if (u.port > 0) u.port else 80, login).open(path, emptyMap(), 3000).use { s ->
            if (s.code == 401) throw LoginRequired(rejected = login != null)
            val type = s.headers["content-type"].orEmpty()
            val plays = s.code in 200..299 && type.lowercase(Locale.ROOT).startsWith("multipart/") &&
                MjpegReader(s.body).next().isNotEmpty()
            if (!plays && !quiet) log("scan: ${safe(url)}: HTTP ${s.code}, ${type.ifEmpty { "no content type" }}")
            plays
        }
    } catch (e: LoginRequired) {
        if (quiet) false else {
            log("scan: ${safe(url)}: ${e.message}")
            throw e
        }
    } catch (e: Exception) {
        if (!quiet) log("scan: ${safe(url)}: ${e.message ?: e.javaClass.simpleName}")
        false
    }

    private fun streamIdentity(name: String, url: String) =
        CameraIdentity(model = name, maker = "", firmware = "", details = listOf("Stream" to safe(url)))

    companion object {
        private const val THREADS = 48
        private const val KNOCK_MS = 350
        private const val ONVIF_WAIT_MS = 3000

        /** RTSP's own port and its usual alternative. */
        val RTSP_PORTS = listOf(554, 8554)

        /** Where IP cameras keep their ONVIF service, when not on 80. */
        val ONVIF_PORTS = listOf(80, 8080, 8000, 8899)

        /** Knocked on at every address: video, and the web ports the families, ONVIF and MJPEG answer on. */
        private val SWEEP_PORTS = listOf(554, 8554, 80, 8080, 81)

        /** Where MJPEG streams are served: 81 first, the ESP32-CAM's. */
        val MJPEG_PORTS = listOf(81, 8080, 80)

        /**
         * MJPEG addresses common across makers: ESP32-CAM, phone webcam apps, mjpg-streamer, Axis,
         * and the CGI names cheap IP cameras share.
         */
        val MJPEG_PATHS = listOf(
            "/stream", "/video", "/?action=stream", "/mjpg/video.mjpg", "/videostream.cgi", "/video.mjpg", "/mjpeg", "/mjpeg.cgi"
        )

        fun isMjpeg(url: String) = url.startsWith("http://", ignoreCase = true)

        /** Every MJPEG address to try at [host] on the web [ports], in order. */
        internal fun mjpegGuesses(host: String, ports: List<Int>): List<String> {
            val out = ArrayList<String>()
            for (port in ports) for (path in MJPEG_PATHS) out.add("http://$host${if (port == 80) "" else ":$port"}$path")
            return out
        }

        /**
         * Stream addresses common across makers, the generic ones first. Tried only when the camera
         * neither is a family spoken here nor says where its stream is over ONVIF.
         */
        val RTSP_PATHS = listOf(
            "/", "/live", "/stream1", "/h264", "/live.sdp", "/stream", "/ch0", "/11", "/1", "/0",
            "/live/ch00_0", "/av0_0", "/onvif1", "/Streaming/Channels/101", "/cam/realmonitor?channel=1&subtype=0",
            "/videoMain", "/live/main", "/media/video1", "/h264_stream", "/unicast",
            // Reolink (old and new firmware), older Hikvision and Ezviz, Uniview, Eufy.
            "/h264Preview_01_main", "/Preview_01_main", "/h264/ch1/main/av_stream", "/unicast/c1/s0/live", "/live0",
            "/livestream/1", "/xxx.mov", "/liveRTSP/av4"
        )

        /**
         * Between two stream addresses tried on one camera. Thirty connections in a second and a half
         * had a camera answer some of them with a reset: a small one can hold few at a time.
         */
        private const val PATH_GAP_MS = 80L

        /** At most this many addresses that answered but are not cameras go into the search's log line. */
        private const val MAX_OTHERS = 20

        /**
         * The first of [urls] that [plays], [gapMs] apart. A login turned down at one of them does
         * not end the search: some cameras answer 401 at an address they do not have, right login
         * or not. Only when none plays is the refusal thrown, since then the login may well be
         * what is wrong.
         */
        internal fun firstPlaying(urls: List<String>, gapMs: Long = 0, plays: (String) -> Boolean): String? {
            var refused: LoginRequired? = null
            var tried = 0
            for (url in urls) {
                if (tried++ > 0 && gapMs > 0) Thread.sleep(gapMs)
                try {
                    if (plays(url)) return url
                } catch (e: LoginRequired) {
                    if (!e.rejected) throw e // no login given yet: ask for one at once
                    refused = e
                }
            }
            refused?.let { throw it }
            return null
        }

        private val SECRET = Pattern.compile("((?:pass(?:word)?|pwd|passwd)=)[^&_]*", Pattern.CASE_INSENSITIVE)

        /** [url] fit for the log and the screen: a password some cameras put in the query is masked. */
        fun safe(url: String): String = SECRET.matcher(url).replaceAll("$1***")

        /**
         * Why the camera behind RTSP server [server] cannot be shown, for the servers whose stream
         * address is made by the maker's own app and no other; null for every other server.
         */
        fun lockedStream(server: String): String? = when {
            // HidviCam builds the address natively from the camera's id and the account's keys (seen in its APK, v2.5.32).
            server.contains("Hopeway", ignoreCase = true) ->
                "This is a HidviCam (Hopeway) camera. Its video address is made by the HidviCam app from keys of " +
                    "its own, so no other app can open it: this module cannot show it."
            else -> null
        }

        /**
         * What the RTSP server at [host]:[port] calls itself, from one OPTIONS: a server that turns
         * the request down usually still names itself. Null when it says nothing or does not answer.
         */
        fun rtspServer(bind: (Socket) -> Unit, host: String, port: Int): String? {
            var server: String? = null
            try {
                Socket().use { s ->
                    bind(s)
                    s.connect(InetSocketAddress(host, port), 1500)
                    s.soTimeout = 2000
                    s.getOutputStream().apply {
                        write("OPTIONS rtsp://$host:$port/ RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: MOTO-HUB-Dashcam\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                        flush()
                    }
                    val input = s.getInputStream().buffered()
                    if (!CameraHttp.readLine(input).startsWith("RTSP/")) return null
                    while (true) {
                        val h = CameraHttp.readLine(input)
                        if (h.isEmpty()) break
                        if (h.startsWith("server:", ignoreCase = true)) server = h.substring(7).trim()
                    }
                }
            } catch (_: IOException) {
                // Closed or cut off: whatever name came before it is still the server's.
            }
            return server
        }

        fun knock(bind: (Socket) -> Unit, host: String, port: Int, timeoutMs: Int): Boolean = try {
            Socket().use { s ->
                bind(s)
                s.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: IOException) {
            false
        }
    }
}
