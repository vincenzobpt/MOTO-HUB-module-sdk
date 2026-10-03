// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.dashcam.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableTarget
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.motohub.android.module.ModuleUi

/**
 * The module's look: a dark instrument panel, big numbers, one cyan accent, red only for recording
 * and for what cannot be undone. Fixed rather than the rider's theme: the live picture, the
 * files and the player sit on video, and the pages around them should not change colour under it.
 */
internal object Dc {
    val bg = Color(0xFF0B0F14)
    val surface = Color(0xFF121821)
    val surfaceLine = Color(0xFF1F2834)
    val rail = Color(0xFF0F141B)
    val line = Color(0xFF263140)
    val text = Color(0xFFEEF2F6)
    val text2 = Color(0xFFC3CCD6)
    val dim = Color(0xFF9AA6B4)
    val faint = Color(0xFF5D6A79)
    val accent = Color(0xFF4FC3F7)
    val accentInk = Color(0xFF06131B)
    val accentSoft = Color(0xFF0E2633)
    val ok = Color(0xFF3DDC97)
    val okSoft = Color(0xFF10261C)
    val warn = Color(0xFFFFC56B)
    val warnSoft = Color(0x29FFB547)
    val rec = Color(0xFFFF5252)
    val danger = Color(0xFFE5484D)
    val dangerText = Color(0xFFFF7A7A)
    val dangerSoft = Color(0xFF2A1416)
    val scrim = Color(0xB8060A0E)
    val glass = Color(0x1FFFFFFF)
}

