// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.dashcam.probe

import io.motohub.android.dashcam.LanSearch
import io.motohub.android.dashcam.net.CameraHttp
import io.motohub.android.dashcam.net.LoginRequired
import io.motohub.android.dashcam.net.Onvif
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * What a camera this module could not show answers, written to the log so a rider's report says
 * how it speaks - and often which family it is - without anyone having the camera in hand.
 *
 * It only ever asks. Every request is a read: a page, a version, a status, a stream's description.
 * Nothing that records, sets, formats or deletes is sent, because on a camera nobody here has seen
 * a command that only reads elsewhere may well do something else.
 *
 * Compact on purpose: the app's log keeps its last 1500 events, so this stays around 40 lines and
 * never more than [MAX_LINES], each cut at [MAX_CHARS]. Every line goes through [Redact] first.
 */
class CameraProbe(private val log: (String) -> Unit) {
    private var lines = 0

    /**
     * Probes [host], reached through [bind]. [why] heads the block; [stream] is the address the
     * live view tried, when it was the picture that failed; [context] adds what the module knows
     * already (the network, the family it took the camera for).
     */
    fun run(host: String, bind: (Socket) -> Unit, why: String, stream: String? = null, context: String = "") {
        lines = 0
        line("start: $why; camera at $host${if (context.isEmpty()) "" else "; $context"}")
        val open = openPorts(host, bind)
        line("open ports: ${if (open.isEmpty()) "none of ${PORTS.joinToString(",")}" else open.joinToString(",")}")
        if (open.isEmpty()) return

        for (port in WEB_PORTS) if (port in open) page(host, bind, port, "/")
        if (80 in open) for (path in FAMILY_PATHS) page(host, bind, 80, path)
        if (8080 in open) page(host, bind, 8080, "/gopro/camera/state")
        if (7878 in open) ambarella(host, bind)

        val rtspPorts = RTSP_PORTS.filter { it in open }
        if (stream != null && stream.startsWith("rtsp", ignoreCase = true)) rtsp(bind, stream)
        else for (port in rtspPorts) rtsp(bind, "rtsp://$host${if (port == 554) "" else ":$port"}/")

        for (port in ONVIF_PORTS) if (port in open) onvif(host, bind, port)
        line("end: $lines lines")
    }

    private fun line(text: String) {
        if (lines >= MAX_LINES) return
        lines++
        val flat = WHITESPACE.matcher(text).replaceAll(" ").trim()
        log("probe: " + Redact.text(flat).take(MAX_CHARS))
    }

