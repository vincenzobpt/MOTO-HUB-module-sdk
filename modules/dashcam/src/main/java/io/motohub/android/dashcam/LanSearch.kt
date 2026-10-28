// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.dashcam

import io.motohub.android.dashcam.net.CameraHttp
import io.motohub.android.dashcam.net.Credentials
import io.motohub.android.dashcam.net.LocalNet
import io.motohub.android.dashcam.net.LocalNetworks
import io.motohub.android.dashcam.net.LoginRequired
import io.motohub.android.dashcam.net.Onvif
import io.motohub.android.dashcam.protocol.CameraException
import io.motohub.android.dashcam.protocol.CameraIdentity
import io.motohub.android.dashcam.protocol.CameraProtocol
import io.motohub.android.dashcam.protocol.StreamOnlyCamera
import io.motohub.android.dashcam.video.RtspClient
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.Collections
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
    val onvif: String?
)

/** A camera ready to connect to: a dashcam family the module speaks, or a stream and nothing else. */
class Resolved(
    val host: String,
    val protocol: CameraProtocol,
    val identity: CameraIdentity,
    /** The RTSP address of a stream-only camera, without any login in it; empty for a family. */
    val url: String,
    val onvif: String?
)

/** What the rider typed: an address, perhaps with a port, or a whole `rtsp://` stream address. */
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
                if (t.startsWith("http", ignoreCase = true)) return Target(host, port, null, login)
                val clean = buildString {
                    append("rtsp://").append(host)
                    if (port != null) append(':').append(port)
                    append(u.rawPath.orEmpty().ifEmpty { "/" })
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
 * The search knocks on the usual video and web ports of every address around the phone and asks
 * the network for ONVIF cameras at the same time. What it finds is then asked, in order, whether
 * it is a dashcam family this module speaks (every button then works), an ONVIF camera (which
 * tells where its stream is), or at least an RTSP server with video at one of the addresses
 * cameras commonly use.
 *
 * Everything blocks: call it off the main thread.
 */
class LanSearch(private val networks: LocalNetworks, private val log: (String) -> Unit) {

    /** Every address around the phone on [nets]; [progress] runs 0..1 as the knocks come back. */
    fun search(nets: List<LocalNet>, progress: (Float) -> Unit): List<Candidate> {
        val pool = Executors.newFixedThreadPool(THREADS)
        try {
            val onvif = pool.submit(Callable { Onvif.discover(nets, ONVIF_WAIT_MS, log) })
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

            // Who answers on port 80 may be a dashcam of a family spoken here; asked in parallel.
            val families = HashMap<String, Future<Pair<CameraProtocol, CameraIdentity>?>>()
            for ((host, open) in ports) {
                if (80 !in open) continue
                val bind = where[host]?.bind ?: networks.bindFor(host)
                families[host] = pool.submit(Callable { detectFamily(CameraHttp(host, bind)) })
            }

            // Families first (every button works), then ONVIF, then bare RTSP; by address within each.
            val ranked = listOf(ArrayList<Candidate>(), ArrayList<Candidate>(), ArrayList<Candidate>())
            for ((host, open) in ports) {
                val family = families[host]?.let { runCatching { it.get() }.getOrNull() }
                val xaddr = onvifByHost[host]
                val rtsp = open.any { it in RTSP_PORTS }
                if (family == null && xaddr == null && !rtsp) continue
                val identityName = family?.second?.let { listOf(it.maker, it.model).filter(String::isNotBlank).joinToString(" ") }.orEmpty()
                val name = identityName.ifEmpty { onvifNames[host].orEmpty() }.ifEmpty { "Camera" }
                val services = listOfNotNull(family?.first?.family, if (xaddr != null) "ONVIF" else null, if (rtsp) "RTSP" else null).joinToString(" · ")
                ranked[if (family != null) 0 else if (xaddr != null) 1 else 2].add(Candidate(host, name, services, xaddr))
            }
            val byAddress = Comparator<Candidate> { a, b -> Integer.compareUnsigned(address(a.host), address(b.host)) }
            val out = ArrayList<Candidate>()
            for (group in ranked) {
                Collections.sort(group, byAddress)
                out.addAll(group)
            }
            log("scan: ${nets.joinToString { it.label }}: ${hits.size} open ports, ${devices.size} ONVIF, ${out.size} camera(s)" +
                out.joinToString("") { "; ${it.host} ${it.services}" })
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
            if (!tryStream(url, bind, auth)) throw CameraException("There is no H.264 or H.265 video at ${safe(url)}.")
            return Resolved(host, StreamOnlyCamera(url, "RTSP"), streamIdentity("", url), url, null)
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
        for (port in rtspPorts) {
            if (!knock(bind, host, port, 1500)) continue
            for (path in RTSP_PATHS) {
                val url = "rtsp://$host${if (port == 554) "" else ":$port"}$path"
                if (tryStream(url, bind, auth)) {
                    log("scan: $host streams at ${safe(url)}")
                    return Resolved(host, StreamOnlyCamera(url, "RTSP"), streamIdentity("", url), url, null)
                }
            }
        }
        onvifLogin?.let { throw it }
        throw CameraException(
            "Nothing at $host shows a picture this module can play. If the camera's manual gives a stream " +
                "address (rtsp://…), type that instead."
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
        val family = if (onvif != null) "ONVIF" else "RTSP"
        if (url.isNotEmpty() && tryStream(url, bind, login)) return Resolved(host, StreamOnlyCamera(url, family), streamIdentity(name, url), url, onvif)
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

    /** True when [url] describes a video track; a login asked for and missing or wrong is thrown. */
    private fun tryStream(url: String, bind: (Socket) -> Unit, login: Credentials?): Boolean = try {
        RtspClient(url, bind, {}, login).use { it.describe() != null }
    } catch (e: LoginRequired) {
        throw e
    } catch (e: Exception) {
        false // refused, closed, timed out, or an address that is not one
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

        /** Knocked on at every address: video, and the web ports the families and ONVIF answer on. */
        private val SWEEP_PORTS = listOf(554, 8554, 80, 8080)

        /**
         * Stream addresses common across makers, the generic ones first. Tried only when the camera
         * neither is a family spoken here nor says where its stream is over ONVIF.
         */
        val RTSP_PATHS = listOf(
            "/", "/live", "/stream1", "/h264", "/live.sdp", "/stream", "/ch0", "/11", "/1", "/0",
            "/live/ch00_0", "/av0_0", "/onvif1", "/Streaming/Channels/101", "/cam/realmonitor?channel=1&subtype=0",
            "/videoMain", "/live/main", "/media/video1", "/h264_stream", "/unicast",
            "/livestream/1", "/xxx.mov", "/liveRTSP/av4"
        )

        private val SECRET = Pattern.compile("((?:pass(?:word)?|pwd|passwd)=)[^&_]*", Pattern.CASE_INSENSITIVE)

        /** [url] fit for the log and the screen: a password some cameras put in the query is masked. */
        fun safe(url: String): String = SECRET.matcher(url).replaceAll("$1***")

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
