// SPDX-License-Identifier: MPL-2.0
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
// of the MPL was not distributed with this file, you can obtain one at https://mozilla.org/MPL/2.0/.
package io.motohub.paddock.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableTarget
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.motohub.android.module.ModuleUi
import io.motohub.paddock.engine.Freshness
import io.motohub.paddock.engine.Member
import io.motohub.paddock.engine.PaddockController
import io.motohub.paddock.engine.PaddockEvent
import io.motohub.paddock.engine.QuickKind
import io.motohub.paddock.engine.Room
import io.motohub.paddock.engine.RoomPhase
import io.motohub.paddock.qr.QrImage
import kotlinx.coroutines.delay

/**
 * One room, a permanent group. Joining, it says who is being asked. Open, the main thing is the
 * ride: start it and my position goes to this room, and only this room, until I stop. Then the
 * invitation, the riders, and leaving (or, for the leader, deleting the room for everyone).
 * [ridingWith] is the room I ride with now, if any: starting here replaces it.
 */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun RoomScreen(
    controller: PaddockController,
    room: Room,
    ridingWith: Room?,
    myHash: String,
    slot: BackSlot,
    onInvite: () -> Unit,
    onChat: () -> Unit,
    onToast: (ToastMessage) -> Unit,
    onBack: () -> Unit
) {
    var quickOpen by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Member?>(null) }
    val sendQuick: (QuickKind) -> Unit = { kind ->
        controller.sendQuick(room.id, kind)
        quickOpen = false
        onToast(ToastMessage("${kind.label} sent to ${room.name}", quickIcon(kind), quickTint(kind)))
    }
    var asking by remember { mutableStateOf(false) }
    var switching by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(room.name) }
    // Requests show at the next poll: until then, say what is happening instead of the button.
    var leaving by remember { mutableStateOf(false) }
    var pendingRide by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(room.riding) { pendingRide = null }
    LaunchedEffect(controller) {
        controller.events.collect {
            if (it is PaddockEvent.Error) {
                leaving = false
                pendingRide = null
            }
        }
    }
    val start: () -> Unit = {
        pendingRide = true
        controller.startRide(room.id)
    }

    Box(Modifier.fillMaxSize()) {
        Page(
            room.name, onBack,
            trailing = {
                if (room.iAmLeader && room.phase == RoomPhase.ACTIVE) {
                    RoundIconButton(PI.PEN, "Rename room", { draft = room.name; renaming = true })
                }
            }
        ) {
            if (room.phase == RoomPhase.JOINING) Joining(room) { controller.leaveRoom(room.id) }
            else ActiveRoom(
                controller, room, leaving, pendingRide, onInvite,
                onStart = { if (ridingWith != null && ridingWith.id != room.id) switching = true else start() },
                onStop = { pendingRide = false; controller.stopRide() },
                onLeave = { asking = true },
                chat = { ChatCard(room, myHash, onChat) },
                onQuick = sendQuick,
                onMore = { quickOpen = true },
                onRemove = { removing = it }
            )
        }

        Sheet(renaming, slot, "Rename room", { renaming = false }) {
            Field("Room name", draft, { draft = it }, "Sunday group", 40)
            Txt("Every rider in the room sees the new name.", Ty.SMALL, P.dim, Modifier.padding(start = 4.dp, top = 6.dp))
            Spacer(Modifier.height(18.dp))
            PrimaryButton("Save name", PI.CHECK, draft.isNotBlank() && draft.trim() != room.name) {
                controller.renameRoom(room.id, draft.trim())
                renaming = false
            }
        }
        QuickSheet(quickOpen, slot, room.name, sendQuick) { quickOpen = false }
        val target = removing
        ConfirmSheet(
            target != null, slot, PI.USERS, danger = true,
            title = "Remove ${target?.name ?: "rider"} from ${room.name}?",
            body = "The room disappears from their phone, and they stop getting its messages and positions. " +
                "They can come back only with the room's code or link.",
            confirm = "Remove",
            onDismiss = { removing = null },
            onConfirm = {
                if (target != null) controller.removeMember(room.id, target.hash)
                removing = null
            }
        )
        if (ridingWith != null) ConfirmSheet(
            switching, slot, PI.BIKE, danger = false,
            title = "Ride with ${room.name} instead?",
            body = "You are riding with ${ridingWith.name}. Starting this ride stops sharing your position with " +
                "${ridingWith.name}: from now on it goes only to ${room.name}.",
            confirm = "Start ride",
            onDismiss = { switching = false },
            onConfirm = { switching = false; start() }
        )
        if (room.iAmLeader) ConfirmSheet(
            asking, slot, PI.TRASH, danger = true,
            title = "Delete ${room.name} for everyone?",
            body = "The room disappears from every rider's phone, and its code stops working. This cannot be undone.",
            confirm = "Delete room",
            onDismiss = { asking = false },
            onConfirm = { asking = false; leaving = true; controller.leaveRoom(room.id) }
        ) else ConfirmSheet(
            asking, slot, PI.USERS, danger = false,
            title = "Leave ${room.name}?",
            body = "The room disappears from this phone and you stop receiving its riders' positions. To come back " +
                "you need its code or link again, and the leader's phone must be online to let you in.",
            confirm = "Leave room",
            onDismiss = { asking = false },
            onConfirm = { asking = false; leaving = true; controller.leaveRoom(room.id) }
        )
    }
}

