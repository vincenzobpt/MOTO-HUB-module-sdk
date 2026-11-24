// SPDX-License-Identifier: MPL-2.0
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
// of the MPL was not distributed with this file, you can obtain one at https://mozilla.org/MPL/2.0/.
package io.motohub.paddock.engine

import kotlinx.coroutines.flow.StateFlow

/** What the rider chose on the profile screen. Kept on the phone only. */
data class Profile(
    val name: String,
    /** "#RRGGBB", one of [RiderColors]. */
    val color: String,
    /** Free text, may be empty: "CFMOTO 800MT Explore". */
    val bike: String
)

/** The colours a rider can pick: distinct on the map in daylight and at night. */
val RiderColors = listOf(
    "#FF7A1A", "#FFC233", "#3DDC97", "#33C7FF", "#6E8BFF", "#B57BFF", "#FF5C8A", "#EDEFF2"
)

enum class EngineStage {
    /** Python and Reticulum not started yet. */
    STOPPED,
    /** Unpacking files / starting Python / connecting. [PaddockState.startStep] says which. */
    STARTING,
    RUNNING,
    /** Could not start; [PaddockState.error] says why. */
    FAILED
}

enum class RoomPhase { JOINING, ACTIVE }

enum class Freshness { FRESH, STALE, NONE }

data class Member(
    val hash: String,
    val name: String,
    val color: String,
    val bike: String,
    val isMe: Boolean,
    val isLeader: Boolean,
    val lat: Double?,
    val lon: Double?,
    /** m/s */
    val speed: Double?,
    val bearing: Double?,
    /** Epoch seconds of the GPS fix, null until one arrives. */
    val fixAt: Long?,
    /** Epoch seconds we last heard from them. */
    val heardAt: Long?
) {
    /** ≤ 60 s fresh, ≤ 10 min stale, otherwise none (not drawn on the map). */
    fun freshness(nowSeconds: Long): Freshness {
        val at = fixAt ?: return Freshness.NONE
        val age = nowSeconds - at
        return when {
            age <= 60 -> Freshness.FRESH
            age <= 600 -> Freshness.STALE
            else -> Freshness.NONE
        }
    }
}

/** A permanent group the rider leads or joined ("Sunday group", "Track days"). */
data class Room(
    val id: String,
    val name: String,
    /** The paddock://join link: the QR's text and what "Share link" sends. */
    val link: String,
    val phase: RoomPhase,
    val iAmLeader: Boolean,
    val members: List<Member>,
    /** Epoch seconds. */
    val createdAt: Long = 0,
    /** Epoch seconds of the latest creation, roster change or position heard. */
    val lastActivity: Long = 0,
    /** I am riding with this room: my position goes to its members. At most one room. */
    val riding: Boolean = false,
    /** Chat messages not read yet. */
    val unread: Int = 0,
    /** The latest chat message, for the rooms list; null before the first. */
    val last: LastMessage? = null
)

data class LastMessage(val fromHash: String, val name: String, val quick: Boolean, val text: String, val atMillis: Long)

/** The one-tap messages for riding: big tiles, one tap (Emergency: a 2 s hold). */
enum class QuickKind(val wire: String, val label: String) {
    STOP("stop", "Stop"),
    FUEL("fuel", "Fuel"),
    SLOW("slow", "Slow down"),
    WAIT("wait", "Wait for me"),
    PHOTO("photo", "Photo stop"),
    SPOT("spot", "Share my spot"),
    EMERGENCY("emergency", "Emergency");

    companion object {
        fun of(wire: String?): QuickKind? = values().firstOrNull { it.wire == wire }
    }
}

enum class ChatKind { TEXT, QUICK, EVENT }

/**
 * How one of my messages fared, per recipient: delivered, held in a mailbox, waiting (their phone
 * is off: sent again when it is heard), or failed (they left the room).
 */
data class Delivery(
    val total: Int, val delivered: Int, val mailbox: Int, val failed: Int, val waiting: Int = 0,
    /** Each recipient by name with its state: "delivered", "mailbox", "waiting", "failed" or "pending". */
    val who: List<Pair<String, String>> = emptyList()
)

