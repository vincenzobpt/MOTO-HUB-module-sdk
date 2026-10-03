// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.dashcam.video

import java.io.ByteArrayOutputStream

/** The two codecs these cameras send. The rider can switch between them in the camera's settings. */
enum class VideoCodec(val mime: String) {
    H264("video/avc"),
    H265("video/hevc")
}

/**
 * One picture's worth of NAL units, in Annex-B form (each behind a 00 00 00 01 start code),
 * ready to be queued into a decoder.
 */
class AccessUnit(val data: ByteArray, val rtpTimestamp: Long, val keyFrame: Boolean)

/**
 * RTP payloads in, access units out: RFC 6184 for H.264, RFC 7798 for H.265.
 *
 * Handles what dashcams actually send - single NAL units, aggregation packets (STAP-A / AP) and
 * fragmentation units (FU-A / FU) - and remembers the parameter sets it sees, because a decoder
 * cannot be configured without them and some cameras only send them in-band, never in the SDP.
 *
 * An access unit ends on the RTP marker bit, or when the timestamp moves on (a camera that never
 * sets the marker still gets its frames out, one late).
 */
class RtpDepacketizer(val codec: VideoCodec, private val onAccessUnit: (AccessUnit) -> Unit) {
    var vps: ByteArray? = null
    var sps: ByteArray? = null
    var pps: ByteArray? = null

    private val unit = ByteArrayOutputStream(256 * 1024)
    private var unitTimestamp = -1L
    private var unitHasKey = false
    private var fragmentOpen = false
    private var lastSequence = -1

    /** Packets lost inside a fragment make the rest of that picture garbage: it is dropped. */
    var droppedUnits = 0
        private set

    /** Whether the decoder can be configured: the parameter sets this codec needs are known. */
    val ready: Boolean get() = sps != null && pps != null && (codec == VideoCodec.H264 || vps != null)

    fun onRtp(packet: ByteArray, length: Int) {
        // RTP version 2. The byte is signed in Kotlin: mask it before shifting, or 0x80 is not 2.
        if (length < 12 || ((packet[0].toInt() and 0xff) ushr 6) != 2) return
        val csrcCount = packet[0].toInt() and 0x0f
        val hasExtension = packet[0].toInt() and 0x10 != 0
        val hasPadding = packet[0].toInt() and 0x20 != 0
        val marker = packet[1].toInt() and 0x80 != 0
        val sequence = ((packet[2].toInt() and 0xff) shl 8) or (packet[3].toInt() and 0xff)
        val timestamp = ((packet[4].toLong() and 0xff) shl 24) or ((packet[5].toLong() and 0xff) shl 16) or
            ((packet[6].toLong() and 0xff) shl 8) or (packet[7].toLong() and 0xff)
        var offset = 12 + csrcCount * 4
        if (hasExtension) {
            if (offset + 4 > length) return
            val words = ((packet[offset + 2].toInt() and 0xff) shl 8) or (packet[offset + 3].toInt() and 0xff)
            offset += 4 + words * 4
        }
        var end = length
        if (hasPadding) end -= packet[length - 1].toInt() and 0xff
        if (offset >= end) return

        val gap = lastSequence >= 0 && ((lastSequence + 1) and 0xffff) != sequence
        lastSequence = sequence
        if (gap && fragmentOpen) {
            // The middle of a fragmented NAL went missing: what is in the buffer cannot be decoded.
            discardUnit()
        }

        if (unitTimestamp != -1L && timestamp != unitTimestamp) flush()
        unitTimestamp = timestamp

        when (codec) {
            VideoCodec.H264 -> h264(packet, offset, end)
            VideoCodec.H265 -> h265(packet, offset, end)
        }
        if (marker) flush()
    }