/** The few icons the module needs, drawn: a module carries no resources. 24-unit grid, 1.8 stroke. */
internal enum class Ico { BACK, CHEVRON, PLAY, PAUSE, CAMERA, VIDEO, SLIDERS, PHONE, WIFI, LOCK, DOWNLOAD, TRASH, PHOTO, CARD, CHECK, EXPAND, CLOSE }

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun DcIcon(icon: Ico, color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val u = this.size.width / 24f
        val stroke = Stroke(width = 1.9f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(vararg xy: Float): Path = Path().apply {
            moveTo(xy[0] * u, xy[1] * u)
            var i = 2
            while (i < xy.size) { lineTo(xy[i] * u, xy[i + 1] * u); i += 2 }
        }
        fun line(vararg xy: Float) = drawPath(p(*xy), color, style = stroke)
        fun box(x: Float, y: Float, w: Float, h: Float, r: Float) =
            drawRoundRect(color, Offset(x * u, y * u), Size(w * u, h * u), CornerRadius(r * u), style = stroke)
        fun ring(cx: Float, cy: Float, r: Float) = drawCircle(color, r * u, Offset(cx * u, cy * u), style = stroke)
        when (icon) {
            Ico.BACK -> line(15f, 18f, 9f, 12f, 15f, 6f)
            Ico.CHEVRON -> line(9f, 6f, 15f, 12f, 9f, 18f)
            Ico.PLAY -> drawPath(p(7f, 5f, 19f, 12f, 7f, 19f).apply { close() }, color)
            Ico.PAUSE -> {
                drawRoundRect(color, Offset(6f * u, 5f * u), Size(4f * u, 14f * u), CornerRadius(u))
                drawRoundRect(color, Offset(14f * u, 5f * u), Size(4f * u, 14f * u), CornerRadius(u))
            }
            Ico.CAMERA -> { box(3f, 7f, 13f, 10f, 2f); line(16f, 10.5f, 21f, 7.5f, 21f, 16.5f, 16f, 13.5f); ring(9.5f, 12f, 2.5f) }
            Ico.VIDEO -> { box(3f, 5f, 18f, 14f, 2f); drawPath(p(10f, 9.5f, 14.5f, 12f, 10f, 14.5f).apply { close() }, color, style = stroke) }
            Ico.SLIDERS -> { line(4f, 7f, 14f, 7f); line(18f, 7f, 20f, 7f); line(4f, 17f, 8f, 17f); line(12f, 17f, 20f, 17f); ring(16f, 7f, 2f); ring(10f, 17f, 2f) }
            Ico.PHONE -> { box(6f, 2f, 12f, 20f, 2.5f); line(12f, 7f, 12f, 14f); line(9f, 11f, 12f, 14f, 15f, 11f) }
            Ico.WIFI -> {
                drawArc(color, 225f, 90f, false, Offset(2f * u, 4f * u), Size(20f * u, 20f * u), style = stroke)
                drawArc(color, 225f, 90f, false, Offset(5.5f * u, 8f * u), Size(13f * u, 13f * u), style = stroke)
                drawArc(color, 225f, 90f, false, Offset(9f * u, 12f * u), Size(6f * u, 6f * u), style = stroke)
                drawCircle(color, 1.2f * u, Offset(12f * u, 19.5f * u))
            }
            Ico.LOCK -> { box(5f, 11f, 14f, 10f, 2f); drawArc(color, 180f, 180f, false, Offset(8f * u, 4f * u), Size(8f * u, 8f * u), style = stroke); line(8f, 8f, 8f, 11f); line(16f, 8f, 16f, 11f) }
            Ico.DOWNLOAD -> { line(12f, 4f, 12f, 14f); line(8f, 10f, 12f, 14f, 16f, 10f); line(5f, 20f, 19f, 20f) }
            Ico.TRASH -> { line(4f, 7f, 20f, 7f); line(6f, 7f, 7f, 20f, 17f, 20f, 18f, 7f); line(9f, 7f, 9f, 4f, 15f, 4f, 15f, 7f); line(10f, 11f, 10f, 16f); line(14f, 11f, 14f, 16f) }
            Ico.PHOTO -> { line(4f, 7f, 8f, 7f, 10f, 4f, 14f, 4f, 16f, 7f, 20f, 7f, 20f, 19f, 4f, 19f, 4f, 7f); ring(12f, 13f, 3.5f) }
            Ico.CARD -> { line(7f, 3f, 14f, 3f, 19f, 8f, 19f, 21f, 7f, 21f, 7f, 3f); line(10f, 13f, 16f, 13f) }
            Ico.CHECK -> line(5f, 12f, 10f, 17f, 19f, 7f)
            Ico.EXPAND -> { line(4f, 9f, 4f, 4f, 9f, 4f); line(20f, 15f, 20f, 20f, 15f, 20f); line(4f, 4f, 10f, 10f); line(20f, 20f, 14f, 14f) }
            Ico.CLOSE -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
        }
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun SectionTitle(text: String) {
    Text(text.uppercase(), color = Dc.dim, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
}

/** A card of rows, divided by hairlines. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun Card(content: @Composable @ComposableTarget(ModuleUi.UI_APPLIER) () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Dc.surface)
            .border(1.dp, Dc.surfaceLine, RoundedCornerShape(18.dp))
    ) { content() }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun Hairline() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Dc.surfaceLine))
}

/** A row that opens something: icon, title, a line of detail, a chevron. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun NavRow(icon: Ico?, title: String, detail: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(66.dp).alpha(if (enabled) 1f else 0.45f)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (icon != null) DcIcon(icon, Dc.accent, 22.dp)
        Column(Modifier.weight(1f)) {
            Text(title, color = Dc.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotEmpty()) Text(detail, color = Dc.dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DcIcon(Ico.CHEVRON, Dc.faint, 18.dp)
    }
}

/** The page's main action: full width, 60 dp, the accent. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun BigButton(text: String, icon: Ico?, enabled: Boolean, danger: Boolean, onClick: () -> Unit) {
    val bg = when { !enabled -> Dc.line; danger -> Dc.danger; else -> Dc.accent }
    val fg = when { !enabled -> Dc.dim; danger -> Color.White; else -> Dc.accentInk }
    Row(
        Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(18.dp)).background(bg)
            .clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) { DcIcon(icon, fg, 22.dp); Spacer(Modifier.width(10.dp)) }
        Text(text, color = fg, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

/** A round control on video: a glass disc with an icon, its word underneath. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun RoundButton(label: String, onClick: () -> Unit, face: @Composable @ComposableTarget(ModuleUi.UI_APPLIER) () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(Modifier.size(60.dp).clip(CircleShape).background(Dc.glass), contentAlignment = Alignment.Center) { face() }
        Text(label, color = Dc.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** A small rounded status on video: an optional dot or icon and a word. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun Pill(text: String, fg: Color, bg: Color, dot: Color?) {
    Row(
        Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(bg).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (dot != null) Box(Modifier.size(9.dp).clip(CircleShape).background(dot))
        Text(text, color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** A circular glass button with only an icon, for the corners of the video. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun GlassIconButton(icon: Ico, tint: Color, bg: Color, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape).background(bg).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        DcIcon(icon, tint, 22.dp)
    }
}

/** A choice among a few options, as a segmented bar. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun Segmented(options: List<String>, selected: Int, onPick: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Dc.bg).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        options.forEachIndexed { i, option ->
            val on = i == selected
            Box(
                Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(10.dp)).background(if (on) Dc.accent else Color.Transparent)
                    .clickable { if (!on) onPick(i) },
                contentAlignment = Alignment.Center
            ) {
                Text(option, color = if (on) Dc.accentInk else Dc.text2, fontSize = 15.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

/** An on/off switch drawn in the module's colours. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun DcSwitch(on: Boolean, onToggle: () -> Unit) {
    Box(
        Modifier.width(52.dp).height(32.dp).clip(RoundedCornerShape(16.dp)).background(if (on) Dc.accent else Dc.line)
            .clickable(onClick = onToggle).padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(if (on) Dc.accentInk else Dc.dim))
    }
}

/** A question that needs a second tap, over whatever is on screen. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun ConfirmDialog(title: String, body: String, action: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color(0xC7040609)).clickable(onClick = onCancel),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.widthIn(max = 440.dp).padding(24.dp).clip(RoundedCornerShape(24.dp)).background(Dc.surface)
                .border(1.dp, Color(0xFF2B3644), RoundedCornerShape(24.dp)).clickable { }.padding(26.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(Dc.dangerSoft), contentAlignment = Alignment.Center) {
                DcIcon(Ico.TRASH, Dc.dangerText, 26.dp)
            }
            Text(title, color = Dc.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(body, color = Dc.text2, fontSize = 15.sp, lineHeight = 22.sp)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.weight(1f).height(54.dp).clip(RoundedCornerShape(16.dp)).border(1.dp, Color(0xFF2B3644), RoundedCornerShape(16.dp))
                        .clickable(onClick = onCancel),
                    contentAlignment = Alignment.Center
                ) { Text("Cancel", color = Dc.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                Box(
                    Modifier.weight(1f).height(54.dp).clip(RoundedCornerShape(16.dp)).background(Dc.danger).clickable(onClick = onConfirm),
                    contentAlignment = Alignment.Center
                ) { Text(action, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

/** A page header for the portrait pages: a round back button and the title. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun PageHeader(title: String, onBack: () -> Unit, trailing: @Composable @ComposableTarget(ModuleUi.UI_APPLIER) () -> Unit) {
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(Dc.surface).border(1.dp, Dc.line, CircleShape).clickable(onClick = onBack),
            contentAlignment = Alignment.Center
        ) { DcIcon(Ico.BACK, Dc.text, 20.dp) }
        Text(title, color = Dc.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing()
    }
}

internal fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes >= 1L shl 30 -> String.format(java.util.Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1L shl 20 -> String.format(java.util.Locale.ROOT, "%.0f MB", bytes / (1024.0 * 1024))
    else -> String.format(java.util.Locale.ROOT, "%.0f KB", bytes / 1024.0)
}

internal fun formatMb(mb: Long): String = if (mb >= 1024) String.format(java.util.Locale.ROOT, "%.1f GB", mb / 1024.0) else "$mb MB"

internal fun clock(ms: Long): String {
    val s = (ms / 1000).toInt()
    return if (s >= 3600) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    else String.format(java.util.Locale.ROOT, "%d:%02d", s / 60, s % 60)
}
