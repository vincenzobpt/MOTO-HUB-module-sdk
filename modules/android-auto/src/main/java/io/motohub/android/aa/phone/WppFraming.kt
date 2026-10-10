// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.aa.phone

/** The Wi-Fi Projection Protocol frame: a 4-byte header (uint16 payload length, uint16 type) + payload. */
object WppFraming {
    const val HEADER_SIZE = 4

    fun encodeHeader(payloadSize: Int, type: Int): ByteArray {
        require(payloadSize in 0..0xFFFF) { "WPP payload size $payloadSize does not fit in uint16" }
        require(type in 0..0xFFFF) { "WPP message type $type does not fit in uint16" }
        return byteArrayOf(
            ((payloadSize shr 8) and 0xFF).toByte(),
            (payloadSize and 0xFF).toByte(),
            ((type shr 8) and 0xFF).toByte(),
            (type and 0xFF).toByte()
        )
    }

    fun encodeFrame(payload: ByteArray, type: Int): ByteArray =
        encodeHeader(payload.size, type) + payload

    fun decodePayloadSize(header: ByteArray): Int {
        require(header.size >= HEADER_SIZE) { "WPP header needs $HEADER_SIZE bytes, got ${header.size}" }
        return ((header[0].toInt() and 0xFF) shl 8) or (header[1].toInt() and 0xFF)
    }

    fun decodeType(header: ByteArray): Int {
        require(header.size >= HEADER_SIZE) { "WPP header needs $HEADER_SIZE bytes, got ${header.size}" }
        return ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
    }
}
