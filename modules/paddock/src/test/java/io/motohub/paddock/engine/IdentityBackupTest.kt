// SPDX-License-Identifier: MPL-2.0
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
// of the MPL was not distributed with this file, you can obtain one at https://mozilla.org/MPL/2.0/.
package io.motohub.paddock.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IdentityBackupTest {

    @Test
    fun rfc4648Vectors() {
        assertEquals("MZXW6YTBOI======", IdentityBackup.encode("foobar".toByteArray()))
        assertEquals("MY======", IdentityBackup.encode("f".toByteArray()))
    }

    @Test
    fun roundTripsA64ByteKeyAndForgivesFormatting() {
        val key = ByteArray(64) { (it * 7 + 3).toByte() }
        val text = IdentityBackup.encode(key)
        assertArrayEquals(key, IdentityBackup.parse(text))
        // As pasted from a note: lower case, wrapped, no padding.
        val messy = text.lowercase().trimEnd('=').chunked(16).joinToString("\n ")
        assertArrayEquals(key, IdentityBackup.parse(messy))
    }

    @Test
    fun rejectsWhatIsNotAKey() {
        assertNull(IdentityBackup.parse(""))
        assertNull(IdentityBackup.parse("paddock://join?v=1"))
        assertNull(IdentityBackup.parse(IdentityBackup.encode(ByteArray(32))))
        val cut = IdentityBackup.encode(ByteArray(64) { 1 }).trimEnd('=').dropLast(3)
        assertNull(IdentityBackup.parse(cut))
    }
}
