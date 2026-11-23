// SPDX-License-Identifier: MPL-2.0
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
// of the MPL was not distributed with this file, you can obtain one at https://mozilla.org/MPL/2.0/.
package io.motohub.paddock.engine

import org.json.JSONArray
import org.json.JSONObject

/** paddock_engine.poll()'s JSON (DESIGN.md §4) into the model. Lenient: a bad field is a null, never a crash. */
internal object StateJson {

    fun parse(json: JSONObject, previous: PaddockState): PaddockState {
        val me = json.optJSONObject("me")
        return previous.copy(
            stage = EngineStage.RUNNING,
            startStep = "",
            error = null,
            myHash = me?.optString("hash").orEmpty(),
            online = me?.optBoolean("online", false) ?: false,
            node = me?.optString("node").orEmpty(),
            rooms = json.optJSONArray("rooms")?.let { list ->
                val riding = json.optString("riding")
                (0 until list.length()).mapNotNull { i -> list.optJSONObject(i)?.let { room(it, riding) } }
            }.orEmpty(),
            call = json.optJSONObject("call")?.let(::call)
        )
    }

    private fun call(o: JSONObject): Call? {
        val stage = when (o.optString("state")) {
            "calling" -> CallStage.CALLING
            "ringing_out" -> CallStage.RINGING_OUT
            "ringing" -> CallStage.RINGING_IN
            "connected" -> CallStage.CONNECTED
            else -> return null
        }
        return Call(
            incoming = o.optString("dir") == "in", stage = stage, peer = o.optString("peer"),
            name = o.optString("name").ifEmpty { "Rider" }, roomId = o.optString("room"),
            connectedAt = if (o.isNull("connectedAt")) null else (o.optDouble("connectedAt") * 1000).toLong(),
            remoteMuted = o.optBoolean("remoteMuted", false)
        )
    }

    private fun room(o: JSONObject, ridingId: String): Room? {
        val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return null
        val members = o.optJSONArray("members") ?: JSONArray()
        return Room(
            id = id,
            name = o.optString("name").ifEmpty { "Ride" },
            link = o.optString("link"),
            phase = if (o.optString("state") == "active") RoomPhase.ACTIVE else RoomPhase.JOINING,
            iAmLeader = o.optBoolean("iAmLeader", false),
            members = (0 until members.length()).mapNotNull { members.optJSONObject(it)?.let(::member) },
            createdAt = o.longOrNull("createdAt") ?: 0,
            lastActivity = o.longOrNull("lastActivity") ?: 0,
            riding = id == ridingId,
            unread = o.optInt("unread", 0).coerceAtLeast(0),
            last = o.optJSONObject("last")?.let { l ->
                LastMessage(
                    fromHash = l.optString("from"),
                    name = l.optString("name").ifEmpty { "Rider" },
                    quick = l.optString("kind") == "quick",
                    text = l.optString("text"),
                    atMillis = l.longOrNull("at") ?: 0
                )
            }
        )
    }

    private fun member(o: JSONObject): Member? {
        val hash = o.optString("hash").takeIf { it.isNotEmpty() } ?: return null
        val lat = o.doubleOrNull("lat")?.takeIf { it in -90.0..90.0 }
        val lon = o.doubleOrNull("lon")?.takeIf { it in -180.0..180.0 }
        return Member(
            hash = hash,
            name = o.optString("name").ifEmpty { "Rider" },
            color = o.optString("color").takeIf { it.matches(COLOR) } ?: RiderColors.first(),
            bike = o.optString("bike"),
            isMe = o.optBoolean("isMe", false),
            isLeader = o.optBoolean("isLeader", false),
            lat = if (lon == null) null else lat,
            lon = if (lat == null) null else lon,
            speed = o.doubleOrNull("speed"),
            bearing = o.doubleOrNull("bearing"),
            fixAt = o.longOrNull("fixAt"),
            heardAt = o.longOrNull("heardAt")
        )
    }

    fun events(json: JSONObject): List<PaddockEvent> {
        val list = json.optJSONArray("events") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val e = list.optJSONObject(i) ?: return@mapNotNull null
            val text = e.optString("text")
            val room = e.optString("room").ifEmpty { null }
            when (e.optString("type")) {
                "room_joined" -> PaddockEvent.RoomJoined(text, room)
                "member_joined" -> PaddockEvent.MemberJoined(text, room)
                "member_left" -> PaddockEvent.MemberLeft(text, room)
                "room_closed" -> PaddockEvent.RoomClosed(text, room)
                "room_renamed" -> PaddockEvent.RoomRenamed(text, room)
                "join_failed" -> PaddockEvent.JoinFailed(text, room)
                "error" -> PaddockEvent.Error(text)
                "call_incoming" -> PaddockEvent.CallIncoming(text)
                "call_ended" -> PaddockEvent.CallEnded(text)
                else -> null
            }
        }
    }

    /** chat()'s JSON (DESIGN.md §4b), newest last. */
    fun chat(json: JSONObject): List<ChatMessage> {
        val list = json.optJSONArray("messages") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val o = list.optJSONObject(i) ?: return@mapNotNull null
            val kind = when (o.optString("kind")) {
                "quick" -> ChatKind.QUICK
                "event" -> ChatKind.EVENT
                else -> ChatKind.TEXT
            }
            val lat = o.doubleOrNull("lat")?.takeIf { it in -90.0..90.0 }
            val lon = o.doubleOrNull("lon")?.takeIf { it in -180.0..180.0 }
            ChatMessage(
                id = o.optString("id").ifEmpty { "m$i" },
                fromHash = o.optString("from"),
                name = o.optString("name").ifEmpty { "Rider" },
                color = o.optString("color").takeIf { it.matches(COLOR) } ?: RiderColors.first(),
                mine = o.optBoolean("mine", false),
                kind = kind,
                text = o.optString("text"),
                quick = if (o.isNull("quick")) null else QuickKind.of(o.optString("quick")),
                lat = if (lon == null) null else lat,
                lon = if (lat == null) null else lon,
                atMillis = o.longOrNull("at") ?: 0,
                delivery = o.optJSONObject("delivery")?.let {
                    val who = it.optJSONArray("who")
                    Delivery(
                        it.optInt("total"), it.optInt("delivered"), it.optInt("mailbox"), it.optInt("failed"), it.optInt("waiting"),
                        (0 until (who?.length() ?: 0)).mapNotNull { j ->
                            who?.optJSONObject(j)?.let { w -> w.optString("name").ifEmpty { "Rider" } to w.optString("state") }
                        }
                    )
                }
            )
        }
    }

    private val COLOR = Regex("#[0-9A-Fa-f]{6}")

    private fun JSONObject.doubleOrNull(key: String): Double? =
        if (isNull(key)) null else optDouble(key).takeIf { it.isFinite() }

    private fun JSONObject.longOrNull(key: String): Long? =
        if (isNull(key)) null else optLong(key, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }
}
