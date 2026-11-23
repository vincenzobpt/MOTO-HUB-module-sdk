// SPDX-License-Identifier: MPL-2.0
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
// of the MPL was not distributed with this file, you can obtain one at https://mozilla.org/MPL/2.0/.
package io.motohub.paddock.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableTarget
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.motohub.android.module.ModuleUi
import io.motohub.paddock.engine.PaddockEvent
import kotlin.math.sin

/** Pages are read on phones and tablets alike: content never runs wider than this. */
internal val MAX_W = 600.dp

/**
 * Where an open sheet puts its "close me": the system back closes the sheet first, then the
 * screen. The app routes back to [PaddockApp] through Backdrop, so the module answers it itself.
 */
internal class BackSlot {
    var handler: (() -> Unit)? = null
}

/** A screen fades and rises a little as it opens. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun Enter(content: @Composable @ComposableTarget(ModuleUi.UI_APPLIER) () -> Unit) {
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, tween(280)) }
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = shown.value; translationY = (1f - shown.value) * 14.dp.toPx() }) {
        content()
    }
}

/**
 * The frame of every portrait screen: back (or close) button and title on top, a scrolling body,
 * and an optional fixed bottom area for the screen's one main action. Clear of the system bars.
 */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun Page(
    title: String,
    onBack: () -> Unit,
    backIcon: PI = PI.BACK,
    trailing: (@Composable @ComposableTarget(ModuleUi.UI_APPLIER) () -> Unit)? = null,
    bottom: (@Composable @ComposableTarget(ModuleUi.UI_APPLIER) ColumnScope.() -> Unit)? = null,
    body: @Composable @ComposableTarget(ModuleUi.UI_APPLIER) ColumnScope.() -> Unit
) {
    // imePadding: the keyboard must not cover the page's bottom action (the profile's Save).
    Column(Modifier.fillMaxSize().statusBarsPadding().keyboardPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier.widthIn(max = MAX_W).fillMaxWidth().height(68.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            RoundIconButton(backIcon, if (backIcon == PI.CLOSE) "Close" else "Back", onBack)
            Txt(title, Ty.TITLE, P.text, Modifier.weight(1f), maxLines = 1)
            if (trailing != null) trailing()
        }
        Column(
            Modifier.weight(1f).widthIn(max = MAX_W).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = S.gutter).padding(top = 8.dp, bottom = 24.dp)
        ) { body() }
        if (bottom != null) Column(
            Modifier.widthIn(max = MAX_W).fillMaxWidth().padding(horizontal = S.gutter).padding(top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) { bottom() }
    }
}

