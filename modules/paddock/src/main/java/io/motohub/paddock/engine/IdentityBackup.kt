// SPDX-License-Identifier: MPL-2.0
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
// of the MPL was not distributed with this file, you can obtain one at https://mozilla.org/MPL/2.0/.
package io.motohub.paddock.engine

import java.io.File

/**
 * The rider ID as text, in the format Sideband and Columba export and import: the 64-byte
 * Reticulum private key (X25519 + Ed25519), Base32 (RFC 4648, upper case, with padding).
 * The key file is what RNS.Identity.to_file writes: those same 64 bytes, raw.
 */
internal object IdentityBackup {
    const val KEY_BYTES = 64
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun export(identityFile: File): String? {
        val bytes = identityFile.takeIf { it.isFile }?.readBytes() ?: return null
        return if (bytes.size == KEY_BYTES) encode(bytes) else null
    }

    /** The 64 key bytes, or null when [text] is not a rider ID. Spaces, line breaks and case are ignored. */
    fun parse(text: String): ByteArray? {
        val clean = text.filterNot { it.isWhitespace() }.trimEnd('=').uppercase()
        if (clean.isEmpty() || clean.any { it !in ALPHABET }) return null
        return decode(clean)?.takeIf { it.size == KEY_BYTES }
    }

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size + 4) / 5 * 8)
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                out.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        while (out.length % 8 != 0) out.append('=')
        return out.toString()
    }

    private fun decode(text: String): ByteArray? {
        val out = java.io.ByteArrayOutputStream(text.length * 5 / 8)
        var buffer = 0
        var bits = 0
        for (c in text) {
            buffer = (buffer shl 5) or ALPHABET.indexOf(c)
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xFF)
                bits -= 8
            }
        }
        // Leftover bits must be zero padding, or the text was cut or mistyped.
        if (bits >= 5 || (buffer and ((1 shl bits) - 1)) != 0) return null
        return out.toByteArray()
    }
}
