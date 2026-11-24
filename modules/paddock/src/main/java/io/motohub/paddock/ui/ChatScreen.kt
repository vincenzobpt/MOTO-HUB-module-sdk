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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableTarget
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.motohub.android.module.ModuleUi
import io.motohub.paddock.engine.ChatKind
import io.motohub.paddock.engine.ChatMessage
import io.motohub.paddock.engine.Delivery
import io.motohub.paddock.engine.Member
import io.motohub.paddock.engine.PaddockController
import io.motohub.paddock.engine.QuickKind
import io.motohub.paddock.engine.Room

/** One line of the chat list: a day separator or a message. [key] keeps the list steady when older ones load. */
private class ChatRow(val key: String, val day: String?, val message: ChatMessage?)

/**
 * A room's chat: who is there, whether it is encrypted and riding, the messages newest at the
 * bottom, and a composer with the quick messages one tap away. Opening it marks the room read
 * (openChat), and scrolling to the top loads older messages.
 */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun ChatScreen(controller: PaddockController, room: Room, slot: BackSlot, onToast: (ToastMessage) -> Unit, onBack: () -> Unit) {
    DisposableEffect(room.id) {
        controller.openChat(room.id)
        onDispose { controller.closeChat() }
    }
    val chat by controller.chat.collectAsState()
    val messages = if (chat.roomId == room.id) chat.messages else emptyList()
    val now = rememberNowSeconds()
    var draft by remember { mutableStateOf("") }
    var quick by remember { mutableStateOf(false) }

    val rows = ArrayList<ChatRow>(messages.size + 8)
    for (i in 0 until messages.size) {
        val m = messages[i]
        if (i == 0 || !sameDay(messages[i - 1].atMillis, m.atMillis)) rows.add(ChatRow("day-" + m.id, dayLabel(m.atMillis), null))
        rows.add(ChatRow(m.id, null, m))
    }

    val list = rememberLazyListState()
    var settled by remember { mutableStateOf(false) }
    val lastId = if (messages.isEmpty()) "" else messages[messages.size - 1].id
    LaunchedEffect(lastId) {
        if (rows.isNotEmpty()) {
            list.animateScrollToItem(rows.size - 1)
            settled = true
        }
    }
    // At the top, after the first scroll to the bottom: ask for the page before.
    val atTop = settled && rows.isNotEmpty() && list.firstVisibleItemIndex == 0
    LaunchedEffect(atTop) {
        if (atTop && !chat.loading) controller.loadEarlierChat()
    }

    var online = 0
    for (m in room.members) if (m.isMe || isOnline(m, now)) online++

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().keyboardPadding()) {
            ChatHeader(room, online, onBack)
            if (room.riding) {
                Row(
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(P.okSoft)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    PIcon(PI.LOCK, MINT, 16.dp)
                    Txt("Riding with this room · end-to-end encrypted", Ty.SMALL, MINT, maxLines = 1)
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    PIcon(PI.LOCK, P.dim, 14.dp)
                    Spacer(Modifier.width(6.dp))
                    Txt("End-to-end encrypted", Ty.SMALL, P.dim)
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (messages.isEmpty() && !chat.loading) EmptyChat(room.name)
                else LazyColumn(
                    Modifier.fillMaxSize(),
                    state = list,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    if (chat.loading) item { LoadingEarlier() }
                    itemsIndexed(rows, key = { _, r -> r.key }) { _, r ->
                        val m = r.message
                        if (r.day != null) {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Txt(r.day, Ty.LABEL, P.dim)
                            }
                        } else if (m != null) {
                            MessageRow(controller, room, m)
                        }
                    }
                }
            }

            // Opened from a push: the message is on its way from the sender's phone.
            val fetching by io.motohub.paddock.engine.Push.fetching.collectAsState()
            fetching[room.id]?.takeIf { now * 1000L - it.second < io.motohub.paddock.engine.Push.FETCH_SHOWN_MILLIS }?.let { (name, _) ->
                WaitingRow("Fetching the new message…", "From $name's phone", Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            }

            ChatComposer(room.name, draft, { draft = it }, onQuick = { quick = true }) {
                val text = draft.trim()
                if (text.isNotEmpty()) {
                    controller.sendText(room.id, text)
                    draft = ""
                }
            }
        }
        QuickSheet(quick, slot, room.name, onSend = { kind ->
            controller.sendQuick(room.id, kind)
            quick = false
            onToast(ToastMessage("${kind.label} sent to ${room.name}", quickIcon(kind), quickTint(kind)))
        }, onDismiss = { quick = false })
    }
}

