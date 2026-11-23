// SPDX-License-Identifier: MPL-2.0
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
// of the MPL was not distributed with this file, you can obtain one at https://mozilla.org/MPL/2.0/.
package io.motohub.paddock.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StateJsonTest {

    @Test
    fun parsesRoomMembersAndEvents() {
        val json = JSONObject(
            """
            {"me": {"hash": "aa", "online": true, "node": "rns.kin.earth:4242"},
             "riding": "r1",
             "rooms": [{"id": "r1", "name": "Sunday", "link": "paddock://join?v=1", "state": "active",
                      "iAmLeader": true, "createdAt": 10, "lastActivity": 20, "members": [
                {"hash": "aa", "name": "Vin", "color": "#FF7A1A", "bike": "", "isMe": true, "isLeader": true,
                 "lat": null, "lon": null, "fixAt": null, "heardAt": null},
                {"hash": "bb", "name": "Marco", "color": "nope", "bike": "800MT", "isMe": false,
                 "isLeader": false, "lat": 45.5, "lon": 9.1, "speed": 12.0, "fixAt": 100, "heardAt": 101},
                {"hash": "cc", "lat": 200.0, "lon": 9.1}
             ]}, {"id": "r2", "name": "Track", "state": "joining", "members": []}],
             "events": [{"type": "member_joined", "room": "r1", "text": "Marco joined the room."}, {"type": "???"}]}
            """.trimIndent()
        )
        val state = StateJson.parse(json, PaddockState(stage = EngineStage.STARTING, startStep = "x"))
        assertEquals(EngineStage.RUNNING, state.stage)
        assertTrue(state.online)
        assertEquals(2, state.rooms.size)
        val room = state.room("r1")!!
        assertEquals(RoomPhase.ACTIVE, room.phase)
        assertTrue(room.riding)
        assertEquals(20L, room.lastActivity)
        assertEquals(room, state.riding)
        assertEquals(RoomPhase.JOINING, state.room("r2")!!.phase)
        assertTrue(!state.room("r2")!!.riding)
        assertEquals(3, room.members.size)
        assertNull(room.members[0].lat)
        assertEquals(RiderColors.first(), room.members[1].color)
        assertEquals(45.5, room.members[1].lat!!, 0.0)
        // An out-of-range latitude drops the whole position, not half of it.
        assertNull(room.members[2].lat)
        assertNull(room.members[2].lon)
        assertEquals(Freshness.FRESH, room.members[1].freshness(150))
        assertEquals(Freshness.STALE, room.members[1].freshness(500))
        assertEquals(Freshness.NONE, room.members[1].freshness(1000))
        val events = StateJson.events(json)
        assertEquals(1, events.size)
        assertTrue(events[0] is PaddockEvent.MemberJoined)
        assertEquals("r1", events[0].roomId)
    }

    @Test
    fun parsesChat() {
        val json = JSONObject(
            """
            {"messages": [
              {"id": "a1", "from": "bb", "name": "Marco", "color": "#33C7FF", "mine": false, "kind": "text",
               "text": "Coffee?", "quick": null, "lat": null, "lon": null, "at": 1000, "delivery": null},
              {"id": "a2", "from": "cc", "name": "Luca", "color": "bad", "mine": false, "kind": "quick",
               "text": "Fuel", "quick": "fuel", "lat": 45.1, "lon": 9.2, "at": 2000},
              {"id": "a3", "from": "aa", "name": "Vin", "mine": true, "kind": "text", "text": "Yes",
               "at": 3000, "delivery": {"total": 3, "delivered": 1, "mailbox": 1, "failed": 0, "waiting": 1}},
              {"id": "a4", "kind": "event", "text": "Luca joined the room.", "at": 500},
              {"id": "a5", "kind": "quick", "quick": "nonsense", "lat": 95.0, "lon": 9.0, "at": 4000}
            ]}
            """.trimIndent()
        )
        val chat = StateJson.chat(json)
        assertEquals(5, chat.size)
        assertEquals(ChatKind.TEXT, chat[0].kind)
        assertEquals(QuickKind.FUEL, chat[1].quick)
        assertEquals(RiderColors.first(), chat[1].color)
        assertEquals(45.1, chat[1].lat!!, 0.0)
        assertEquals(Delivery(3, 1, 1, 0, 1), chat[2].delivery)
        assertTrue(chat[2].mine)
        assertEquals(ChatKind.EVENT, chat[3].kind)
        assertNull(chat[4].quick)
        assertNull(chat[4].lat)
    }

    @Test
    fun noRoom() {
        val state = StateJson.parse(JSONObject("""{"me": {"hash": "aa"}, "riding": null, "rooms": []}"""), PaddockState())
        assertTrue(state.rooms.isEmpty())
        assertNull(state.riding)
    }
}