data class ChatMessage(
    val id: String,
    val fromHash: String,
    val name: String,
    /** "#RRGGBB" of the sender. */
    val color: String,
    val mine: Boolean,
    val kind: ChatKind,
    val text: String,
    val quick: QuickKind?,
    /** Where a SPOT or EMERGENCY was sent from. */
    val lat: Double?,
    val lon: Double?,
    val atMillis: Long,
    /** Only on my own messages. */
    val delivery: Delivery?
)

/** The chat of the room open on screen; [messages] newest last. */
data class ChatView(val roomId: String = "", val messages: List<ChatMessage> = emptyList(), val loading: Boolean = false)

sealed interface PaddockEvent {
    val text: String
    /** The room it is about, when there is one. */
    val roomId: String? get() = null
    data class RoomJoined(override val text: String, override val roomId: String? = null) : PaddockEvent
    data class MemberJoined(override val text: String, override val roomId: String? = null) : PaddockEvent
    data class MemberLeft(override val text: String, override val roomId: String? = null) : PaddockEvent
    data class RoomClosed(override val text: String, override val roomId: String? = null) : PaddockEvent
    data class RoomRenamed(override val text: String, override val roomId: String? = null) : PaddockEvent
    data class JoinFailed(override val text: String, override val roomId: String? = null) : PaddockEvent
    /** A request failed: the screen that made it gives its button back. */
    data class Error(override val text: String) : PaddockEvent
    data class CallIncoming(override val text: String) : PaddockEvent
    data class CallEnded(override val text: String) : PaddockEvent
    /** Something to know that is not a failed request (no location permission, say). */
    data class Warning(override val text: String) : PaddockEvent
}

/** A public relay: one this phone is connected to, or one Reticulum's discovery heard of. */
data class Relay(
    val name: String,
    val host: String,
    val port: Int,
    val connected: Boolean,
    val online: Boolean,
    /** How fast it answered when Paddock started, connected ones only. */
    val millis: Int?,
    val hops: Int?,
    /** "available", "unknown" (not heard for a day) or "stale" (three days). */
    val status: String,
    val lat: Double?,
    val lon: Double?,
    /** Connected because Reticulum found it on the network, not because it was among the fastest. */
    val found: Boolean = false,
    /** Connected because a room's leader shares it: the riders of the room meet there. */
    val room: Boolean = false
)

data class RelaysView(val auto: Boolean = true, val connected: List<Relay> = emptyList(), val discovered: List<Relay> = emptyList(), val loading: Boolean = false)

enum class CallStage { CALLING, RINGING_OUT, RINGING_IN, CONNECTED }

/** A voice call with one room-mate. */
data class Call(
    val incoming: Boolean,
    val stage: CallStage,
    /** The other rider's identity hash. */
    val peer: String,
    val name: String,
    val roomId: String,
    /** Epoch millis when both sides were connected, null before. */
    val connectedAt: Long?,
    val remoteMuted: Boolean
)

/** What the rider set for the call on this phone. */
data class CallAudio(val muted: Boolean = false, val speaker: Boolean = false, val headset: Boolean = false)

data class PaddockState(
    val stage: EngineStage = EngineStage.STOPPED,
    /** While STARTING: "Unpacking", "Starting Python", "Connecting to the relay". */
    val startStep: String = "",
    val error: String? = null,
    /** My LXMF destination hash, hex; empty until RUNNING. */
    val myHash: String = "",
    /** The TCP link to the relay is up. */
    val online: Boolean = false,
    /** "host:port" of the relay in use. */
    val node: String = "",
    /** Every room, newest activity first. */
    val rooms: List<Room> = emptyList(),
    /** The voice call on now, if any (VOICE.md). */
    val call: Call? = null,
    /** Running, but the relays are dropped until needed (no ride, no call, MOTO-HUB not on screen). */
    val standby: Boolean = false
) {
    /** The room I ride with, if any. */
    val riding: Room? get() = rooms.firstOrNull { it.riding }
    fun room(id: String): Room? = rooms.firstOrNull { it.id == id }
}

/**
 * The one object the screens talk to. Actions return at once; their effect shows up in [state].
 * Main-thread safe. [startRide] must be called from the rider's tap: it starts the app's
 * keep-alive with location, which Android only allows while the app is in front.
 */
interface PaddockController {
    val state: StateFlow<PaddockState>
    /** Null until the rider has made a profile. */
    val profile: StateFlow<Profile?>
    /** One-shot notices for a snackbar/toast: a rider joined, the room was closed... */
    val events: kotlinx.coroutines.flow.SharedFlow<PaddockEvent>