private val MINT get() = P.okText

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun ChatHeader(room: Room, online: Int, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        RoundIconButton(PI.BACK, "Back", onBack)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Txt(room.name, Ty.HEADING, P.text, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (online > 1) P.ok else P.faint))
                val riders = if (room.members.size == 1) "1 rider" else "${room.members.size} riders"
                Txt("$riders · $online online", Ty.SMALL, P.dim, maxLines = 1)
            }
        }
        AvatarStack(room.members)
    }
}

/** Up to three faces, overlapping, each ringed in the page colour. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun AvatarStack(members: List<Member>) {
    val n = if (members.size < 3) members.size else 3
    if (n == 0) return
    Box(Modifier.width((30 + 20 * (n - 1)).dp).height(30.dp)) {
        for (i in 0 until n) {
            Box(Modifier.padding(start = (20 * i).dp).size(30.dp).clip(CircleShape).background(P.bg).padding(2.dp)) {
                Avatar(members[i].name, riderColor(members[i].color), 26.dp)
            }
        }
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun EmptyChat(roomName: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(P.accentSoft), contentAlignment = Alignment.Center) {
            PIcon(PI.CHAT, P.accent, 32.dp)
        }
        Spacer(Modifier.height(16.dp))
        Txt("No messages yet", Ty.HEADING, P.text, align = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Txt(
            "Messages go only to the riders of $roomName, end-to-end encrypted. A rider who is offline gets them when their phone is back.",
            Ty.SMALL, P.dim, align = TextAlign.Center
        )
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun LoadingEarlier() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Spinner(16.dp, P.dim)
        Spacer(Modifier.width(8.dp))
        Txt("Loading earlier messages…", Ty.SMALL, P.dim)
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun MessageRow(controller: PaddockController, room: Room, m: ChatMessage) {
    when {
        m.kind == ChatKind.EVENT -> Row(
            Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(P.faint))
            Spacer(Modifier.width(8.dp))
            Txt(m.text, Ty.SMALL, P.dim, align = TextAlign.Center)
        }
        m.kind == ChatKind.QUICK && m.quick != null -> QuickMessage(controller, room, m, m.quick)
        m.mine -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(
                Modifier.widthIn(max = 280.dp).clip(RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp)).background(P.accent)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) { Txt(m.text, Ty.BODY_STRONG, P.accentInk) }
            DeliveryLine(m)
        }
        else -> Incoming(m, "") {
            Box(
                Modifier.widthIn(max = 260.dp).clip(RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp)).background(P.surfaceHi)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) { Txt(m.text, Ty.BODY, P.text) }
        }
    }
}

/** Someone else's message: their face, their coloured name, the content, the time. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun Incoming(m: ChatMessage, nameSuffix: String, content: @Composable @ComposableTarget(ModuleUi.UI_APPLIER) () -> Unit) {
    val color = riderColor(m.color)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.padding(bottom = 18.dp)) { Avatar(m.name, color, 30.dp) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Txt(m.name + nameSuffix, Ty.LABEL, color, maxLines = 1)
            content()
            Txt(clockTime(m.atMillis), Ty.SMALL, P.faint)
        }
    }
}

/** A quick message as the big card in its kind's colour, with "Show on map" when it carries a point. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun QuickMessage(controller: PaddockController, room: Room, m: ChatMessage, kind: QuickKind) {
    val tint = quickTint(kind)
    var sender: Member? = null
    for (x in room.members) if (x.hash == m.fromHash) sender = x
    val metres = if (!m.mine && sender != null) controller.distanceTo(sender) else null
    val subtitle = quickSentence(kind, m.name, m.mine) + if (metres != null) " · ${distanceText(metres)} from you" else ""
    val lat = m.lat
    val lon = m.lon
    val card: @Composable @ComposableTarget(ModuleUi.UI_APPLIER) () -> Unit = {
        val shape = RoundedCornerShape(18.dp)
        Column(
            Modifier.fillMaxWidth().clip(shape).background(tint.copy(alpha = 0.14f)).border(1.dp, tint.copy(alpha = 0.45f), shape)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(tint), contentAlignment = Alignment.Center) {
                    PIcon(quickIcon(kind), P.ink, 24.dp)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Txt(kind.label, Ty.TITLE, tint, maxLines = 1)
                    Txt(subtitle, Ty.SMALL, P.text2)
                }
            }
            if ((kind == QuickKind.SPOT || kind == QuickKind.EMERGENCY) && lat != null && lon != null) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp)).background(tint.copy(alpha = 0.22f))
                        .clickable { controller.showPoint(lat, lon, if (m.mine) "My spot" else m.name) }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    PIcon(PI.MAP, tint, 20.dp)
                    Spacer(Modifier.width(10.dp))
                    Txt("Show on map", Ty.BODY_STRONG, tint)
                }
            }
        }
    }
    if (m.mine) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Txt("You · quick message", Ty.LABEL, P.accent, maxLines = 1)
            card()
            DeliveryLine(m)
        }
    } else {
        Incoming(m, " · quick message", card)
    }
}

/**
 * Under my own messages: the time, how delivery went, and who has it and who not yet, by name.
 * Every line wraps and ends at the right edge: with a large font nothing is cut off.
 */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun DeliveryLine(m: ChatMessage) {
    val d: Delivery? = m.delivery
    Column(Modifier.widthIn(max = 320.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Txt(clockTime(m.atMillis), Ty.SMALL, P.dim)
            when {
                d == null -> {}
                d.total == 0 -> Txt("No other rider in the room yet", Ty.SMALL, P.dim, align = TextAlign.End)
                d.delivered >= d.total -> {
                    PIcon(PI.DOUBLE_CHECK, P.ok, 16.dp)
                    Txt(if (d.total == 1) "Delivered" else "Delivered to everyone", Ty.SMALL, P.ok, align = TextAlign.End)
                }
                d.delivered == 0 && d.failed == 0 && d.waiting == 0 && d.mailbox == 0 -> {
                    Spinner(12.dp, P.dim)
                    Txt("Sending…", Ty.SMALL, P.dim)
                }
                else -> Txt("Delivered to ${d.delivered} of ${d.total}", Ty.SMALL, P.text2, align = TextAlign.End)
            }
        }
        if (d != null && d.who.isNotEmpty()) {
            fun names(vararg states: String) = d.who.filter { it.second in states }.map { it.first }
            DeliveryNames(PI.DOUBLE_CHECK, P.ok, "Delivered", names("delivered"))
            // Waiting or in a mailbox are the same to the rider: their phone is off or out of
            // reach, and Paddock sends it again as soon as it hears them.
            DeliveryNames(PI.CLOCK, P.warn, "Offline, sent when they are back", names("waiting", "mailbox"))
            DeliveryNames(null, P.dim, "Sending", names("pending"))
            DeliveryNames(PI.WARNING, P.dangerText, "Not delivered, left the room", names("failed"))
        }
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun DeliveryNames(icon: PI?, tint: Color, label: String, names: List<String>) {
    if (names.isEmpty()) return
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (icon != null) Box(Modifier.padding(top = 2.dp)) { PIcon(icon, tint, 13.dp) }
        Txt("$label: ${names.joinToString(", ")}", Ty.SMALL, tint, align = TextAlign.End)
    }
}

/** Quick messages, the text, send. 48 dp round controls, bigger than they look. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun ChatComposer(roomName: String, draft: String, onDraft: (String) -> Unit, onQuick: () -> Unit, onSend: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(P.surfaceHi))
    Row(
        Modifier.fillMaxWidth().background(P.surface).padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RoundIconButton(PI.BOLT, "Quick messages", onQuick, tint = P.accentText, bg = P.accentSoft, outlined = false)
        val shape = RoundedCornerShape(24.dp)
        Box(
            Modifier.weight(1f).heightIn(min = 48.dp).clip(shape).background(P.surface).border(1.dp, P.line, shape)
                .padding(horizontal = 18.dp, vertical = 13.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (draft.isEmpty()) Txt("Message $roomName", Ty.BODY, P.faint, maxLines = 1)
            BasicTextField(
                value = draft,
                onValueChange = { v -> onDraft(if (v.length > 2000) v.substring(0, 2000) else v) },
                maxLines = 4,
                textStyle = TextStyle(color = P.text, fontSize = 16.sp),
                cursorBrush = SolidColor(P.accent),
                modifier = Modifier.fillMaxWidth()
            )
        }
        val ready = draft.isNotBlank()
        Box(
            Modifier.size(S.touch).clip(CircleShape).background(if (ready) P.accent else P.line)
                .clickable(enabled = ready, onClick = onSend).semantics { contentDescription = "Send" },
            contentAlignment = Alignment.Center
        ) { PIcon(PI.SEND, if (ready) P.accentInk else P.dim, 22.dp) }
    }
}
