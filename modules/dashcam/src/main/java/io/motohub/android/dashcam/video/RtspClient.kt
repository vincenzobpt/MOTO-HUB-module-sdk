// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.dashcam.video

import android.util.Base64
import io.motohub.android.dashcam.net.Authenticator
import io.motohub.android.dashcam.net.CameraHttp
import io.motohub.android.dashcam.net.Credentials
import io.motohub.android.dashcam.net.LoginRequired
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.util.Locale

/** What the camera's SDP says about its video track. */
class VideoTrack(
    val codec: VideoCodec,
    val control: String,
    /** The RTP payload type the SDP gives the video (96 on most cameras), or -1. */
    val payloadType: Int,
    val vps: ByteArray?,
    val sps: ByteArray?,
    val pps: ByteArray?
)

/** Reads the video track out of an SDP. Base64 decoding is passed in so the parser runs in plain JVM tests. */
fun parseSdpVideo(sdp: String, baseUrl: String, decode: (String) -> ByteArray): VideoTrack? {
    var inVideo = false
    var payloadType = -1
    var codec: VideoCodec? = null
    var control: String? = null
    var vps: ByteArray? = null
    var sps: ByteArray? = null
    var pps: ByteArray? = null
    for (raw in sdp.lines()) {
        val line = raw.trim()
        when {
            line.startsWith("m=") -> {
                inVideo = line.startsWith("m=video")
                if (inVideo) payloadType = line.split(' ').getOrNull(3)?.toIntOrNull() ?: -1
            }
            !inVideo -> Unit
            line.startsWith("a=rtpmap:") -> {
                val name = line.substringAfter(' ').substringBefore('/').uppercase(Locale.ROOT)
                codec = when (name) {
                    "H264" -> VideoCodec.H264
                    "H265", "HEVC" -> VideoCodec.H265
                    else -> codec
                }
            }
            line.startsWith("a=control:") -> control = line.substringAfter("a=control:").trim()
            line.startsWith("a=fmtp:") -> {
                val params = line.substringAfter(' ').split(';').associate {
                    val kv = it.trim()
                    kv.substringBefore('=').lowercase(Locale.ROOT) to kv.substringAfter('=', "")
                }
                params["sprop-parameter-sets"]?.split(',')?.let { sets ->
                    sets.getOrNull(0)?.takeIf { it.isNotBlank() }?.let { sps = runCatching { decode(it) }.getOrNull() }
                    sets.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { pps = runCatching { decode(it) }.getOrNull() }
                }
                params["sprop-vps"]?.let { vps = runCatching { decode(it) }.getOrNull() }
                params["sprop-sps"]?.let { sps = runCatching { decode(it) }.getOrNull() }
                params["sprop-pps"]?.let { pps = runCatching { decode(it) }.getOrNull() }
            }
        }
    }
    val c = codec ?: return null
    val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
    val trackUrl = when {
        control == null || control == "*" -> baseUrl
        control!!.startsWith("rtsp://", ignoreCase = true) -> control!!
        else -> base + control!!.removePrefix("/")
    }
    return VideoTrack(c, trackUrl, payloadType, vps, sps, pps)
}

/**
 * RTSP over one TCP connection (RTP interleaved, RFC 2326 §10.12), video track only.
 *
 * TCP rather than UDP because the cameras advertise it, and because a phone on a camera's
 * access point behind its own firewall rarely gets UDP back. Audio is never set up: the camera
 * this was built on sends AAC with an invalid header, and nothing here plays sound anyway.
 *
 * [credentials] answer a camera that says 401 (Basic or Digest, as it asks); without them the 401
 * comes back as the refusal it always was.
 *
 * A camera that refuses RTP over TCP is asked for it over UDP instead, when [bindUdp] is given:
 * only after the TCP SETUP has failed, so a camera that interleaves is never asked anything new.
 */
