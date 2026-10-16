// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.immich

import org.junit.Assert.assertEquals
import org.junit.Test

class ShareTest {

    private fun bytes(vararg parts: Any): ByteArray = parts.flatMap { p ->
        when (p) {
            is String -> p.toByteArray(Charsets.ISO_8859_1).toList()
            is Int -> listOf(p.toByte())
            else -> error(p)
        }
    }.toByteArray()

    @Test
    fun knowsTheImageByItsFirstBytes() {
        assertEquals("jpg" to "image/jpeg", imageKind(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals("png" to "image/png", imageKind(bytes(0x89, "PNG\r\n")))
        assertEquals("webp" to "image/webp", imageKind(bytes("RIFF", 0, 0, 0, 0, "WEBPVP8 ")))
        assertEquals("gif" to "image/gif", imageKind(bytes("GIF89a")))
        assertEquals("avif" to "image/avif", imageKind(bytes(0, 0, 0, 0x1C, "ftypavif")))
        assertEquals("heic" to "image/heic", imageKind(bytes(0, 0, 0, 0x18, "ftypheic")))
        assertEquals("jpg" to "image/*", imageKind(bytes(1, 2)))
        assertEquals("jpg" to "image/*", imageKind(ByteArray(0)))
    }

    @Test
    fun namesTheFileAfterThePhoto() {
        assertEquals("IMG_1234.jpg", shareName("IMG_1234.HEIC", "a1b2c3d4-0001", "jpg"))
        assertEquals("Passo Stelvio_.webp", shareName("Passo Stelvio?.jpg", "a1b2c3d4-0001", "webp"))
        assertEquals("immich-a1b2c3d4.jpg", shareName(null, "a1b2c3d4-0001", "jpg"))
        assertEquals("immich-a1b2c3d4.jpg", shareName("../.jpg", "a1b2c3d4-0001", "jpg"))
        assertEquals("immich-a1b2c3d4.jpg", shareName("日本.jpg", "a1b2c3d4-0001", "jpg"))
    }
}