/** A round 48 dp button with one icon; [label] is what a screen reader says. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun RoundIconButton(icon: PI, label: String, onClick: () -> Unit, tint: Color = P.text, bg: Color = P.surface, outlined: Boolean = true) {
    Box(
        Modifier.size(S.touch).clip(CircleShape).background(bg)
            .then(if (outlined) Modifier.border(1.dp, P.line, CircleShape) else Modifier)
            .clickable(onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) { PIcon(icon, tint, 22.dp) }
}

/** The screen's main action: full width, 58 dp, the orange. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun PrimaryButton(text: String, icon: PI?, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val fg = if (enabled) P.accentInk else P.dim
    ButtonFace(text, icon, fg, modifier.clip(RoundedCornerShape(S.controlRadius)).background(if (enabled) P.accent else P.line)
        .clickable(enabled = enabled, onClick = onClick))
}

/** A second choice next to the main one: dark, outlined. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun SecondaryButton(text: String, icon: PI?, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(S.controlRadius)
    ButtonFace(text, icon, if (enabled) P.text else P.faint, modifier.clip(shape).background(P.surfaceHi).border(1.dp, P.line, shape)
        .clickable(enabled = enabled, onClick = onClick))
}

/** Only for what cannot be undone: filled red in a confirmation, outlined red on a page. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun DangerButton(text: String, icon: PI?, filled: Boolean, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(S.controlRadius)
    val face = if (filled) modifier.clip(shape).background(if (enabled) P.danger else P.line)
    else modifier.clip(shape).background(P.dangerSoft).border(1.dp, P.danger.copy(alpha = if (enabled) 0.55f else 0.2f), shape)
    val fg = when { !enabled -> P.faint; filled -> Color.White; else -> P.dangerText }
    ButtonFace(text, icon, fg, face.clickable(enabled = enabled, onClick = onClick))
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun ButtonFace(text: String, icon: PI?, fg: Color, modifier: Modifier) {
    Row(
        modifier.fillMaxWidth().height(58.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            PIcon(icon, fg, 22.dp)
            Spacer(Modifier.width(10.dp))
        }
        Txt(text, Ty.BUTTON, fg, maxLines = 1)
    }
}

/** A quiet action in words: orange text, 48 dp tall. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun TextLink(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.heightIn(min = S.touch).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) { Txt(text, Ty.BODY_STRONG, P.accent) }
}

/** A status, not a control: soft fill, no border, an optional leading mark. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun Pill(text: String, fg: Color, bg: Color, lead: (@Composable @ComposableTarget(ModuleUi.UI_APPLIER) () -> Unit)? = null) {
    Row(
        Modifier.heightIn(min = 32.dp).clip(RoundedCornerShape(16.dp)).background(bg).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (lead != null) lead()
        Txt(text, Ty.SMALL_STRONG, fg, maxLines = 1)
    }
}

/** A small word next to a name: YOU, LEADER. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun Tag(text: String, fg: Color, bg: Color) {
    Box(Modifier.clip(RoundedCornerShape(7.dp)).background(bg).padding(horizontal = 7.dp, vertical = 2.dp)) {
        Txt(text.uppercase(), Ty.LABEL, fg, maxLines = 1)
    }
}

/** A rider: their colour, their initials. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun Avatar(name: String, color: Color, size: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Text(initials(name), color = inkOn(color), fontSize = (size.value * 0.38f).sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** A dot that breathes while something is live. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun LiveDot(color: Color, size: Dp = 14.dp, pulse: Boolean = true) {
    val clock = if (pulse) rememberSeconds() else null
    Canvas(Modifier.size(size)) {
        val r = this.size.width / 2f
        if (clock != null) {
            val f = (clock.floatValue / 1.6f) % 1f
            drawCircle(color.copy(alpha = 0.5f * (1f - f)), r * (0.45f + 0.55f * f), center)
        }
        drawCircle(color, r * 0.42f, center)
    }
}

/** The module's own progress ring. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun Spinner(size: Dp, color: Color) {
    val clock = rememberSeconds()
    Canvas(Modifier.size(size)) {
        val t = clock.floatValue
        val sw = this.size.width * 0.12f
        val tl = Offset(sw / 2, sw / 2)
        val arc = Size(this.size.width - sw, this.size.height - sw)
        drawArc(color.copy(alpha = 0.18f), 0f, 360f, false, tl, arc, style = Stroke(width = sw))
        val sweep = 70f + 110f * (0.5f + 0.5f * sin(t * 2.4f))
        drawArc(color, (t * 300f) % 360f, sweep, false, tl, arc, style = Stroke(width = sw, cap = StrokeCap.Round))
    }
}

/** Something is happening: a spinner and the plain sentence of what. Shown the moment it starts. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun WaitingRow(text: String, detail: String?, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(S.controlRadius)).background(P.surface).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(Modifier.padding(top = 1.dp)) { Spinner(24.dp, P.accent) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Txt(text, Ty.BODY_STRONG, P.text)
            if (detail != null) Txt(detail, Ty.SMALL, P.dim)
        }
    }
}

/** A sentence that needs attention, in a soft tinted panel. Not a control. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun Notice(icon: PI, tone: Color, soft: Color, text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(soft).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PIcon(icon, tone, 20.dp)
        Txt(text, Ty.SMALL, P.text, Modifier.weight(1f))
    }
}

/** An icon in a soft disc, a title and a short paragraph. For reading, not tapping. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun InfoBlock(icon: PI, tint: Color, soft: Color, title: String, body: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(soft), contentAlignment = Alignment.Center) { PIcon(icon, tint, 22.dp) }
        Column(Modifier.weight(1f).padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Txt(title, Ty.BODY_STRONG, P.text)
            Txt(body, Ty.SMALL, P.dim)
        }
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Txt(text.uppercase(), Ty.LABEL, P.dim, modifier.padding(start = 4.dp, bottom = 10.dp))
}

/** A card that holds tappable rows. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun Card(content: @Composable @ComposableTarget(ModuleUi.UI_APPLIER) ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(S.cardRadius)
    Column(Modifier.fillMaxWidth().clip(shape).background(P.surface).border(1.dp, P.line, shape)) { content() }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun Hairline(start: Dp = 0.dp) {
    Box(Modifier.fillMaxWidth().padding(start = start).height(1.dp).background(P.line))
}

/** A row that opens something: icon, title, a line saying what is behind it, a chevron. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun NavRow(icon: PI, title: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(P.accentSoft), contentAlignment = Alignment.Center) {
            PIcon(icon, P.accent, 22.dp)
        }
        Column(Modifier.weight(1f)) {
            Txt(title, Ty.BODY_STRONG, P.text, maxLines = 1)
            if (detail.isNotEmpty()) Txt(detail, Ty.SMALL, P.dim, maxLines = 2)
        }
        PIcon(PI.CHEVRON, P.faint, 18.dp)
    }
}

/** A labelled one-line text field; the border turns orange while typing. Cut at [maxLength]. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun Field(label: String, value: String, onChange: (String) -> Unit, placeholder: String, maxLength: Int, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.fillMaxWidth()) {
        Txt(label.uppercase(), Ty.LABEL, P.dim, Modifier.padding(start = 4.dp, bottom = 8.dp))
        Box(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(shape).background(P.surfaceHi)
                .border(1.5.dp, if (focused) P.accent else P.line, shape).padding(horizontal = 16.dp, vertical = 15.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (value.isEmpty()) Txt(placeholder, Ty.BODY, P.faint, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = { v -> onChange(if (v.length > maxLength) v.substring(0, maxLength) else v) },
                singleLine = true,
                textStyle = TextStyle(color = P.text, fontSize = 17.sp),
                cursorBrush = SolidColor(P.accent),
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
            )
        }
    }
}

/**
 * A panel from the bottom over a dimmed screen. Closes by its ✕, a tap on the dim, a drag down
 * on the handle, or the system back (through [slot]).
 */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun Sheet(
    visible: Boolean,
    slot: BackSlot,
    title: String,
    onDismiss: () -> Unit,
    content: @Composable @ComposableTarget(ModuleUi.UI_APPLIER) ColumnScope.() -> Unit
) {
    val dismiss by rememberUpdatedState(onDismiss)
    DisposableEffect(visible) {
        val handler: () -> Unit = { dismiss() }
        if (visible) slot.handler = handler
        onDispose { if (slot.handler === handler) slot.handler = null }
    }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(P.scrim).pointerInput(Unit) { detectTapGestures { dismiss() } })
        }
        AnimatedVisibility(
            visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut()
        ) {
            Column(
                Modifier.widthIn(max = MAX_W).fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(P.surface)
                    // The sheet takes its own taps: they must not reach the dim behind it and close it.
                    .pointerInput(Unit) { detectTapGestures { } }
                    .keyboardPadding().padding(start = 22.dp, end = 22.dp, bottom = 22.dp)
            ) {
                Box(
                    Modifier.fillMaxWidth().height(30.dp).pointerInput(Unit) {
                        var pulled = 0f
                        detectVerticalDragGestures(
                            onDragStart = { pulled = 0f },
                            onDragEnd = { if (pulled > 48.dp.toPx()) dismiss() }
                        ) { _, amount -> pulled += amount }
                    },
                    contentAlignment = Alignment.Center
                ) {
                    Box(Modifier.size(width = 44.dp, height = 5.dp).clip(RoundedCornerShape(3.dp)).background(P.faint))
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Txt(title, Ty.TITLE, P.text, Modifier.weight(1f))
                    RoundIconButton(PI.CLOSE, "Close", { dismiss() })
                }
                Spacer(Modifier.height(12.dp))
                content()
            }
        }
    }
}

