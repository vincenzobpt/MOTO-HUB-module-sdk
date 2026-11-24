// SPDX-License-Identifier: MPL-2.0
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
// of the MPL was not distributed with this file, you can obtain one at https://mozilla.org/MPL/2.0/.
package io.motohub.paddock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableTarget
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.motohub.android.module.ModuleUi
import io.motohub.paddock.engine.EngineStage
import io.motohub.paddock.engine.PaddockState
import io.motohub.paddock.engine.Profile
import io.motohub.paddock.engine.Room
import io.motohub.paddock.engine.RoomPhase

/** The steps the engine reports while it starts, in order (PaddockEngine.ensureStarted). */
private val START_STEPS = listOf("Unpacking Paddock (first time only)", "Starting the secure engine", "Connecting to the relay")

/**
 * The module's home: the ride in progress (if any) on top, who I am, whether Paddock is ready,
 * the two things to do - open a room or join one - and my rooms, led and joined. While the
 * engine starts, its steps take the place of the actions and the rooms.
 */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun HomeScreen(
    state: PaddockState,
    profile: Profile,
    onOpenRoom: () -> Unit,
    onScan: () -> Unit,
    onPaste: () -> Unit,
    onRoom: (String) -> Unit,
    onPrivacy: () -> Unit,
    onSettings: () -> Unit,
    onRetry: () -> Unit,
    onRestart: (android.content.Context) -> Unit,
    onBack: () -> Unit,
    disconnected: Boolean = false,
    onDisconnect: () -> Unit = {},
    onConnect: () -> Unit = {}
) {
    Page("Paddock", onBack, trailing = { RoundIconButton(PI.GEAR, "Settings", onSettings) }) {
        val riding = state.riding
        if (riding != null && state.stage == EngineStage.RUNNING) {
            RidingBanner(riding, state.myHash) { onRoom(riding.id) }
            Spacer(Modifier.height(S.gap))
        }
        RiderCard(profile, state, disconnected, onDisconnect)
        Spacer(Modifier.height(S.section))
        if (disconnected) DisconnectedPanel(onConnect) else when (state.stage) {
            EngineStage.RUNNING -> {
                if (!state.online) {
                    WaitingRow(
                        "Reaching the relays…",
                        "No relay answers yet. Paddock keeps trying others: see Settings ▸ Relays. If it lasts, check that the phone is online."
                    )
                    Spacer(Modifier.height(S.gap))
                }
                ActionTile(PI.FLAG, "Open a room", "A group you lead. Your riders join by scanning its code.", primary = true, onClick = onOpenRoom)
                Spacer(Modifier.height(12.dp))
                // Two ways in, side by side and the same height: neither hides behind the other.
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    JoinTile(PI.SCAN, "Scan a QR code", "Point the camera at the room's code on another rider's phone.", Modifier.weight(1f), onScan)
                    JoinTile(PI.LINK, "Paste a link", "Use a room link a rider sent you in a message.", Modifier.weight(1f), onPaste)
                }
                Spacer(Modifier.height(S.section))
                YourRooms(state.rooms, state.myHash, onRoom)
            }
            EngineStage.STARTING, EngineStage.STOPPED -> StartingPanel(state.startStep)
            EngineStage.FAILED -> {
                val context = androidx.compose.ui.platform.LocalContext.current
                FailedPanel(state.error ?: "Paddock could not start.", onRetry) { onRestart(context) }
            }
        }
        Spacer(Modifier.height(S.section))
        Card { NavRow(PI.SHIELD, "Privacy & how it works", "What is shared, with whom, and what the relay sees", onPrivacy) }
    }
}

