// SPDX-License-Identifier: MPL-2.0
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
// of the MPL was not distributed with this file, you can obtain one at https://mozilla.org/MPL/2.0/.
package io.motohub.paddock.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableTarget
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.sp
import io.motohub.android.module.ModuleUi
import kotlinx.coroutines.delay

/**
 * Paddock's colours, taken from MOTO-HUB's theme (Day, Dark, OLED, Night red, Sunlight...) so the
 * module looks like the same app: ground, cards, text and lines come from the app's colour scheme,
 * the accent is the app's primary. Only the meanings stay Paddock's own - green for "private /
 * connected", amber for "wait", red for what cannot be undone - in a darker shade on a light theme.
 * [PaddockApp] sets the palette from the app's MaterialTheme; until then, the dark one.
 */
internal class Palette(
    val isLight: Boolean,
    val bg: Color, val sky: Color, val surface: Color, val surfaceHi: Color, val line: Color,
    val text: Color, val text2: Color, val dim: Color, val faint: Color,
    val accent: Color, val accentInk: Color, val accentSoft: Color, val accentText: Color,
    val ok: Color, val okSoft: Color, val okText: Color, val warn: Color, val warnSoft: Color,
    val danger: Color, val dangerText: Color, val dangerSoft: Color,
    val scrim: Color, val glass: Color, val ink: Color
)

internal val DarkPalette = Palette(
    isLight = false,
    bg = Color(0xFF080B10), sky = Color(0xFF111A29), surface = Color(0xFF121821), surfaceHi = Color(0xFF19212D),
    line = Color(0xFF273243), text = Color(0xFFF2F5F9), text2 = Color(0xFFC4CDD8), dim = Color(0xFF8E9AA9),
    faint = Color(0xFF5A6677), accent = Color(0xFFFF7A1A), accentInk = Color(0xFF1C0D02), accentSoft = Color(0x29FF7A1A),
    accentText = Color(0xFFFF9A4D), ok = Color(0xFF3DDC97), okSoft = Color(0x223DDC97), okText = Color(0xFF9BEBC7),
    warn = Color(0xFFFFC56B), warnSoft = Color(0x24FFC56B), danger = Color(0xFFE5484D), dangerText = Color(0xFFFF8484),
    dangerSoft = Color(0x26E5484D), scrim = Color(0xC7040609), glass = Color(0x8C05080C), ink = Color(0xFF0A0D12)
)

/** The palette for one of the app's colour schemes. */
internal fun paletteOf(c: ColorScheme): Palette {
    val bg = c.background
    val light = 0.2126f * bg.red + 0.7152f * bg.green + 0.0722f * bg.blue > 0.5f
    val ok = if (light) Color(0xFF13804F) else Color(0xFF3DDC97)
    val warn = if (light) Color(0xFF9A5B00) else Color(0xFFFFC56B)
    val danger = if (light) Color(0xFFC62E33) else Color(0xFFE5484D)
    return Palette(
        isLight = light,
        bg = bg, sky = c.surfaceVariant, surface = c.surface, surfaceHi = c.surfaceVariant, line = c.outlineVariant,
        text = c.onSurface, text2 = c.onSurfaceVariant, dim = c.onSurfaceVariant.copy(alpha = 0.78f), faint = c.outline,
        accent = c.primary, accentInk = c.onPrimary, accentSoft = c.primary.copy(alpha = if (light) 0.12f else 0.16f),
        accentText = c.primary,
        ok = ok, okSoft = ok.copy(alpha = 0.14f), okText = ok, warn = warn, warnSoft = warn.copy(alpha = 0.16f),
        danger = danger, dangerText = if (light) danger else Color(0xFFFF8484), dangerSoft = danger.copy(alpha = 0.14f),
        scrim = if (light) Color(0x66000000) else Color(0xC7040609), glass = if (light) Color(0x99FFFFFF) else Color(0x8C05080C),
        ink = Color(0xFF0A0D12)
    )
}

/** The palette in use; every screen reads it through [P]. */
internal object P {
    var current by mutableStateOf(DarkPalette)
    val isLight get() = current.isLight
    val bg get() = current.bg
    val sky get() = current.sky
    val surface get() = current.surface
    val surfaceHi get() = current.surfaceHi
    val line get() = current.line
    val text get() = current.text
    val text2 get() = current.text2
    val dim get() = current.dim
    val faint get() = current.faint
    val accent get() = current.accent
    val accentInk get() = current.accentInk
    val accentSoft get() = current.accentSoft
    val accentText get() = current.accentText
    val ok get() = current.ok
    val okSoft get() = current.okSoft
    val okText get() = current.okText
    val warn get() = current.warn
    val warnSoft get() = current.warnSoft
    val danger get() = current.danger
    val dangerText get() = current.dangerText
    val dangerSoft get() = current.dangerSoft
    val scrim get() = current.scrim
    val glass get() = current.glass
    val ink get() = current.ink
}

