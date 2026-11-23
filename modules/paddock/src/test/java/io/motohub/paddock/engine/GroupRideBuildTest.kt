// SPDX-License-Identifier: MPL-2.0
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
// of the MPL was not distributed with this file, you can obtain one at https://mozilla.org/MPL/2.0/.
package io.motohub.paddock.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupRideBuildTest {

    private fun member(name: String, lat: Double?, lon: Double?, isMe: Boolean = false, speed: Double? = 10.0, fixAt: Long? = 100L) =
        Member(name, name, "#33C7FF", "", isMe, false, lat, lon, speed, null, fixAt, fixAt)

    private val room = Room(
        "r1", "Sunday", "", RoomPhase.ACTIVE, true,
        listOf(
            member("Me", 45.0, 9.0, isMe = true),
            member("Far", 45.02, 9.0),
            member("Near", 45.001, 9.0),
            member("Behind", 44.999, 9.0),
            member("Unknown", null, null, speed = null, fixAt = null)
        ),
        riding = true
    )

    @Test
    fun ordersNearestFirstAndLeavesMeOut() {
        val list = GroupRideBuild.members(room, 45.0, 9.0, 0.0, 15.0, 160)
        // Near and Behind are both ~111 m away; Far ~2.2 km; Unknown last.
        assertEquals(setOf("Near", "Behind"), list.take(2).map { it.name }.toSet())
        assertEquals(listOf("Far", "Unknown"), list.drop(2).map { it.name })
        assertEquals(111.0, list[0].distanceMeters.toDouble(), 2.0)
        assertTrue(list[3].distanceMeters.isNaN())
        assertTrue(list[3].speedMetersPerSecond.isNaN())
        assertEquals(-1, list[3].ageSeconds)
        assertEquals(60, list[0].ageSeconds)
    }

    @Test
    fun aheadNeedsBearingAndSpeed() {
        val north = GroupRideBuild.members(room, 45.0, 9.0, 0.0, 15.0, 160).associateBy { it.name }
        assertEquals(true, north.getValue("Near").ahead)
        assertEquals(false, north.getValue("Behind").ahead)
        // Heading south: the same two swap.
        val south = GroupRideBuild.members(room, 45.0, 9.0, 180.0, 15.0, 160).associateBy { it.name }
        assertEquals(false, south.getValue("Near").ahead)
        // Standing still, or no heading: not said.
        assertNull(GroupRideBuild.members(room, 45.0, 9.0, 0.0, 0.5, 160).first { it.name == "Near" }.ahead)
        assertNull(GroupRideBuild.members(room, 45.0, 9.0, null, 15.0, 160).first { it.name == "Near" }.ahead)
        // No fix of my own: no distance either.
        val blind = GroupRideBuild.members(room, null, null, null, null, 160).first { it.name == "Near" }
        assertTrue(blind.distanceMeters.isNaN())
        assertNull(blind.ahead)
    }

    @Test
    fun messagesDropEventsAndKeepDelivery() {
        fun msg(id: String, kind: ChatKind, mine: Boolean, quick: QuickKind? = null, delivery: Delivery? = null) =
            ChatMessage(id, "h", "Marco", "#FF7A1A", mine, kind, "hi", quick, null, null, 5L, delivery)
        val out = GroupRideBuild.messages(
            listOf(
                msg("e", ChatKind.EVENT, false),
                msg("t", ChatKind.TEXT, false),
                msg("q", ChatKind.QUICK, true, QuickKind.FUEL, Delivery(3, 2, 0, 0))
            )
        )
        assertEquals(listOf("t", "q"), out.map { it.id })
        assertNull(out[0].quick)
        assertEquals("hi", out[0].text)
        assertEquals(0, out[0].total)
        assertEquals("fuel", out[1].quick)
        assertEquals("", out[1].text)
        assertEquals(2, out[1].delivered)
        assertEquals(3, out[1].total)
        assertEquals(0xFFFF7A1A.toInt(), out[0].color)
        assertEquals(0xFFFF7A1A.toInt(), GroupRideBuild.argb("nope"))
    }
}