/** The ride in progress, impossible to miss: a tap opens its room. Unread messages show here too. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun RidingBanner(room: Room, myHash: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(S.cardRadius)
    val last = room.last
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(shape).background(P.okSoft)
            .border(1.dp, P.ok.copy(alpha = 0.45f), shape).clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        LiveDot(P.ok, 22.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Txt("Riding with ${room.name}", Ty.HEADING, MINT_TEXT, maxLines = 1)
            Txt(
                if (room.unread > 0 && last != null)
                    preview(last, myHash) + " · " + (if (room.unread == 1) "1 new message" else "${room.unread} new messages")
                else "Your position goes to this room every 10 s. Tap to open it.",
                Ty.SMALL, P.text2, maxLines = 2
            )
        }
        PIcon(PI.CHEVRON, MINT_TEXT, 20.dp)
    }
}

private val MINT_TEXT get() = P.okText

/** "Luca: Fuel", "You: Tyres booked for Saturday". */
private fun preview(last: io.motohub.paddock.engine.LastMessage, myHash: String): String =
    (if (last.fromHash == myHash) "You" else last.name) + ": " + last.text

/** My rooms in two groups, led and joined, newest activity first (the engine's order). */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun YourRooms(rooms: List<Room>, myHash: String, onRoom: (String) -> Unit) {
    val now = rememberNowSeconds()
    val led = ArrayList<Room>()
    val joined = ArrayList<Room>()
    for (r in rooms) if (r.iAmLeader) led.add(r) else joined.add(r)
    Txt("Your rooms", Ty.TITLE, P.text, Modifier.padding(start = 4.dp, bottom = 14.dp))
    if (rooms.isEmpty()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(P.surfaceHi), contentAlignment = Alignment.Center) {
                PIcon(PI.USERS, P.dim, 28.dp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Txt("No rooms yet", Ty.BODY_STRONG, P.text)
                Txt(
                    "A room is a group you ride with again and again: your Sunday group, track days, a trip to the " +
                        "mountains. Open one and invite your riders, or join one with its QR code. Rooms keep their " +
                        "chat and stay on this phone until you leave them.",
                    Ty.SMALL, P.dim
                )
            }
        }
    } else {
        if (led.isNotEmpty()) RoomGroup("Rooms you lead", led, now, myHash, onRoom)
        if (led.isNotEmpty() && joined.isNotEmpty()) Spacer(Modifier.height(20.dp))
        if (joined.isNotEmpty()) RoomGroup("Rooms you joined", joined, now, myHash, onRoom)
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.padding(top = 2.dp)) { PIcon(PI.MAILBOX, P.ok, 16.dp) }
            Txt(
                "Messages sent while you were away wait, encrypted, in a public mailbox. They arrive when you open Paddock.",
                Ty.SMALL, P.dim, Modifier.weight(1f)
            )
        }
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun RoomGroup(title: String, rooms: List<Room>, now: Long, myHash: String, onRoom: (String) -> Unit) {
    SectionLabel(title)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (i in 0 until rooms.size) {
            val r = rooms[i]
            RoomRow(r, now, myHash) { onRoom(r.id) }
        }
    }
}

/**
 * A room in the list, as its own card: tile, name (with RIDING or JOINING), the last message
 * (bold while unread) or how many riders and how recent, the time and the unread count.
 */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun RoomRow(room: Room, now: Long, myHash: String, onClick: () -> Unit) {
    val tile = roomColor(room.id)
    val shape = RoundedCornerShape(S.cardRadius)
    val last = room.last
    val unread = room.unread > 0
    Row(
        Modifier.fillMaxWidth().heightIn(min = 76.dp).clip(shape).background(P.surface).border(1.dp, P.line, shape)
            .clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(15.dp)).background(tile.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            Txt(initials(room.name), Ty.HEADING, tile, maxLines = 1)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt(room.name, Ty.HEADING, P.text, Modifier.weight(1f, fill = false), maxLines = 1)
                when {
                    room.riding -> Tag("Riding", P.ok, P.okSoft)
                    room.phase == RoomPhase.JOINING -> Tag("Joining…", P.text2, P.surfaceHi)
                }
            }
            if (last != null) {
                Txt(preview(last, myHash), if (unread) Ty.SMALL_STRONG else Ty.SMALL, if (unread) P.text else P.text2, maxLines = 1)
            } else {
                val riders = if (room.members.size == 1) "1 rider" else "${room.members.size} riders"
                val ago = activeAgo(now, room.lastActivity)
                Txt(if (ago.isEmpty()) riders else "$riders · $ago", Ty.SMALL, P.dim, maxLines = 1)
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (last != null) Txt(listTime(last.atMillis), Ty.SMALL, if (unread) ORANGE_TEXT else P.dim, maxLines = 1)
            if (unread) UnreadBadge(if (room.unread > 99) "99+" else room.unread.toString())
            else if (last == null) PIcon(PI.CHEVRON, P.faint, 18.dp)
        }
    }
}