    /** Saves the profile; starts the engine the first time. */
    fun saveProfile(profile: Profile)
    /** Starts Python + Reticulum if a profile exists and it is not running. */
    fun ensureStarted()

    /** The new room shows up in [state]; a [PaddockEvent] says if it failed. */
    fun createRoom(name: String)
    /** False when [link] is not a Paddock room link. */
    fun joinRoom(link: String): Boolean
    /** Leader only: every member sees the new name. */
    fun renameRoom(roomId: String, name: String)
    /** Rings a room-mate. Needs the microphone: the screen asks for it first. */
    fun callRider(roomId: String, memberHash: String)
    fun answerCall()
    fun declineCall()
    fun hangUp()
    fun setMuted(muted: Boolean)
    fun setSpeaker(on: Boolean)
    val callAudio: StateFlow<CallAudio>

    /** Member: leave the room. Leader: closes it for everyone. */
    fun leaveRoom(roomId: String)

    /** Leader only: the rider is removed from the room (they see it disappear). */
    fun removeMember(roomId: String, memberHash: String)

    /**
     * The chat of [roomId] is on screen: [chat] follows it (refreshed every poll), it counts as
     * read, and the mailbox is checked. [closeChat] when the screen goes.
     */
    fun openChat(roomId: String)
    fun closeChat()
    val chat: StateFlow<ChatView>
    /** Older messages than those in [chat], added at the top. */
    fun loadEarlierChat()
    fun sendText(roomId: String, text: String)
    /** SPOT and EMERGENCY carry my latest GPS fix when there is one. */
    fun sendQuick(roomId: String, kind: QuickKind)
    /** Metres from me to [member], from the latest fixes; null when either is unknown. */
    fun distanceTo(member: Member): Double?
    /** The NAV map centred on a point a message carried (SPOT, EMERGENCY). */
    fun showPoint(lat: Double, lon: Double, label: String)

    /** My position goes to this room's members (only this room) until [stopRide]. */
    fun startRide(roomId: String)
    fun stopRide()

    /** Opens the Android share sheet with the room's link. */
    fun shareRoomLink(context: android.content.Context, roomId: String)
    /** The NAV map centred on [member], or on the room's most recently seen rider. */
    fun showOnMap(roomId: String, member: Member?)

    /** "host:port"; applied at the next app start. */
    fun setRelay(host: String, port: Int)
    /** Back to the fastest public relays, chosen at each start. */
    fun setAutoRelay()
    val relays: StateFlow<RelaysView>
    /** Reads the relays again (the screen calls it while open). */
    fun refreshRelays()
    /** Where this phone is, for the relays map; null when unknown. */
    fun myLocation(): Pair<Double, Double>?
    /** Whether Android lets an incoming call light the screen and show above the lock screen. */
    fun fullScreenCallsAllowed(): Boolean
    /**
     * Off (the default): connected to the relays only during a ride, a call, or while MOTO-HUB is
     * on screen (and one minute after). On: connected all the time, at a battery cost.
     */
    val alwaysOn: StateFlow<Boolean>
    fun setAlwaysOn(on: Boolean)
    /**
     * The rider tapped Disconnect: no relay, no automatic start at the next MOTO-HUB start, until
     * [connect]. Kept on disk. A ride and a call end with it.
     */
    val disconnected: StateFlow<Boolean>
    fun disconnect()
    fun connect()
    val defaultRelay: String
    /**
     * Ends MOTO-HUB's process and opens it again: the only cure once Python is loaded twice or the
     * rider ID changed. Swiping the app away does not end the process.
     */
    fun restartMotoHub(context: android.content.Context)

    /** Forgets the rider ID, the profile and any room. */
    fun resetEverything()

    /**
     * The rider ID as text to keep somewhere safe (Base32 of the 64-byte private key, the format
     * Sideband and Columba use). Null before an ID exists. Whoever holds it can act as this rider.
     */
    fun exportIdentity(): String?

    /**
     * Puts a rider ID saved with [exportIdentity] (or exported from Sideband/Columba) on this
     * phone. Null when done; otherwise a sentence saying what is wrong. If Paddock was already
     * running with another ID, [state] turns FAILED asking the rider to restart MOTO-HUB.
     */
    fun importIdentity(text: String): String?
}