/** Asking the leader to let me in. The engine keeps asking, and leaves the request in the mailbox. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun Joining(room: Room, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CallingRings(P.accent, Modifier.size(190.dp)) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(P.accentSoft), contentAlignment = Alignment.Center) {
                PIcon(PI.FLAG, P.accent, 36.dp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Txt("Asking to join", Ty.LABEL, P.accent)
        Spacer(Modifier.height(8.dp))
        Txt("Asking the leader of ${room.name} to let you in…", Ty.TITLE, P.text, align = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Txt(
            "If the leader's MOTO-HUB is closed, you are let in as soon as they open it. You can leave this " +
                "screen: a notification tells you when you are in.",
            Ty.BODY, P.dim, align = TextAlign.Center
        )
        Spacer(Modifier.height(S.section))
        SecondaryButton("Cancel", PI.CLOSE, onClick = onCancel)
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun ActiveRoom(
    controller: PaddockController,
    room: Room,
    leaving: Boolean,
    pendingRide: Boolean?,
    onInvite: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onLeave: () -> Unit,
    chat: @Composable @ComposableTarget(ModuleUi.UI_APPLIER) () -> Unit,
    onQuick: (QuickKind) -> Unit,
    onMore: () -> Unit,
    onRemove: (Member) -> Unit
) {
    val context = LocalContext.current
    val now = rememberNowSeconds()
    // Leader first, then me, then the others as the engine lists them.
    val ordered = ArrayList<Member>(room.members.size)
    for (m in room.members) if (m.isLeader) ordered.add(m)
    for (m in room.members) if (!m.isLeader && m.isMe) ordered.add(m)
    for (m in room.members) if (!m.isLeader && !m.isMe) ordered.add(m)
    var others = 0
    var located = 0
    var leaderName = ""
    for (m in room.members) {
        if (!m.isMe) others++
        if (!m.isMe && m.lat != null) located++
        if (m.isLeader) leaderName = m.name
    }
    // A leader alone sees the code first. Kept for this visit: it must not fold away while a
    // second rider is still scanning it.
    val codeFirst by remember { mutableStateOf(room.iAmLeader && others == 0) }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (room.iAmLeader) Pill("You lead this room", P.accent, P.accentSoft) { PIcon(PI.CROWN, P.accent, 16.dp) }
        else Pill(if (leaderName.isEmpty()) "Member" else "Led by $leaderName", P.text2, P.surfaceHi) {
            PIcon(PI.CROWN, P.text2, 16.dp)
        }
        Txt(if (room.members.size == 1) "1 rider" else "${room.members.size} riders", Ty.SMALL, P.dim, maxLines = 1)
    }
    Spacer(Modifier.height(20.dp))

    if (codeFirst) {
        RoomCode(controller, room)
        Spacer(Modifier.height(S.section))
    }

    when {
        pendingRide == true -> WaitingRow("Starting the ride…", "Paddock starts sending your position to this room.")
        pendingRide == false -> WaitingRow("Stopping the ride…", "Your position stops going to this room.")
        room.riding -> {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(S.cardRadius)).background(P.okSoft).padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                LiveDot(P.ok, 22.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Txt("Riding with this room", Ty.HEADING, P.ok)
                    Txt("Your position is shared every 10 s, also when this page is closed.", Ty.SMALL, P.text2)
                }
            }
            BackgroundLocationNotice()
            Spacer(Modifier.height(12.dp))
            SecondaryButton("Stop ride", PI.CLOSE, onClick = onStop)
        }
        else -> PrimaryButton("Start ride with this room", PI.BIKE, true, onClick = onStart)
    }
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PIcon(PI.LOCK, P.ok, 20.dp)
        Txt(
            "Your position goes only to this room, end-to-end encrypted, and only while you ride with it. Other " +
                "rooms get nothing, and Paddock does not use the GPS when you are not riding.",
            Ty.SMALL, P.text2, Modifier.weight(1f)
        )
    }
    Spacer(Modifier.height(S.section))

    chat()
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        QuickShortcut(quickIcon(QuickKind.STOP), quickTint(QuickKind.STOP), QuickKind.STOP.label, Modifier.weight(1f)) { onQuick(QuickKind.STOP) }
        QuickShortcut(quickIcon(QuickKind.FUEL), quickTint(QuickKind.FUEL), QuickKind.FUEL.label, Modifier.weight(1f)) { onQuick(QuickKind.FUEL) }
        QuickShortcut(PI.DOTS, P.text2, "More", Modifier.weight(1f), onMore)
    }
    Spacer(Modifier.height(S.section))

    if (!codeFirst) {
        Card {
            NavRow(
                PI.QR, "Invite riders",
                if (room.iAmLeader) "Show the room's QR code or share its link"
                else "Share the room's code. The leader's phone must be online to let them in.",
                onInvite
            )
        }
        Spacer(Modifier.height(S.section))
    }

    SectionLabel("Riders")
    Column {
        for (i in 0 until ordered.size) {
            if (i > 0) Hairline(start = 72.dp)
            val m = ordered[i]
            MemberRow(
                m, now, room.riding, if (room.iAmLeader && !m.isMe) ({ onRemove(m) }) else null,
                if (!m.isMe) ({ if (micReady(context)) controller.callRider(room.id, m.hash) }) else null
            )
        }
    }
    if (others == 0 && !room.iAmLeader) Txt("The others appear here as they join.", Ty.SMALL, P.dim)
    Spacer(Modifier.height(16.dp))
    SecondaryButton("Show riders on the map", PI.MAP, enabled = located > 0) { controller.showOnMap(room.id, null) }
    if (located == 0) Txt(
        "Riders appear on the NAV map, in the Paddock riders layer, when they ride with this room.",
        Ty.SMALL, P.dim, Modifier.fillMaxWidth().padding(top = 8.dp), align = TextAlign.Center
    )

    Spacer(Modifier.height(36.dp))
    when {
        leaving && room.iAmLeader -> WaitingRow("Deleting the room…", "Telling every rider that the room is deleted.")
        leaving -> WaitingRow("Leaving the room…", "Telling the leader that you are leaving.")
        room.iAmLeader -> DangerButton("Delete room for everyone", PI.TRASH, filled = false, onClick = onLeave)
        else -> SecondaryButton("Leave room", PI.CLOSE, onClick = onLeave)
    }
}

/** One rider: who, on what, and how fresh their position is. For reading, not tapping. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun MemberRow(m: Member, now: Long, iRide: Boolean, onRemove: (() -> Unit)?, onCall: (() -> Unit)?) {
    val color = riderColor(m.color)
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        // 58 dp frame round a 48 dp avatar: the badge's centre sits on the avatar's rim, half of it
        // outside, so it never covers the initials.
        Box(Modifier.size(58.dp)) {
            Box(Modifier.align(Alignment.Center)) { Avatar(m.name, color, 48.dp) }
            if (m.isLeader) Box(
                Modifier.align(Alignment.TopEnd).size(24.dp).clip(CircleShape).background(P.bg).padding(3.dp)
                    .clip(CircleShape).background(P.accent),
                contentAlignment = Alignment.Center
            ) { PIcon(PI.CROWN, P.accentInk, 12.dp) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt(m.name, Ty.BODY_STRONG, P.text, Modifier.weight(1f, fill = false), maxLines = 1)
                if (m.isMe) Tag("You", P.text2, P.surfaceHi)
                if (m.isLeader) Tag("Leader", P.accent, P.accentSoft)
            }
            if (m.bike.isNotEmpty()) Txt(m.bike, Ty.SMALL, P.dim, maxLines = 1)
        }
        FreshnessLabel(m, now, iRide)
        if (onCall != null) RoundIconButton(PI.CALL, "Call ${m.name}", onCall, tint = P.ok, bg = P.ok.copy(alpha = 0.14f), outlined = false)
        // Leader only: the way to remove this rider, behind its own 48 dp button and a confirmation.
        if (onRemove != null) RoundIconButton(PI.MORE_VERT, "Remove ${m.name} from the room", onRemove, tint = P.dim, bg = Color.Transparent, outlined = false)
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun FreshnessLabel(m: Member, now: Long, iRide: Boolean) {
    Column(Modifier.widthIn(max = 120.dp), horizontalAlignment = Alignment.End) {
        val fix = m.fixAt
        when {
            // My own row says whether this phone sends to this room, not a position received.
            m.isMe && iRide -> Txt("Sharing", Ty.SMALL_STRONG, P.ok, maxLines = 1)
            m.isMe -> Txt("Not riding", Ty.SMALL, P.dim, maxLines = 1)
            m.freshness(now) == Freshness.FRESH -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LiveDot(P.ok, 14.dp)
                Txt("Live", Ty.SMALL_STRONG, P.ok)
            }
            !isOnline(m, now) -> Txt("Offline · gets messages later", Ty.SMALL, P.dim, align = TextAlign.End, maxLines = 2)
            fix == null -> Txt("Online", Ty.SMALL, P.text2, maxLines = 1)
            else -> {
                Txt("Last seen", Ty.SMALL, P.dim, maxLines = 1)
                Txt(ago(now - fix), Ty.SMALL_STRONG, P.text2, maxLines = 1)
            }
        }
    }
}

private fun ago(seconds: Long): String {
    val minutes = if (seconds < 60) 1 else seconds / 60
    return when {
        minutes < 60 -> "$minutes min ago"
        minutes < 48 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (24 * 60)} days ago"
    }
}

/** The room's code, as big as the screen allows, on white with a quiet zone, and its link. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun RoomCode(controller: PaddockController, room: Room) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        // QrImage paints only its own box: the white padding around it is the quiet zone scanners need.
        Box(
            Modifier.widthIn(max = 340.dp).fillMaxWidth(0.88f).aspectRatio(1f).clip(RoundedCornerShape(28.dp))
                .background(Color.White).padding(20.dp)
        ) {
            QrImage(room.link, Modifier.fillMaxSize(), P.ink, Color.White)
        }
        Spacer(Modifier.height(18.dp))
        Txt(
            if (room.iAmLeader) "Show this to your riders. Anyone with this code can join."
            else "Show this to a rider you want in the room. Anyone with this code can ask to join.",
            Ty.BODY_STRONG, P.text, align = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Txt(
            if (room.iAmLeader) "Your phone lets them in, so it must be online when they scan."
            else "The leader lets them in, so the leader's phone must be online when they scan.",
            Ty.SMALL, P.dim, align = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        SecondaryButton("Share link", PI.SHARE) { controller.shareRoomLink(context, room.id) }
        Spacer(Modifier.height(8.dp))
        Txt(
            "For a rider who is not with you: send the link in a private message.",
            Ty.SMALL, P.dim, align = TextAlign.Center
        )
    }
}

/** The room's code on its own screen, for riders who arrive later. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun InviteScreen(controller: PaddockController, room: Room, onBack: () -> Unit) {
    Page("Invite riders", onBack) {
        Spacer(Modifier.height(8.dp))
        RoomCode(controller, room)
    }
}

/**
 * The room's chat on its page: unread count and the latest message, a tap opens the chat.
 * Only the latest message is known without opening the chat (opening it marks it read).
 */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun ChatCard(room: Room, myHash: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(S.cardRadius)
    val last = room.last
    Column(
        Modifier.fillMaxWidth().clip(shape).background(P.surface).border(1.dp, P.line, shape).clickable(onClick = onClick).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(P.accentSoft), contentAlignment = Alignment.Center) {
                PIcon(PI.CHAT, P.accentText, 22.dp)
            }
            Txt("Chat", Ty.HEADING, P.text, Modifier.weight(1f))
            if (room.unread > 0) UnreadBadge("${room.unread} new")
            PIcon(PI.CHEVRON, P.dim, 18.dp)
        }
        Column(Modifier.padding(start = 50.dp)) {
            if (last == null) {
                Txt("No messages yet. Say hello, or send a quick message while you ride.", Ty.SMALL, P.dim)
            } else {
                var color = P.text2
                for (m in room.members) if (m.hash == last.fromHash) color = riderColor(m.color)
                val mine = last.fromHash == myHash
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Txt(if (mine) "You" else last.name, Ty.SMALL_STRONG, if (mine) P.accent else color, maxLines = 1)
                    Txt(
                        last.text, if (room.unread > 0) Ty.SMALL_STRONG else Ty.SMALL, if (room.unread > 0) P.text else P.text2,
                        Modifier.weight(1f), maxLines = 2
                    )
                }
            }
        }
    }
}