private val ORANGE_TEXT get() = P.accentText

/** The orange count of unread messages. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun UnreadBadge(text: String) {
    Box(
        Modifier.heightIn(min = 22.dp).widthIn(min = 22.dp).clip(RoundedCornerShape(11.dp)).background(P.accent).padding(horizontal = 7.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) { Txt(text, Ty.SMALL_STRONG, P.accentInk, maxLines = 1) }
}

/** Me, as my rooms' riders see me, and whether this phone is private and online. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun RiderCard(profile: Profile, state: PaddockState, disconnected: Boolean, onDisconnect: () -> Unit) {
    val color = riderColor(profile.color)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(S.cardRadius))
            .background(Brush.linearGradient(listOf(color.copy(alpha = 0.22f), P.surface, P.surface)))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Avatar(profile.name, color, 64.dp)
            Column(Modifier.weight(1f)) {
                Txt(profile.name, Ty.TITLE, P.text, maxLines = 1)
                Txt(profile.bike.ifEmpty { "No bike added" }, Ty.SMALL, P.dim, maxLines = 1)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Private · end-to-end encrypted", P.ok, P.okSoft) { PIcon(PI.LOCK, P.ok, 16.dp) }
            RelayPill(state, disconnected)
        }
        // The rider's own off switch: stays off, across MOTO-HUB restarts, until Connect.
        if (!disconnected && (state.stage == EngineStage.RUNNING || state.stage == EngineStage.STARTING)) {
            SecondaryButton("Disconnect", PI.RELAY, modifier = Modifier.fillMaxWidth(), onClick = onDisconnect)
        }
    }
}

/** Disconnected by the rider: nothing connects until Connect. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun DisconnectedPanel(onConnect: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(S.cardRadius)).background(P.surface).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Txt("You are disconnected", Ty.HEADING, P.text)
        Txt(
            "Paddock does not connect, not even when MOTO-HUB starts, until you tap Connect. Your rooms are kept.",
            Ty.SMALL, P.dim
        )
        PrimaryButton("Connect", PI.RELAY, true, Modifier.fillMaxWidth(), onClick = onConnect)
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun RelayPill(state: PaddockState, disconnected: Boolean) {
    val host = relayHost(state.node)
    when {
        disconnected -> Pill("Disconnected", P.text2, P.surfaceHi) { LiveDot(P.text2, 14.dp, pulse = false) }
        state.stage == EngineStage.RUNNING && state.online ->
            Pill("Relay online · $host", P.ok, P.okSoft) { LiveDot(P.ok, 14.dp) }
        state.stage == EngineStage.RUNNING ->
            Pill(if (host.isEmpty()) "Relay offline" else "Relay offline · $host", P.warn, P.warnSoft) { LiveDot(P.warn, 14.dp, pulse = false) }
        state.stage == EngineStage.FAILED ->
            Pill("Not started", P.warn, P.warnSoft) { LiveDot(P.warn, 14.dp, pulse = false) }
        else -> Pill("Starting…", P.text2, P.surfaceHi) { Spinner(14.dp, P.text2) }
    }
}

/** One of the two big things to do here. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun ActionTile(icon: PI, title: String, detail: String, primary: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(S.cardRadius)
    val fg = if (primary) P.accentInk else P.text
    val sub = if (primary) P.accentInk.copy(alpha = 0.72f) else P.dim
    Row(
        Modifier.fillMaxWidth().heightIn(min = 96.dp).clip(shape)
            .background(if (primary) P.accent else P.surfaceHi)
            .then(if (primary) Modifier else Modifier.border(1.dp, P.line, shape))
            .clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(if (primary) Color(0x26000000) else P.accentSoft),
            contentAlignment = Alignment.Center
        ) { PIcon(icon, if (primary) P.accentInk else P.accent, 28.dp) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Txt(title, Ty.HEADING, fg)
            Txt(detail, Ty.SMALL, sub)
        }
        PIcon(PI.CHEVRON, if (primary) P.accentInk else P.faint, 20.dp)
    }
}

/** Half an action tile: icon and arrow on top, then what it does. Both of a pair fill the same height. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun JoinTile(icon: PI, title: String, detail: String, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(S.cardRadius)
    Column(
        modifier.fillMaxHeight().heightIn(min = 132.dp).clip(shape).background(P.surfaceHi)
            .border(1.dp, P.line, shape).clickable(onClick = onClick).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(P.accentSoft), contentAlignment = Alignment.Center) {
                PIcon(icon, P.accent, 24.dp)
            }
            Spacer(Modifier.weight(1f))
            PIcon(PI.CHEVRON, P.faint, 20.dp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Txt(title, Ty.HEADING, P.text)
            Txt(detail, Ty.SMALL, P.dim)
        }
    }
}

/** The engine's start, step by step: done, doing, still to do. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun StartingPanel(step: String) {
    val current = START_STEPS.indexOf(step)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(S.cardRadius)).background(P.surface).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Txt("Getting Paddock ready", Ty.HEADING, P.text)
        Txt("Paddock starts its own encrypted connection on this phone. The first start takes a little longer.", Ty.SMALL, P.dim)
        if (current < 0) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Spinner(22.dp, P.accent)
                Txt(step.ifEmpty { "Getting ready" } + "…", Ty.BODY_STRONG, P.text)
            }
        } else {
            for (i in 0 until START_STEPS.size) {
                Row(Modifier.heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                        when {
                            i < current -> Box(Modifier.size(22.dp).clip(CircleShape).background(P.okSoft), contentAlignment = Alignment.Center) {
                                PIcon(PI.CHECK, P.ok, 14.dp)
                            }
                            i == current -> Spinner(22.dp, P.accent)
                            else -> Box(Modifier.size(8.dp).clip(CircleShape).background(P.faint))
                        }
                    }
                    Txt(
                        START_STEPS[i] + if (i == current) "…" else "",
                        if (i == current) Ty.BODY_STRONG else Ty.BODY,
                        when { i < current -> P.dim; i == current -> P.text; else -> P.faint }
                    )
                }
            }
        }
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun FailedPanel(error: String, onRetry: () -> Unit, onRestart: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(S.cardRadius)).background(P.surface).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(P.warnSoft), contentAlignment = Alignment.Center) {
            PIcon(PI.WARNING, P.warn, 26.dp)
        }
        // The engine's two "close MOTO-HUB and open it again" cases (a restored rider ID, a module
        // reloaded in-process): trying again here cannot help, only a restart of the app.
        val restart = error.contains("open it again")
        Txt(if (restart) "Restart MOTO-HUB to continue" else "Paddock could not start", Ty.HEADING, P.text)
        Txt(error, Ty.SMALL, P.text2)
        Spacer(Modifier.height(4.dp))
        // Swiping MOTO-HUB away does not end it (its foreground service brings it straight back),
        // so the restart is offered here rather than sent to Android's "Force stop".
        if (restart) PrimaryButton("Restart MOTO-HUB", null, true, onClick = onRestart)
        else PrimaryButton("Try again", null, true, onClick = onRetry)
    }
}