/** Takes MOTO-HUB's theme of the moment: call once at the top of Paddock's screens. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun FollowAppTheme() {
    val scheme = MaterialTheme.colorScheme
    val palette = remember(scheme) { paletteOf(scheme) }
    if (P.current !== palette) P.current = palette
}

/** Spacing and radii: every screen uses these and nothing in between. */
internal object S {
    val gutter = 20.dp
    val gap = 16.dp
    val small = 8.dp
    val section = 28.dp
    val cardRadius = 22.dp
    val controlRadius = 18.dp
    val touch = 48.dp
}

/** The type scale. System font; sizes chosen to be read at a glance, gloves on. */
internal enum class Ty(val size: TextUnit, val line: TextUnit, val weight: FontWeight, val tracking: TextUnit) {
    DISPLAY(34.sp, 40.sp, FontWeight.Bold, (-0.4).sp),
    TITLE(24.sp, 30.sp, FontWeight.Bold, (-0.2).sp),
    HEADING(18.sp, 24.sp, FontWeight.SemiBold, 0.sp),
    BODY(16.sp, 23.sp, FontWeight.Normal, 0.sp),
    BODY_STRONG(16.sp, 22.sp, FontWeight.SemiBold, 0.sp),
    SMALL(14.sp, 20.sp, FontWeight.Normal, 0.sp),
    SMALL_STRONG(14.sp, 19.sp, FontWeight.SemiBold, 0.sp),
    LABEL(12.sp, 16.sp, FontWeight.Bold, 1.2.sp),
    BUTTON(17.sp, 22.sp, FontWeight.Bold, 0.sp),
    NUMBER(30.sp, 34.sp, FontWeight.Bold, 0.sp)
}

/** Text in the type scale. Colour is always stated: the module draws without a MaterialTheme. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun Txt(
    text: String,
    ty: Ty,
    color: Color,
    modifier: Modifier = Modifier,
    align: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE
) {
    Text(
        text, modifier, color = color, fontSize = ty.size, fontWeight = ty.weight, letterSpacing = ty.tracking,
        lineHeight = ty.line, textAlign = align, maxLines = maxLines, overflow = TextOverflow.Ellipsis
    )
}

/**
 * Seconds since this composable appeared, ticking every frame. Every animation in the module is
 * plain arithmetic on this one clock: it keeps the borrowed animation API down to withFrameNanos.
 * Wrapped hourly so the float keeps its precision; every period used divides an hour.
 */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun rememberSeconds(): MutableFloatState {
    val t = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { now -> t.floatValue = ((now - start) % 3_600_000_000_000L) / 1_000_000_000f }
    }
    return t
}

/** Wall-clock epoch seconds, refreshed every second: "last seen 3 min ago" must age on its own. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun rememberNowSeconds(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis() / 1000
        }
    }
    return now
}

/** A rider's "#RRGGBB". It comes from other phones too, so anything unreadable falls back to the accent. */
internal fun riderColor(hex: String): Color = try {
    Color(android.graphics.Color.parseColor(hex).toLong() and 0xFFFFFFFFL)
} catch (e: RuntimeException) {
    P.accent
}

/** Dark ink on light colours, white on dark ones. */
internal fun inkOn(c: Color): Color = if (0.299f * c.red + 0.587f * c.green + 0.114f * c.blue > 0.5f) P.ink else Color.White

/** Up to two initials, from letters and digits only (an emoji half would draw as a box). */
internal fun initials(name: String): String {
    val out = StringBuilder()
    var wordStart = true
    for (ch in name) {
        if (Character.isWhitespace(ch)) { wordStart = true; continue }
        if (wordStart && out.length < 2 && Character.isLetterOrDigit(ch)) out.append(Character.toUpperCase(ch))
        wordStart = false
    }
    return if (out.isEmpty()) "?" else out.toString()
}

/** "rns.kin.earth:4242" → "rns.kin.earth". */
internal fun relayHost(node: String): String {
    val i = node.lastIndexOf(':')
    return if (i > 0) node.substring(0, i) else node
}

/** Names for [io.motohub.paddock.engine.RiderColors], in the same order, for screen readers. */
internal val RiderColorNames = listOf("Orange", "Yellow", "Green", "Sky blue", "Blue", "Purple", "Pink", "White")

/** The ids of [rooms], to tell later which room is new. */
internal fun roomIds(rooms: List<io.motohub.paddock.engine.Room>): Set<String> {
    val ids = java.util.HashSet<String>()
    for (r in rooms) ids.add(r.id)
    return ids
}

/** "active just now", "active 5 min ago", "active 3 h ago", "active 2 days ago"; "" when unknown. */
internal fun activeAgo(now: Long, at: Long): String {
    if (at <= 0) return ""
    val s = now - at
    return when {
        s < 60 -> "active just now"
        s < 3600 -> "active ${s / 60} min ago"
        s < 86400 -> "active ${s / 3600} h ago"
        s < 2 * 86400 -> "active yesterday"
        else -> "active ${s / 86400} days ago"
    }
}

/** A steady colour for a room's tile, picked from its id. */
internal fun roomColor(id: String): androidx.compose.ui.graphics.Color {
    var h = 0
    for (c in id) h = h * 31 + c.code
    return riderColor(io.motohub.paddock.engine.RiderColors[(h and 0x7fffffff) % io.motohub.paddock.engine.RiderColors.size])
}

