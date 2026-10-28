// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.dashcam

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Several cameras in one file, and the single camera of 0.4.x carried into it. */
class DashcamStoreTest {
    private val dir: File = Files.createTempDirectory("dashcam").toFile()

    @Test
    fun `the one camera of an older module becomes the first of the list`() {
        File(dir, "camera.properties").writeText("ssid=S1-c020\npassword=12345678\n")
        val store = DashcamStore(dir)
        assertEquals("1", store.active)
        assertEquals("S1-c020", store.ssid)
        assertEquals("12345678", store.password)
        assertFalse(store.lan)
        assertEquals(listOf("S1-c020"), store.cameras().map { it.name })
    }

    @Test
    fun `a second camera is added, switched to, kept apart and forgotten`() {
        val store = DashcamStore(dir)
        store.ssid = "S1-c020"
        store.startNew()
        store.lan = true
        store.lanHost = "192.168.43.17"
        store.lanUser = "admin"
        store.save()

        val again = DashcamStore(dir)
        assertEquals(2, again.cameras().size)
        assertEquals("192.168.43.17", again.lanHost)
        assertTrue(again.lan)
        again.select(again.cameras()[0].id)
        assertEquals("S1-c020", again.ssid)
        assertFalse(again.lan)
        assertEquals("", again.lanUser)

        again.remove(again.active)
        assertEquals(listOf("192.168.43.17"), again.cameras().map { it.where })
        assertEquals("192.168.43.17", again.lanHost)
    }

    @Test
    fun `a new camera left unfinished is dropped and the one before comes back`() {
        val store = DashcamStore(dir)
        store.ssid = "A"
        store.startNew()
        store.startNew() // the first draft, never filled, is not kept
        store.select(store.cameras()[0].id)
        store.startNew()
        assertFalse(store.configured)
        assertTrue(store.dropUnfinished())
        assertEquals("A", store.ssid)
        assertEquals(1, store.cameras().size)
        assertFalse(store.dropUnfinished())
    }

    @Test
    fun `the name a camera gave is the one listed`() {
        val store = DashcamStore(dir)
        store.ssid = "S1-c020"
        store.setLabel(store.active, "LINGTUO S1")
        assertEquals("LINGTUO S1", store.name)
        assertEquals("LINGTUO S1", store.cameras()[0].name)
    }
}
