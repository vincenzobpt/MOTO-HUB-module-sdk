// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Vincenzo Buonomano.
// Part of the MOTO-HUB module SDK; see module-api/LICENSE.
package io.motohub.android.module

/**
 * A group ride a module runs (contract 48), shown on the rider's own dashboard: who is riding with
 * them, what they last said, and a handful of one-tap messages (Stop, Fuel, Help...) sent from the
 * handlebar or a Bluetooth remote.
 *
 * The module owns the room, the transport and the chat; the app only reads a snapshot and asks the
 * module to send. Answered as a capability by a module that has a ride to show.
 *
 * Threads: [snapshot] is called every frame on the render thread, so it returns a value the module
 * already holds and never waits for anything. [sendQuick] may be called from any thread but main
 * and may take its time.
 */
interface ModuleGroupRide {

    /**
     * What the dashboard shows now, or null when the module has nothing to show (no room, engine not
     * started). The same object may be returned until something changes.
     */
    fun snapshot(): GroupRideSnapshot?

    /**
     * Sends the quick message [kind] to the room the rider rides with: `stop`, `fuel`, `slow`,
     * `wait`, `photo`, `spot` or `emergency`. Returns null when it was sent (or queued), otherwise a
     * short sentence for the rider saying why it was not ("Start a ride with a room first").
     */
    fun sendQuick(kind: String): String?
}

/** The riding room at one moment. Times are wall clock (epoch milliseconds). */
class GroupRideSnapshot(
    /** The room's name; empty when the rider rides with none. */
    val room: String,
    /** The rider is riding with that room (their position goes to its members). */
    val riding: Boolean,
    /** The link to the network is up. */
    val online: Boolean,
    /** How many relays the module is connected to; 0 when not known. */
    val relays: Int,
    /** The other riders, nearest first when their distance is known. */
    val members: List<GroupRideMember>,
    /** The latest chat messages of the room, oldest first, at most 8. */
    val messages: List<GroupRideMessage>,
    val atMillis: Long
)

class GroupRideMember(
    val name: String,
    /** ARGB. */
    val color: Int,
    /** Metres per second as the rider's GPS reported it; NaN when unknown. */
    val speedMetersPerSecond: Float,
    /** Straight-line distance from the rider, metres; NaN when either position is unknown. */
    val distanceMeters: Float,
    /** In front of the rider along their heading (true), behind (false), unknown (null). */
    val ahead: Boolean?,
    /** Seconds since this rider's last position, -1 when none yet. */
    val ageSeconds: Int
)

class GroupRideMessage(
    /** Same for every copy of one message; the app uses it to tell a new message from one it showed. */
    val id: String,
    val from: String,
    /** ARGB. */
    val color: Int,
    /** `stop`, `fuel`, `slow`, `wait`, `photo`, `spot`, `emergency`, or null for a text message. */
    val quick: String?,
    /** The text of a text message; empty for a quick one. */
    val text: String,
    val atMillis: Long,
    /** The rider sent it. */
    val mine: Boolean,
    /** For a message of the rider's own: how many riders have it, of [total]; 0 and 0 on others'. */
    val delivered: Int,
    val total: Int
)