class RtspClient(
    private val url: String,
    private val bind: (Socket) -> Unit,
    private val log: (String) -> Unit,
    private val credentials: Credentials? = null,
    private val bindUdp: ((DatagramSocket) -> Unit)? = null
) : Closeable {
    private val socket = Socket()
    private lateinit var input: InputStream
    private lateinit var output: OutputStream
    private var cseq = 1
    private var session: String? = null
    private var keepAliveMs = 25_000L
    private var baseUrl = url

    /**
     * The interleaved channel the video arrives on. Asked for as 0, but a camera may answer with
     * its own numbering (the track's index, say) - and then everything on 0 is nothing at all.
     */
    var videoChannel = 0
        private set

    /** What the camera answered to SETUP, for the log. */
    var transport = ""
        private set

    private var videoPayloadType = -1

    /** Set by the first 401 when there is a login to answer it with; signs every request after it. */
    private var authenticator: Authenticator? = null

    /** Where RTP and RTCP arrive when the camera sends them over UDP; null while they are interleaved. */
    private var rtpSocket: DatagramSocket? = null
    private var rtcpSocket: DatagramSocket? = null

    class Response(val code: Int, val headers: Map<String, String>, val body: String)

    /** Connects, describes, sets up the video track and starts it playing. */
    fun start(): VideoTrack {
        val track = describe() ?: throw IOException("The camera's stream has no H.264 or H.265 video.")
        videoPayloadType = track.payloadType
        var setup = request("SETUP", track.control, "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n")
        if (setup.code != 200) {
            setup = setupUdp(track.control, setup.code) ?: throw IOException("The camera refused SETUP (${setup.code}).")
        }
        transport = setup.headers["transport"].orEmpty()
        if (rtpSocket == null) Regex("interleaved=(\\d+)").find(transport)?.groupValues?.get(1)?.toIntOrNull()?.let { videoChannel = it }
        setup.headers["session"]?.split(';')?.let { parts ->
            session = parts[0].trim()
            parts.drop(1).map { it.trim() }.firstOrNull { it.startsWith("timeout=") }
                ?.substringAfter('=')?.toLongOrNull()?.let { keepAliveMs = (it * 1000 / 2).coerceAtLeast(10_000) }
        }
        val play = request("PLAY", baseUrl, "Session: $session\r\nRange: npt=0.000-\r\n")
        if (play.code != 200) throw IOException("The camera refused PLAY (${play.code}).")
        return track
    }

    /**
     * Connects and asks what is at [url]: its video track, or null when the stream has none. Alone,
     * it is how an address is tried without playing it; [start] goes on from here.
     */
    fun describe(): VideoTrack? {
        val uri = URI(url)
        val port = if (uri.port > 0) uri.port else 554
        bind(socket)
        socket.connect(InetSocketAddress(uri.host, port), 5000)
        socket.soTimeout = 8000
        socket.tcpNoDelay = true
        input = BufferedInputStream(socket.getInputStream(), 1 shl 16)
        output = socket.getOutputStream()

        request("OPTIONS", url, "")
        val describe = request("DESCRIBE", url, "Accept: application/sdp\r\n")
        if (describe.code == 401) throw LoginRequired(rejected = credentials != null)
        if (describe.code != 200) throw IOException("The camera refused DESCRIBE (${describe.code}).")
        baseUrl = describe.headers["content-base"] ?: url
        val track = parseSdpVideo(describe.body, baseUrl) { Base64.decode(it, Base64.DEFAULT) }
        if (track == null) {
            log("live: DESCRIBE $url answered ${describe.code}, headers ${describe.headers}, SDP: " +
                describe.body.replace("\r", "").replace("\n", " | ").take(1500))
        }
        return track
    }

    /**
     * SETUP again asking for RTP over UDP, after the camera turned down TCP with [tcpCode]: two
     * sockets bound to the camera's network, an even port and the next for RTCP as RFC 3550 asks.
     * Null when there is no way to bind them or the camera refuses this too.
     */
    private fun setupUdp(control: String, tcpCode: Int): Response? {
        val binder = bindUdp ?: return null
        for (attempt in 0 until 8) {
            val rtp = DatagramSocket(null as java.net.SocketAddress?)
            val rtcp = DatagramSocket(null as java.net.SocketAddress?)
            try {
                binder(rtp)
                binder(rtcp)
                rtp.bind(InetSocketAddress(0))
                // An odd port, or its neighbour taken: another pair. The last tries take any two.
                if (rtp.localPort % 2 != 0 && attempt < 6) throw IOException("odd port")
                rtcp.bind(InetSocketAddress(if (attempt < 6) rtp.localPort + 1 else 0))
            } catch (_: Exception) {
                rtp.close()
                rtcp.close()
                continue
            }
            log("live: SETUP over TCP refused (${tcpCode}); asking for UDP on ports ${rtp.localPort}-${rtcp.localPort}")
            val answer = try {
                request("SETUP", control, "Transport: RTP/AVP;unicast;client_port=${rtp.localPort}-${rtcp.localPort}\r\n")
            } catch (_: IOException) {
                rtp.close()
                rtcp.close()
                throw IOException("The camera refused SETUP over TCP ($tcpCode), then closed the connection asking for UDP.")
            }
            if (answer.code != 200) {
                rtp.close()
                rtcp.close()
                throw IOException("The camera refused SETUP over TCP ($tcpCode) and over UDP (${answer.code}).")
            }
            rtpSocket = rtp
            rtcpSocket = rtcp
            videoChannel = 0
            return answer
        }
        return null
    }

    /**
     * Reads interleaved packets until [isActive] says stop or the camera hangs up, handing each
     * RTP packet of channel 0 (video) to [onRtp]. Keeps the session alive on its own.
     */
    fun pump(isActive: () -> Boolean, onPacket: (channel: Int, length: Int) -> Unit = { _, _ -> }, onRtp: (ByteArray, Int) -> Unit) {
        rtpSocket?.let { return pumpUdp(it, isActive, onPacket, onRtp) }
        val buffer = ByteArray(65_536 + 4)
        var lastKeepAlive = System.currentTimeMillis()
        var videoSeen = false
        while (isActive()) {
            val now = System.currentTimeMillis()
            if (now - lastKeepAlive > keepAliveMs) {
                send("OPTIONS", baseUrl, "Session: $session\r\n")
                lastKeepAlive = now
            }
            val first = input.read()
            if (first < 0) throw EOFException("The camera closed the video connection.")
            when (first) {
                '$'.code -> {
                    val channel = input.read()
                    val length = (input.read() shl 8) or input.read()
                    if (length < 0) throw EOFException("The camera closed the video connection.")
                    readFully(buffer, length)
                    onPacket(channel, length)
                    if (channel == videoChannel) {
                        videoSeen = true
                        onRtp(buffer, length)
                    } else if (!videoSeen && channel % 2 == 0 && isVideoRtp(buffer, length)) {
                        // The LINGTUO S1 answers SETUP with interleaved=0-1 and then sends the
                        // video on channel 2: trust the packets, not the answer.
                        log("live: the camera sends video on channel $channel, not the $videoChannel it announced")
                        videoChannel = channel
                        videoSeen = true
                        onRtp(buffer, length)
                    }
                }
                'R'.code -> readResponse('R') // the answer to a keep-alive
                else -> Unit // stray byte between frames: resynchronise on the next '$'
            }
        }
    }

    /**
     * [pump] for RTP over UDP: datagrams from the camera on [socket], and on the RTSP connection
     * only the keep-alives and their answers - nothing else arrives there now.
     */
    private fun pumpUdp(socket: DatagramSocket, isActive: () -> Boolean, onPacket: (Int, Int) -> Unit, onRtp: (ByteArray, Int) -> Unit) {
        val buffer = ByteArray(65_536)
        val packet = DatagramPacket(buffer, buffer.size)
        val camera = URI(url).host
        socket.soTimeout = 1000
        var lastKeepAlive = System.currentTimeMillis()
        var lastData = lastKeepAlive
        while (isActive()) {
            val now = System.currentTimeMillis()
            if (now - lastKeepAlive > keepAliveMs) {
                exchange("OPTIONS", baseUrl, "Session: $session\r\n")
                lastKeepAlive = now
            }
            try {
                packet.setLength(buffer.size)
                socket.receive(packet)
            } catch (_: SocketTimeoutException) {
                if (System.currentTimeMillis() - lastData > UDP_SILENCE_MS) throw EOFException("The camera stopped sending video over UDP.")
                continue
            }
            // Only the camera's own datagrams: anything else on the port is not this stream.
            if (packet.address?.hostAddress != camera) continue
            lastData = System.currentTimeMillis()
            onPacket(0, packet.length)
            if (isVideoRtp(buffer, packet.length)) onRtp(buffer, packet.length)
        }
    }

    /** An RTP packet carrying the video's payload type (any RTP, when the SDP named none). */
    private fun isVideoRtp(packet: ByteArray, length: Int): Boolean {
        if (length < 12 || ((packet[0].toInt() and 0xff) ushr 6) != 2) return false
        val type = packet[1].toInt() and 0x7f
        return videoPayloadType < 0 || type == videoPayloadType
    }

    private fun send(method: String, target: String, extra: String) {
        val auth = authenticator?.let { "Authorization: ${it.header(method, target)}\r\n" }.orEmpty()
        val text = "$method $target RTSP/1.0\r\nCSeq: ${cseq++}\r\nUser-Agent: MOTO-HUB-Dashcam\r\n$auth$extra\r\n"
        output.write(text.toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    private fun request(method: String, target: String, extra: String): Response {
        val first = exchange(method, target, extra)
        val challenge = first.headers["www-authenticate"]
        if (first.code != 401 || credentials == null || challenge == null) return first
        // A fresh challenge each time: a second 401 may only be the camera renewing its nonce.
        authenticator = Authenticator(credentials, challenge)
        return exchange(method, target, extra)
    }

    private fun exchange(method: String, target: String, extra: String): Response {
        send(method, target, extra)
        while (true) {
            val first = input.read()
            if (first < 0) throw EOFException("The camera closed the connection during $method.")
            if (first == '$'.code) {
                input.read()
                val length = (input.read() shl 8) or input.read()
                readFully(ByteArray(length), length)
            } else if (first == 'R'.code) {
                return readResponse('R')
            }
        }
    }

    private fun readResponse(first: Char): Response {
        val status = first + CameraHttp.readLine(input)
        val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: -1
        val headers = HashMap<String, String>()
        while (true) {
            val line = CameraHttp.readLine(input)
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                val key = line.substring(0, colon).trim().lowercase(Locale.ROOT)
                val value = line.substring(colon + 1).trim()
                if (key != "www-authenticate" || Authenticator.keepChallenge(headers[key], value)) headers[key] = value
            }
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (length > 0) String(CameraHttp.readExactly(input, length), Charsets.UTF_8) else ""
        return Response(code, headers, body)
    }

    private fun readFully(buffer: ByteArray, length: Int) {
        var off = 0
        while (off < length) {
            val r = input.read(buffer, off, length - off)
            if (r < 0) throw EOFException("The camera closed the video connection.")
            off += r
        }
    }

    override fun close() {
        try {
            if (session != null) send("TEARDOWN", baseUrl, "Session: $session\r\n")
        } catch (_: Exception) { }
        try { socket.close() } catch (_: IOException) { }
        rtpSocket?.close()
        rtcpSocket?.close()
    }

    private companion object {
        /** How long a UDP stream may stay silent before it counts as dropped: a datagram lost is not an end. */
        const val UDP_SILENCE_MS = 8000L
    }
}
