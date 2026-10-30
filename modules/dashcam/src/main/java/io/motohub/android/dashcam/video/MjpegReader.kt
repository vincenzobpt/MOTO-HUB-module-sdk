// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.dashcam.video

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * One JPEG after another out of an MJPEG stream - `multipart/x-mixed-replace` over HTTP, as
 * ESP32-CAMs, cheap IP cameras and phone webcam apps send it.
 *
 * Each picture is found by walking its own JPEG markers rather than by the multipart framing:
 * cameras get the boundary and the part lengths wrong often enough, and a marker walk does not
 * care what sits between pictures. Segments are skipped by their length, so the thumbnail some
 * cameras embed in an EXIF block cannot end a picture early with its own end marker.
 */
class MjpegReader(private val input: InputStream) {

    /** The next whole picture; throws [EOFException] when the camera hangs up. */
    fun next(): ByteArray {
        val out = ByteArrayOutputStream(64 * 1024)
        // Whatever comes before the picture - boundary, part headers - is skipped.
        var previous = -1
        while (true) {
            val b = read()
            if (previous == 0xFF && b == 0xD8) break
            previous = b
        }
        out.write(0xFF)
        out.write(0xD8)
        var marker = nextMarker()
        while (true) {
            out.write(0xFF)
            out.write(marker)
            when (marker) {
                0xD9 -> return out.toByteArray()
                in 0xD0..0xD7, 0x01 -> marker = nextMarker()
                else -> {
                    val length = (read() shl 8) or read()
                    if (length < 2) throw IOException("Not a JPEG: a segment of length $length.")
                    out.write(length ushr 8)
                    out.write(length and 0xFF)
                    repeat(length - 2) { out.write(read()) }
                    marker = if (marker == 0xDA) scan(out) else nextMarker()
                }
            }
            if (out.size() > MAX_PICTURE_BYTES) throw IOException("A picture over ${MAX_PICTURE_BYTES / 1_048_576} MB: not a camera's.")
        }
    }

    /** The marker after a segment: 0xFF, any fill bytes, then the marker code. */
    private fun nextMarker(): Int {
        if (read() != 0xFF) throw IOException("Not a JPEG: no marker where one belongs.")
        var m = read()
        while (m == 0xFF) m = read()
        return m
    }

    /**
     * The compressed picture after a start-of-scan, copied until the marker that ends it. Inside it
     * a 0xFF is always followed by 0x00 (a stuffed byte) or a restart marker, so neither ends it.
     */
    private fun scan(out: ByteArrayOutputStream): Int {
        while (true) {
            val b = read()
            if (b != 0xFF) {
                out.write(b)
                continue
            }
            var m = read()
            while (m == 0xFF) m = read()
            if (m == 0x00 || m in 0xD0..0xD7) {
                out.write(0xFF)
                out.write(m)
                if (out.size() > MAX_PICTURE_BYTES) throw IOException("A picture over ${MAX_PICTURE_BYTES / 1_048_576} MB: not a camera's.")
                continue
            }
            return m
        }
    }

    private fun read(): Int {
        val b = input.read()
        if (b < 0) throw EOFException("The camera closed the video connection.")
        return b
    }

    private companion object {
        const val MAX_PICTURE_BYTES = 8 * 1_048_576
    }
}
