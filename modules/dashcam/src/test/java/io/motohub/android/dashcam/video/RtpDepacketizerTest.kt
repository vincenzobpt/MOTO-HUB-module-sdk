// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.dashcam.video

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RtpDepacketizerTest {
    private var seq = 0

    private fun rtp(payload: ByteArray, timestamp: Long, marker: Boolean): ByteArray {
        val header = byteArrayOf(
            0x80.toByte(), ((if (marker) 0x80 else 0) or 96).toByte(),
            (seq shr 8).toByte(), seq.toByte(),
            (timestamp shr 24).toByte(), (timestamp shr 16).toByte(), (timestamp shr 8).toByte(), timestamp.toByte(),
            0, 0, 0, 1
        )
        seq++
        return header + payload
    }

    private val start = byteArrayOf(0, 0, 0, 1)

    @Test
    fun `h264 single NALs and STAP-A make one access unit with the parameter sets remembered`() {
        val units = ArrayList<AccessUnit>()
        val d = RtpDepacketizer(VideoCodec.H264) { units += it }
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1f)
        val pps = byteArrayOf(0x68, 0xce.toByte(), 0x3c)
        val stap = byteArrayOf(24) + byteArrayOf(0, sps.size.toByte()) + sps + byteArrayOf(0, pps.size.toByte()) + pps
        val idr = byteArrayOf(0x65, 1, 2, 3)
        d.onRtp(rtp(stap, 1000, false).let { it }, 12 + stap.size)
        val p = rtp(idr, 1000, true)
        d.onRtp(p, p.size)

        assertEquals(1, units.size)
        assertTrue(units[0].keyFrame)
        assertArrayEquals(start + sps + start + pps + start + idr, units[0].data)
        assertArrayEquals(sps, d.sps)
        assertArrayEquals(pps, d.pps)
        assertTrue(d.ready)
    }

    @Test
    fun `h264 FU-A is reassembled with its real NAL header`() {
        val units = ArrayList<AccessUnit>()
        val d = RtpDepacketizer(VideoCodec.H264) { units += it }
        // NAL 0x65 (IDR, nri 3) split in three: indicator 0x7c = nri 3 + type 28.
        val a = byteArrayOf(0x7c, 0x85.toByte(), 1, 2)
        val b = byteArrayOf(0x7c, 0x05, 3, 4)
        val c = byteArrayOf(0x7c, 0x45, 5)
        listOf(a to false, b to false, c to true).forEach { (payload, marker) ->
            val p = rtp(payload, 2000, marker)
            d.onRtp(p, p.size)
        }
        assertEquals(1, units.size)
        assertArrayEquals(start + byteArrayOf(0x65, 1, 2, 3, 4, 5), units[0].data)
        assertTrue(units[0].keyFrame)
    }

    @Test
    fun `a lost packet inside a fragment drops that picture instead of passing garbage on`() {
        val units = ArrayList<AccessUnit>()
        val d = RtpDepacketizer(VideoCodec.H264) { units += it }
        val first = rtp(byteArrayOf(0x7c, 0x85.toByte(), 1), 3000, false)
        d.onRtp(first, first.size)
        seq++ // the middle fragment never arrives
        val last = rtp(byteArrayOf(0x7c, 0x45, 9), 3000, true)
        d.onRtp(last, last.size)
        assertTrue(units.isEmpty())
        assertEquals(1, d.droppedUnits)
    }

    @Test
    fun `a camera that never sets the marker still gets its pictures out on the next timestamp`() {
        val units = ArrayList<AccessUnit>()
        val d = RtpDepacketizer(VideoCodec.H264) { units += it }
        val p1 = rtp(byteArrayOf(0x41, 1), 100, false)
        val p2 = rtp(byteArrayOf(0x41, 2), 200, false)
        d.onRtp(p1, p1.size)
        d.onRtp(p2, p2.size)
        assertEquals(1, units.size)
        assertFalse(units[0].keyFrame)
    }

    @Test
    fun `h265 FU rebuilds the two-byte NAL header and marks IRAP pictures as key frames`() {
        val units = ArrayList<AccessUnit>()
        val d = RtpDepacketizer(VideoCodec.H265) { units += it }
        // Payload header type 49, layer 0, tid 1: 0x62 0x01. FU header: S + type 19 (IDR_W_RADL).
        val s = byteArrayOf(0x62, 0x01, (0x80 or 19).toByte(), 7, 8)
        val e = byteArrayOf(0x62, 0x01, (0x40 or 19).toByte(), 9)
        listOf(s to false, e to true).forEach { (payload, marker) ->
            val p = rtp(payload, 5000, marker)
            d.onRtp(p, p.size)
        }
        assertEquals(1, units.size)
        assertArrayEquals(start + byteArrayOf((19 shl 1).toByte(), 0x01, 7, 8, 9), units[0].data)
        assertTrue(units[0].keyFrame)
    }

    @Test
    fun `h265 needs its VPS as well before it is ready`() {
        val d = RtpDepacketizer(VideoCodec.H265) { }
        val vps = byteArrayOf((32 shl 1).toByte(), 1, 0x0c)
        val sps = byteArrayOf((33 shl 1).toByte(), 1, 0x01)
        val pps = byteArrayOf((34 shl 1).toByte(), 1, 0x02)
        for (nal in listOf(sps, pps)) { val p = rtp(nal, 1, false); d.onRtp(p, p.size) }
        assertFalse(d.ready)
        val p = rtp(vps, 1, true)
        d.onRtp(p, p.size)
        assertTrue(d.ready)
        assertArrayEquals(start + vps + start + sps + start + pps, d.codecConfig())
    }

    @Test
    fun `the SDP gives the codec, the track and the parameter sets`() {
        val sps = byteArrayOf(0x67, 0x64, 0x00, 0x1f)
        val pps = byteArrayOf(0x68, 0xee.toByte())
        val b64 = Base64.getEncoder()
        val sdp = """
            v=0
            m=audio 0 RTP/AVP 97
            a=rtpmap:97 MPEG4-GENERIC/44100
            a=control:track2
            m=video 0 RTP/AVP 96
            a=rtpmap:96 H264/90000
            a=fmtp:96 packetization-mode=1;profile-level-id=64001f;sprop-parameter-sets=${b64.encodeToString(sps)},${b64.encodeToString(pps)}
            a=control:track1
        """.trimIndent()
        val track = parseSdpVideo(sdp, "rtsp://192.168.169.1") { Base64.getDecoder().decode(it) }
        assertNotNull(track)
        assertEquals(VideoCodec.H264, track!!.codec)
        assertEquals("rtsp://192.168.169.1/track1", track.control)
        assertEquals(96, track.payloadType)
        assertArrayEquals(sps, track.sps)
        assertArrayEquals(pps, track.pps)
    }

    @Test
    fun `an H265 SDP is recognised and an absolute control is kept`() {
        val sdp = "m=video 0 RTP/AVP 96\r\na=rtpmap:96 H265/90000\r\na=control:rtsp://192.168.169.1/stream=0\r\n"
        val track = parseSdpVideo(sdp, "rtsp://192.168.169.1/") { ByteArray(0) }
        assertEquals(VideoCodec.H265, track!!.codec)
        assertEquals("rtsp://192.168.169.1/stream=0", track.control)
    }
}