/** A question that needs a second tap. [danger] makes the confirming button red. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun ConfirmSheet(
    visible: Boolean,
    slot: BackSlot,
    icon: PI,
    danger: Boolean,
    title: String,
    body: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    Sheet(visible, slot, title, onDismiss) {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(if (danger) P.dangerSoft else P.accentSoft),
            contentAlignment = Alignment.Center
        ) { PIcon(icon, if (danger) P.dangerText else P.accent, 26.dp) }
        Spacer(Modifier.height(14.dp))
        Txt(body, Ty.BODY, P.text2)
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Cancel", null, modifier = Modifier.weight(1f), onClick = onDismiss)
            if (danger) DangerButton(confirm, null, filled = true, modifier = Modifier.weight(1f), onClick = onConfirm)
            else PrimaryButton(confirm, null, true, Modifier.weight(1f), onConfirm)
        }
    }
}

/** What a toast says, with its mark: from the engine's events or from a screen ("Copied."). */
internal class ToastMessage(val text: String, val icon: PI, val tint: Color)

internal fun toastFor(e: PaddockEvent): ToastMessage = when (e) {
    is PaddockEvent.RoomJoined -> ToastMessage(e.text, PI.CHECK, P.ok)
    is PaddockEvent.MemberJoined -> ToastMessage(e.text, PI.USERS, P.ok)
    is PaddockEvent.MemberLeft -> ToastMessage(e.text, PI.USERS, P.text2)
    is PaddockEvent.RoomClosed -> ToastMessage(e.text, PI.FLAG, P.warn)
    is PaddockEvent.RoomRenamed -> ToastMessage(e.text, PI.PEN, P.text2)
    is PaddockEvent.CallIncoming -> ToastMessage(e.text, PI.CALL, P.ok)
    is PaddockEvent.CallEnded -> ToastMessage(e.text, PI.HANG_UP, P.text2)
    is PaddockEvent.JoinFailed, is PaddockEvent.Error, is PaddockEvent.Warning -> ToastMessage(e.text, PI.WARNING, P.warn)
}

/** A notice from the top; it goes by itself, or with a tap. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun Toast(message: ToastMessage?, onDismiss: () -> Unit) {
    // The last notice stays drawn while it slides away.
    val last = remember { arrayOfNulls<ToastMessage>(1) }
    if (message != null) last[0] = message
    Box(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(message != null, enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut()) {
            val m = last[0]
            if (m != null) {
                Row(
                    Modifier.widthIn(max = 520.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(P.surfaceHi)
                        .pointerInput(Unit) { detectTapGestures { onDismiss() } }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(m.tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                        PIcon(m.icon, m.tint, 20.dp)
                    }
                    Txt(m.text, Ty.SMALL_STRONG, P.text, Modifier.weight(1f), maxLines = 3)
                }
            }
        }
    }
}