/** A quick message's look: its icon and its colour, the same in the sheet, the chat and Home. */
internal fun quickIcon(kind: io.motohub.paddock.engine.QuickKind): PI = when (kind) {
    io.motohub.paddock.engine.QuickKind.STOP -> PI.STOP_SIGN
    io.motohub.paddock.engine.QuickKind.FUEL -> PI.FUEL
    io.motohub.paddock.engine.QuickKind.SLOW -> PI.GAUGE
    io.motohub.paddock.engine.QuickKind.WAIT -> PI.CLOCK
    io.motohub.paddock.engine.QuickKind.PHOTO -> PI.PHOTO
    io.motohub.paddock.engine.QuickKind.SPOT -> PI.PIN
    io.motohub.paddock.engine.QuickKind.EMERGENCY -> PI.WARNING
}

internal fun quickTint(kind: io.motohub.paddock.engine.QuickKind): Color = when (kind) {
    io.motohub.paddock.engine.QuickKind.STOP -> P.accent
    io.motohub.paddock.engine.QuickKind.FUEL -> P.warn
    io.motohub.paddock.engine.QuickKind.SLOW -> Color(0xFF33C7FF)
    io.motohub.paddock.engine.QuickKind.WAIT -> Color(0xFFB57BFF)
    io.motohub.paddock.engine.QuickKind.PHOTO -> P.ok
    io.motohub.paddock.engine.QuickKind.SPOT -> P.text2
    io.motohub.paddock.engine.QuickKind.EMERGENCY -> P.dangerText
}

/** "Luca needs fuel"; for my own: "You asked for fuel". */
internal fun quickSentence(kind: io.motohub.paddock.engine.QuickKind, name: String, mine: Boolean): String {
    val who = if (mine) "You" else name
    return when (kind) {
        io.motohub.paddock.engine.QuickKind.STOP -> if (mine) "You asked everyone to stop" else "$who asks everyone to stop"
        io.motohub.paddock.engine.QuickKind.FUEL -> if (mine) "You need fuel" else "$who needs fuel"
        io.motohub.paddock.engine.QuickKind.SLOW -> if (mine) "You asked to slow down" else "$who asks to slow down"
        io.motohub.paddock.engine.QuickKind.WAIT -> if (mine) "You asked the others to wait" else "$who asks you to wait"
        io.motohub.paddock.engine.QuickKind.PHOTO -> if (mine) "You asked for a photo stop" else "$who wants a photo stop"
        io.motohub.paddock.engine.QuickKind.SPOT -> if (mine) "You shared your spot" else "$who shared their spot"
        io.motohub.paddock.engine.QuickKind.EMERGENCY -> if (mine) "You sent an emergency" else "$who needs help"
    }
}

/** "350 m" or "1.2 km". */
internal fun distanceText(metres: Double): String =
    if (metres < 1000) "${((metres / 10).toLong() * 10)} m"
    else String.format(java.util.Locale.ENGLISH, "%.1f km", metres / 1000)

private val HM = java.time.format.DateTimeFormatter.ofPattern("HH:mm", java.util.Locale.ENGLISH)
private val WEEKDAY = java.time.format.DateTimeFormatter.ofPattern("EEE", java.util.Locale.ENGLISH)
private val DAY_MONTH = java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.ENGLISH)
private val DAY_LABEL = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.ENGLISH)

private fun zoned(atMillis: Long) = java.time.Instant.ofEpochMilli(atMillis).atZone(java.time.ZoneId.systemDefault())

/** "09:41" */
internal fun clockTime(atMillis: Long): String = HM.format(zoned(atMillis))

/** For the rooms list: "09:41" today, "Yesterday", "Tue" this week, otherwise "12 Nov". */
internal fun listTime(atMillis: Long): String {
    val day = zoned(atMillis).toLocalDate()
    val today = java.time.LocalDate.now()
    return when {
        day == today -> clockTime(atMillis)
        day == today.minusDays(1) -> "Yesterday"
        day.isAfter(today.minusDays(7)) -> WEEKDAY.format(day)
        else -> DAY_MONTH.format(day)
    }
}

/** For the chat's day separators: "TODAY 09:12", "YESTERDAY 18:40", "TUE 12 NOV 18:40". */
internal fun dayLabel(atMillis: Long): String {
    val day = zoned(atMillis).toLocalDate()
    val today = java.time.LocalDate.now()
    val name = when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> DAY_LABEL.format(day)
    }
    return (name + " " + clockTime(atMillis)).uppercase()
}

/** Same calendar day, in this phone's time zone. */
internal fun sameDay(a: Long, b: Long): Boolean = zoned(a).toLocalDate() == zoned(b).toLocalDate()

/** Online: a fresh position, or heard from in the last 2 minutes. */
internal fun isOnline(m: io.motohub.paddock.engine.Member, nowSeconds: Long): Boolean {
    if (m.freshness(nowSeconds) == io.motohub.paddock.engine.Freshness.FRESH) return true
    val heard = m.heardAt ?: return false
    return nowSeconds - heard <= 120
}