/** One of the three quick buttons on the room page: big enough for a glove. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun QuickShortcut(icon: PI, tint: Color, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.height(72.dp).clip(RoundedCornerShape(18.dp)).background(P.surfaceHi).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)
    ) {
        PIcon(icon, tint, 22.dp)
        Txt(label, Ty.SMALL_STRONG, P.text, maxLines = 1)
    }
}

/**
 * While riding: without "All the time" Android stops giving MOTO-HUB the position once the phone is locked or another app
 * is in front, and the room stops seeing the rider. Said here, with the button that asks Android; gone once allowed.
 */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun BackgroundLocationNotice() {
    val context = LocalContext.current
    fun allowed() = context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    var missing by remember { mutableStateOf(!allowed()) }
    // The answer is given on Android's own page: read again while this is on screen.
    LaunchedEffect(Unit) {
        while (missing) { delay(1_500); missing = !allowed() }
    }
    if (!missing) return
    Spacer(Modifier.height(12.dp))
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(S.cardRadius)).background(P.warnSoft).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Txt("Your position stops when the phone is locked", Ty.HEADING, P.warn)
        Txt(
            "Android gives MOTO-HUB your location only while the app is open. Allow location \"All the time\" so the " +
                "room keeps seeing you with the phone in your pocket.",
            Ty.SMALL, P.text2
        )
        SecondaryButton("Allow all the time", PI.LOCK) {
            context.findActivity()?.requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 4713)
        }
    }
}

private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