    /** The probe's ports that accept a connection, knocked on in parallel. */
    private fun openPorts(host: String, bind: (Socket) -> Unit): List<Int> {
        val open = Collections.synchronizedList(ArrayList<Int>())
        val pool = Executors.newFixedThreadPool(8)
        try {
            for (port in PORTS) pool.execute { if (LanSearch.knock(bind, host, port, 800)) open.add(port) }
            pool.shutdown()
            pool.awaitTermination(10, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
        val sorted = ArrayList(open)
        Collections.sort(sorted)
        return sorted
    }

    /** One GET: its status, type and server, and the start of what it sends - never more than a few KB. */
    private fun page(host: String, bind: (Socket) -> Unit, port: Int, path: String) {
        val what = "GET :$port$path"
        try {
            CameraHttp(host, bind, port).open(path, emptyMap(), 2500).use { s ->
                val type = s.headers["content-type"].orEmpty()
                val server = s.headers["server"]?.let { ", server \"$it\"" }.orEmpty()
                val auth = s.headers["www-authenticate"]?.let { ", asks for ${it.substringBefore(' ')}" }.orEmpty()
                val body = readSome(s.body, BODY_BYTES)
                line("$what -> ${s.code} ${type.ifEmpty { "(no type)" }}$server$auth: ${describe(body, type)}")
            }
        } catch (e: Exception) {
            line("$what -> ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * Ambarella's JSON on TCP 7878, spoken by many action cameras and dashcams: a session opened,
     * the device's own description asked for, the session closed. No setting is touched.
     */
    private fun ambarella(host: String, bind: (Socket) -> Unit) {
        try {
            Socket().use { s ->
                bind(s)
                s.connect(InetSocketAddress(host, 7878), 2000)
                s.soTimeout = 2500
                val input = s.getInputStream()
                val output = s.getOutputStream()
                fun ask(json: String): String {
                    output.write(json.toByteArray(Charsets.UTF_8))
                    output.flush()
                    return readJson(input)
                }
                val opened = ask("{\"msg_id\":257,\"token\":0}")
                line("ambarella :7878 start session -> $opened")
                val token = TOKEN.matcher(opened).let { if (it.find()) it.group(1).orEmpty() else "0" }
                line("ambarella :7878 device info -> ${ask("{\"msg_id\":11,\"token\":$token}")}")
                runCatching { ask("{\"msg_id\":258,\"token\":$token}") }
            }
        } catch (e: Exception) {
            line("ambarella :7878 -> ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * What the RTSP server at [url] answers, asked several ways: OPTIONS as clients send it, for
     * the whole server (`*`) and with the port written out, then DESCRIBE with and without the
     * usual headers. Each on a connection of its own - a server that turns one request down often
     * hangs up, and the next would never be asked. A server that answered `400` to everything but
     * one form is told apart this way.
     */
    internal fun rtsp(bind: (Socket) -> Unit, url: String) {
        val uri = runCatching { java.net.URI(url) }.getOrNull()
        val host = uri?.host
        if (host == null) {
            line("rtsp ${LanSearch.safe(url)} -> not an address")
            return
        }
        val port = if (uri.port > 0) uri.port else 554
        val withPort = "rtsp://$host:$port${uri.rawPath.orEmpty().ifEmpty { "/" }}${uri.rawQuery?.let { "?$it" }.orEmpty()}"
        ask(bind, host, port, "OPTIONS", url, AGENT)
        ask(bind, host, port, "OPTIONS", "*", AGENT)
        if (withPort != url) ask(bind, host, port, "OPTIONS", withPort, AGENT)
        ask(bind, host, port, "DESCRIBE", url, AGENT + "Accept: application/sdp\r\n")
        ask(bind, host, port, "DESCRIBE", url, "")
    }

    /** One RTSP request on a fresh connection, and one line of what came back - or of how it did not. */
    private fun ask(bind: (Socket) -> Unit, host: String, port: Int, method: String, target: String, headers: String) {
        val what = "rtsp ${LanSearch.safe(target)} $method${if (headers.isEmpty()) " (bare)" else ""}"
        try {
            Socket().use { s ->
                bind(s)
                s.connect(InetSocketAddress(host, port), 2500)
                s.soTimeout = 3000
                s.getOutputStream().apply {
                    write("$method $target RTSP/1.0\r\nCSeq: 1\r\n$headers\r\n".toByteArray(Charsets.ISO_8859_1))
                    flush()
                }
                val input = s.getInputStream().buffered()
                val status = try {
                    CameraHttp.readLine(input)
                } catch (_: java.io.EOFException) {
                    line("$what -> closed with no answer")
                    return
                }
                if (!status.startsWith("RTSP/")) {
                    line("$what -> not RTSP: \"${printable(status)}\"")
                    return
                }
                val answer = HashMap<String, String>()
                while (true) {
                    val h = CameraHttp.readLine(input)
                    if (h.isEmpty()) break
                    val colon = h.indexOf(':')
                    if (colon > 0) answer[h.substring(0, colon).trim().lowercase(Locale.ROOT)] = h.substring(colon + 1).trim()
                }
                val length = answer["content-length"]?.toIntOrNull() ?: 0
                val body = if (length in 1..BODY_BYTES) String(CameraHttp.readExactly(input, length), Charsets.UTF_8) else ""
                val server = answer["server"]?.let { ", server \"$it\"" }.orEmpty()
                val does = answer["public"]?.let { ", does $it" }.orEmpty()
                val auth = answer["www-authenticate"]?.let { ", asks for ${it.substringBefore(' ')}" }.orEmpty()
                line("$what -> $status$server$does$auth${if (body.isEmpty()) "" else ": " + sdpSummary(body)}")
            }
        } catch (e: Exception) {
            line("$what -> ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Who the camera says it is over ONVIF, without a login - many answer this much to anyone. */
    private fun onvif(host: String, bind: (Socket) -> Unit, port: Int) {
        try {
            val info = Onvif.info(CameraHttp(host, bind, port), "/onvif/device_service", null)
            line("onvif :$port -> ${info.maker} ${info.model}, firmware ${info.firmware}")
        } catch (_: LoginRequired) {
            line("onvif :$port -> asks for a login")
        } catch (e: Exception) {
            line("onvif :$port -> ${e.message ?: e.javaClass.simpleName}")
        }
    }

    companion object {
        /**
         * The ports cameras are known to listen on: FTP and telnet (many firmwares leave them
         * open), web, RTSP, RTMP, Jieli's 3333, the event channel some dashcams keep on 5000,
         * Ambarella's 7878, Novatek's MJPEG on 8192, ONVIF's usual 8000 and 8899.
         */
        val PORTS = listOf(21, 23, 80, 81, 443, 554, 1935, 3333, 5000, 7878, 8000, 8080, 8081, 8192, 8554, 8899, 9000)

        private val WEB_PORTS = listOf(80, 81, 8080, 8081, 8192, 9000)
        private val RTSP_PORTS = listOf(554, 8554)
        private val ONVIF_PORTS = listOf(80, 8000, 8080, 8899)

        /**
         * Read-only questions that tell the families apart: eeasytech, Novatek (version and
         * status), HiSilicon, MStar, the older GoPros, BlackVue, and the Hi3510 boards inside many
         * cheap IP cameras.
         */
        val FAMILY_PATHS = listOf(
            "/app/getproductinfo",
            "/?custom=1&cmd=3012",
            "/?custom=1&cmd=3014",
            "/cgi-bin/hisnet/getdeviceattr.cgi?",
            "/cgi-bin/Config.cgi?action=get&property=Net.WIFI_AP.SSID",
            "/gp/gpControl/status",
            "/Config/version.bin",
            "/cgi-bin/hi3510/param.cgi?cmd=getserverinfo"
        )

        private const val MAX_LINES = 60
        private const val MAX_CHARS = 1500
        private const val BODY_BYTES = 4096

        private const val AGENT = "User-Agent: MOTO-HUB-Dashcam\r\n"

        /** The first bytes of an answer that is not RTSP, readable in one line: anything else is a dot. */
        internal fun printable(text: String): String {
            val out = StringBuilder()
            for (c in text) {
                if (out.length >= 80) break
                out.append(if (c.code in 32..126) c else '.')
            }
            return out.toString()
        }

        private val WHITESPACE = Pattern.compile("\\s+")
        private val TITLE = Pattern.compile("<title[^>]*>([^<]*)</title>", Pattern.CASE_INSENSITIVE)
        private val TOKEN = Pattern.compile("\"param\"\\s*:\\s*(\\d+)")

        /** Up to [limit] bytes of [input]: enough to recognise a page, never a whole stream. */
        internal fun readSome(input: InputStream, limit: Int): ByteArray {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            try {
                while (out.size() < limit) {
                    val n = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
                    if (n < 0) break
                    out.write(buffer, 0, n)
                }
            } catch (_: SocketTimeoutException) {
                // A slow or endless answer: what came is enough.
            }
            return out.toByteArray()
        }

        /** The start of a body as one line: its title for a page, its text for anything readable. */
        internal fun describe(body: ByteArray, type: String): String {
            if (body.isEmpty()) return "(empty)"
            val printable = body.count { b -> val c = b.toInt() and 0xff; c == 9 || c == 10 || c == 13 || c in 32..126 || c >= 0xC0 }
            if (printable < body.size * 9 / 10) return "(${body.size} bytes of binary${if (type.isEmpty()) "" else ", $type"})"
            val text = String(body, Charsets.UTF_8)
            val title = TITLE.matcher(text).let { if (it.find()) it.group(1).orEmpty().trim() else "" }
            val flat = WHITESPACE.matcher(text).replaceAll(" ").trim()
            return (if (title.isNotEmpty()) "title \"$title\" | " else "") + flat.take(600)
        }

        /** The SDP lines that say what the stream is, joined: media, codec, control, parameters. */
        internal fun sdpSummary(sdp: String): String {
            val out = ArrayList<String>()
            for (raw in sdp.split('\n')) {
                val l = raw.trim()
                if (l.startsWith("m=") || l.startsWith("a=rtpmap") || l.startsWith("a=control") || l.startsWith("s=") ||
                    l.startsWith("a=framerate") || l.startsWith("a=x-dimensions") || l.startsWith("a=range")
                ) out.add(l)
                else if (l.startsWith("a=fmtp")) out.add(l.take(160))
            }
            return out.joinToString(" | ")
        }

        /** One JSON object off a stream, by its braces; whatever arrived when it stops sending. */
        private fun readJson(input: InputStream): String {
            val out = ByteArrayOutputStream()
            var depth = 0
            var inString = false
            var escaped = false
            try {
                while (out.size() < BODY_BYTES) {
                    val b = input.read()
                    if (b < 0) break
                    out.write(b)
                    val c = b.toChar()
                    when {
                        escaped -> escaped = false
                        inString && c == '\\' -> escaped = true
                        c == '"' -> inString = !inString
                        !inString && c == '{' -> depth++
                        !inString && c == '}' -> if (--depth == 0) break
                    }
                }
            } catch (_: SocketTimeoutException) {
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        }
    }
}
