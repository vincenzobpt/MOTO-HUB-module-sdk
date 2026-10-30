// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.dashcam.video

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamFormatsTest {

    /** A small JPEG-shaped picture: an EXIF block with its own thumbnail, then a scan with stuffing and a restart. */
    private fun picture(fill: Int): ByteArray {
        val b = ByteArrayOutputStream()
        fun w(vararg v: Int) = v.forEach { b.write(it) }
        w(0xFF, 0xD8)
        // APP1 whose payload holds a whole thumbnail, end marker included.
        val thumb = intArrayOf(0xFF, 0xD8, 0x01, 0x02, 0xFF, 0xD9)
        w(0xFF, 0xE1, 0x00, 2 + thumb.size)
        w(*thumb)
        w(0xFF, 0xDB, 0x00, 0x04, 0x00, fill)
        w(0xFF, 0xDA, 0x00, 0x03, 0x01)
        w(fill, 0xFF, 0x00, fill, 0xFF, 0xD3, fill)
        w(0xFF, 0xD9)
        return b.toByteArray()
    }

    @Test
    fun `mjpeg pictures come out whole, whatever the framing around them`() {
        val first = picture(0x11)
        val second = picture(0x22)
        val wire = ByteArrayOutputStream()
        wire.write("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${first.size}\r\n\r\n".toByteArray())
        wire.write(first)
        // A camera that forgets the length and adds stray bytes before the picture.
        wire.write("\r\n--frame\r\nContent-Type: image/jpeg\r\n\r\nÿ\u0000junk".toByteArray(Charsets.ISO_8859_1))
        wire.write(second)
        wire.write("\r\n--frame\r\n".toByteArray())
        val reader = MjpegReader(ByteArrayInputStream(wire.toByteArray()))
        assertArrayEquals(first, reader.next())
        assertArrayEquals(second, reader.next())
        assertThrows(EOFException::class.java) { reader.next() }
    }

    @Test
    fun `a camera that refuses rtp over tcp is asked for udp, and its datagrams arrive`() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val sdp = "v=0\r\nm=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\na=control:track1\r\n"
        val asked = ArrayList<String>()
        val camera = Thread {
            server.accept().use { s ->
                val input = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val out = s.getOutputStream()
                var clientPort = -1
                while (true) {
                    val request = input.readLine() ?: break
                    val headers = ArrayList<String>()
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        headers.add(line)
                    }
                    val method = request.substringBefore(' ')
                    val transport = headers.firstOrNull { it.startsWith("Transport:") }.orEmpty()
                    asked.add("$method $transport".trim())
                    val answer = when {
                        method == "DESCRIBE" -> "RTSP/1.0 200 OK\r\nContent-Length: ${sdp.length}\r\n\r\n$sdp"
                        method == "SETUP" && transport.contains("TCP") -> "RTSP/1.0 461 Unsupported Transport\r\n\r\n"
                        method == "SETUP" -> {
                            clientPort = Regex("client_port=(\\d+)").find(transport)!!.groupValues[1].toInt()
                            "RTSP/1.0 200 OK\r\nSession: 42;timeout=60\r\nTransport: RTP/AVP;unicast;client_port=$clientPort-${clientPort + 1};server_port=6970-6971\r\n\r\n"
                        }
                        else -> "RTSP/1.0 200 OK\r\n\r\n"
                    }
                    out.write(answer.toByteArray(Charsets.ISO_8859_1))
                    out.flush()
                    if (method == "PLAY") {
                        // One RTP packet of payload type 96, to the port the client asked for.
                        val rtp = byteArrayOf(0x80.toByte(), 96, 0, 1, 0, 0, 0, 0, 0, 0, 0, 1, 0x65, 0x01)
                        DatagramSocket().use { it.send(DatagramPacket(rtp, rtp.size, InetAddress.getLoopbackAddress(), clientPort)) }
                    }
                    if (method == "TEARDOWN") break
                }
            }
        }.apply { start() }

        var received = 0
        RtspClient("rtsp://127.0.0.1:${server.localPort}/", {}, {}, null, {}).use { rtsp ->
            rtsp.start()
            rtsp.pump({ received == 0 }) { _, length -> received = length }
        }
        camera.join(5000)
        server.close()
        assertEquals(14, received)
        assertTrue(asked.any { it.startsWith("SETUP") && it.contains("TCP") })
        assertTrue(asked.any { it.startsWith("SETUP") && it.contains("client_port=") && !it.contains("TCP") })
    }
}