    private fun h264(p: ByteArray, offset: Int, end: Int) {
        val type = p[offset].toInt() and 0x1f
        when {
            type in 1..23 -> nal(p, offset, end - offset, type)
            type == 24 -> { // STAP-A: 16-bit size, then the NAL, repeated
                var i = offset + 1
                while (i + 2 <= end) {
                    val size = ((p[i].toInt() and 0xff) shl 8) or (p[i + 1].toInt() and 0xff)
                    i += 2
                    if (size <= 0 || i + size > end) break
                    nal(p, i, size, p[i].toInt() and 0x1f)
                    i += size
                }
            }
            type == 28 -> { // FU-A
                if (offset + 2 > end) return
                val header = p[offset + 1].toInt()
                val nalType = header and 0x1f
                val start = header and 0x80 != 0
                if (start) {
                    startCode()
                    unit.write((p[offset].toInt() and 0xe0) or nalType)
                    if (nalType == 5) unitHasKey = true
                    fragmentOpen = true
                } else if (!fragmentOpen) {
                    return // a fragment whose start we never saw
                }
                unit.write(p, offset + 2, end - offset - 2)
                if (header and 0x40 != 0) fragmentOpen = false
            }
        }
    }

    private fun h265(p: ByteArray, offset: Int, end: Int) {
        if (offset + 2 > end) return
        val type = (p[offset].toInt() ushr 1) and 0x3f
        when {
            type < 48 -> nal(p, offset, end - offset, type)
            type == 48 -> { // AP: after the 2-byte header, 16-bit size + NAL, repeated
                var i = offset + 2
                while (i + 2 <= end) {
                    val size = ((p[i].toInt() and 0xff) shl 8) or (p[i + 1].toInt() and 0xff)
                    i += 2
                    if (size <= 0 || i + size > end) break
                    nal(p, i, size, (p[i].toInt() ushr 1) and 0x3f)
                    i += size
                }
            }
            type == 49 -> { // FU
                if (offset + 3 > end) return
                val header = p[offset + 2].toInt()
                val nalType = header and 0x3f
                if (header and 0x80 != 0) {
                    startCode()
                    // The real NAL header: the payload header with its type replaced.
                    unit.write((p[offset].toInt() and 0x81) or (nalType shl 1))
                    unit.write(p[offset + 1].toInt())
                    if (nalType in 16..21) unitHasKey = true
                    fragmentOpen = true
                } else if (!fragmentOpen) {
                    return
                }
                unit.write(p, offset + 3, end - offset - 3)
                if (header and 0x40 != 0) fragmentOpen = false
            }
        }
    }

    private fun nal(p: ByteArray, offset: Int, size: Int, type: Int) {
        val copy = { p.copyOfRange(offset, offset + size) }
        when (codec) {
            VideoCodec.H264 -> when (type) {
                7 -> sps = copy()
                8 -> pps = copy()
                5 -> unitHasKey = true
            }
            VideoCodec.H265 -> when (type) {
                32 -> vps = copy()
                33 -> sps = copy()
                34 -> pps = copy()
                in 16..21 -> unitHasKey = true
            }
        }
        startCode()
        unit.write(p, offset, size)
    }

    private fun startCode() {
        unit.write(0); unit.write(0); unit.write(0); unit.write(1)
    }

    private fun flush() {
        if (unit.size() > 0 && !fragmentOpen) {
            onAccessUnit(AccessUnit(unit.toByteArray(), unitTimestamp, unitHasKey))
        } else if (fragmentOpen) {
            droppedUnits++
        }
        unit.reset()
        unitHasKey = false
        fragmentOpen = false
    }

    private fun discardUnit() {
        droppedUnits++
        unit.reset()
        unitHasKey = false
        fragmentOpen = false
    }

    /** The decoder's codec-specific data: every parameter set, each behind a start code. */
    fun codecConfig(): ByteArray {
        val out = ByteArrayOutputStream()
        listOfNotNull(vps.takeIf { codec == VideoCodec.H265 }, sps, pps).forEach {
            out.write(byteArrayOf(0, 0, 0, 1)); out.write(it)
        }
        return out.toByteArray()
    }
}
