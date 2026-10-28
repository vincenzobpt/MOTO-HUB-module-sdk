// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.dashcam.net

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale

/**
 * Plain HTTP to the camera, over sockets the module opens itself.
 *
 * Two reasons it is not HttpURLConnection. The app forbids cleartext HTTP to anything but
 * loopback (its network security config has no way to allow 192.168.x.x without allowing it
 * everywhere), and a camera speaks nothing else. And every request must leave through the
 * camera's network, not the phone's default one: [connect] decides which, by binding the socket
 * before it connects.
 *
 * HTTP/1.1 with `Connection: close` - one request per socket - because these cameras run tiny
 * embedded servers that handle keep-alive badly or not at all, and the phone is the only client.
 */
class CameraHttp(
    val host: String,
    private val connect: (Socket) -> Unit,
    private val port: Int = 80,
    /** The camera's login, answered only when it says 401; the Wi-Fi dashcams never ask. */
    private val credentials: Credentials? = null
) {
    class Response(val code: Int, val headers: Map<String, String>, val body: ByteArray) {
        val text: String get() = String(body, Charsets.UTF_8)
        val ok: Boolean get() = code in 200..299
    }

    /** A response whose body is still on the wire: for downloads and the video proxy. */
    class Stream(
        val code: Int,
        val headers: Map<String, String>,
        val body: InputStream,
        private val socket: Socket
    ) : Closeable {
        val contentLength: Long get() = headers["content-length"]?.toLongOrNull() ?: -1L
        override fun close() {
            try { socket.close() } catch (_: IOException) { }
        }
    }

    /** Reads the whole answer. Bodies here are small - JSON, XML, `var x="y";` - except downloads. */
    fun get(path: String, timeoutMs: Int = 6000): Response = read(open(path, emptyMap(), timeoutMs))

    /** A POST with [body]: ONVIF's SOAP calls, which IP cameras answer on their HTTP port. */
    fun post(path: String, contentType: String, body: ByteArray, timeoutMs: Int = 6000): Response =
        read(open(path, mapOf("Content-Type" to contentType), timeoutMs, "POST", body))

    private fun read(stream: Stream): Response {
        stream.use { s ->
            val body = when {
                s.headers["transfer-encoding"]?.lowercase(Locale.ROOT)?.contains("chunked") == true -> readChunked(s.body)
                s.contentLength >= 0 -> readExactly(s.body, s.contentLength.toInt())
                else -> s.body.readBytes()
            }
            return Response(s.code, s.headers, body)
        }
    }

    /** The answer as text, or the reason there is none in the exception. */
    fun text(path: String, timeoutMs: Int = 6000): String = get(path, timeoutMs).text

    /**
     * Opens [path] and hands back the body unread. [extraHeaders] go out as they are (the video
     * proxy forwards the player's Range). The caller closes the stream.
     */
    fun open(path: String, extraHeaders: Map<String, String>, timeoutMs: Int = 8000, method: String = "GET", body: ByteArray? = null): Stream {
        val first = exchange(path, extraHeaders, timeoutMs, method, body, null)
        val challenge = first.headers["www-authenticate"]
        // No login, or none asked for: the answer as it is, a 401 included - as before logins existed.
        if (first.code != 401 || credentials == null || challenge == null) return first
        first.close()
        val target = if (path.startsWith("/")) path else "/$path"
        return exchange(path, extraHeaders, timeoutMs, method, body, Authenticator(credentials, challenge).header(method, target))
    }

    private fun exchange(
        path: String,
        extraHeaders: Map<String, String>,
        timeoutMs: Int,
        method: String,
        body: ByteArray?,
        authorization: String?
    ): Stream {
        val socket = Socket()
        try {
            connect(socket)
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            val request = buildString {
                append(method).append(' ').append(if (path.startsWith("/")) path else "/$path").append(" HTTP/1.1\r\n")
                append("Host: ").append(host).append("\r\n")
                append("User-Agent: MOTO-HUB-Dashcam\r\n")
                append("Connection: close\r\n")
                extraHeaders.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
                if (authorization != null) append("Authorization: ").append(authorization).append("\r\n")
                if (body != null) append("Content-Length: ").append(body.size).append("\r\n")
                append("\r\n")
            }
            socket.getOutputStream().apply {
                write(request.toByteArray(Charsets.ISO_8859_1))
                if (body != null) write(body)
                flush()
            }
            val input = socket.getInputStream().buffered(32 * 1024)
            val status = readLine(input)
            val code = status.split(' ').getOrNull(1)?.toIntOrNull()
                ?: throw IOException("Not an HTTP answer: \"${status.take(60)}\"")
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input)
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val key = line.substring(0, colon).trim().lowercase(Locale.ROOT)
                    val value = line.substring(colon + 1).trim()
                    if (key != "www-authenticate" || Authenticator.keepChallenge(headers[key], value)) headers[key] = value
                }
            }
            return Stream(code, headers, input, socket)
        } catch (e: Exception) {
            try { socket.close() } catch (_: IOException) { }
            throw e
        }
    }

    /** Whether something answers on [tcpPort] at all - the camera's RTSP server opens late. */
    fun reachable(tcpPort: Int, timeoutMs: Int = 1500): Boolean = try {
        Socket().use { s ->
            connect(s)
            s.connect(InetSocketAddress(host, tcpPort), timeoutMs)
            true
        }
    } catch (_: IOException) {
        false
    }

    companion object {
        fun readLine(input: InputStream): String {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) {
                    if (sb.isEmpty()) throw EOFException("connection closed")
                    return sb.toString()
                }
                if (b == '\n'.code) return sb.toString()
                if (b != '\r'.code) sb.append(b.toChar())
            }
        }

        fun readExactly(input: InputStream, length: Int): ByteArray {
            val out = ByteArray(length)
            var off = 0
            while (off < length) {
                val r = input.read(out, off, length - off)
                if (r < 0) throw EOFException("body ended after $off of $length bytes")
                off += r
            }
            return out
        }

        fun readChunked(input: InputStream): ByteArray {
            val out = ByteArrayOutputStream()
            while (true) {
                val size = readLine(input).substringBefore(';').trim().toIntOrNull(16)
                    ?: throw IOException("bad chunk size")
                if (size == 0) {
                    // Trailers, if any, end with an empty line.
                    while (readLine(input).isNotEmpty()) { }
                    return out.toByteArray()
                }
                out.write(readExactly(input, size))
                readLine(input)
            }
        }
    }
}
