// SPDX-License-Identifier: MPL-2.0
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
// of the MPL was not distributed with this file, you can obtain one at https://mozilla.org/MPL/2.0/.
package io.motohub.paddock.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableTarget
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.motohub.android.module.ModuleUi
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The welcome picture: four riders ride a winding night road, each joined to the next by a dotted
 * green link, with a padlock on the link - the room, private between them. Slow on purpose.
 */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun HeroRoad(modifier: Modifier) {
    val clock = rememberSeconds()
    Box(modifier) {
        Canvas(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(P.sky, P.bg)))) {
            val time = clock.floatValue
            val w = size.width
            val h = size.height

            for (i in 0 until STAR_COUNT) {
                val twinkle = 0.35f + 0.3f * sin(time * 1.3f + i * 1.7f)
                drawCircle(Color.White.copy(alpha = twinkle), STARS[i * 3 + 2] * 1.dp.toPx(), Offset(STARS[i * 3] * w, STARS[i * 3 + 1] * h))
            }
            val moon = Offset(w * 0.83f, h * 0.17f)
            drawCircle(Color(0x0FE8EDF4), h * 0.13f, moon)
            drawCircle(Color(0xE6E8EDF4), h * 0.045f, moon)
            drawCircle(P.sky, h * 0.04f, Offset(moon.x + h * 0.02f, moon.y - h * 0.012f))

            drawPath(Path().apply {
                moveTo(0f, 0.50f * h)
                cubicTo(0.18f * w, 0.40f * h, 0.30f * w, 0.47f * h, 0.45f * w, 0.42f * h)
                cubicTo(0.62f * w, 0.36f * h, 0.78f * w, 0.46f * h, w, 0.38f * h)
                lineTo(w, h); lineTo(0f, h); close()
            }, Color(0xFF0E1521))
            drawPath(Path().apply {
                moveTo(0f, 0.66f * h)
                cubicTo(0.2f * w, 0.58f * h, 0.42f * w, 0.70f * h, 0.62f * w, 0.62f * h)
                cubicTo(0.8f * w, 0.55f * h, 0.9f * w, 0.62f * h, w, 0.58f * h)
                lineTo(w, h); lineTo(0f, h); close()
            }, Color(0xFF0B1019))

            // The road as a ribbon of discs, narrowing into the distance: a stroke cannot taper.
            val roadHalf = h * 0.065f
            for (pass in 0..1) {
                val color = if (pass == 0) Color(0xFF222C3A) else Color(0xFF141B25)
                val scale = if (pass == 0) 1f else 0.82f
                for (k in 0..ROAD_SAMPLES) {
                    val t = k / ROAD_SAMPLES.toFloat()
                    drawCircle(color, roadHalf * scale * perspective(t), roadAt(t, w, h))
                }
            }
            for (k in 0..ROAD_SAMPLES) {
                if ((k / 3) % 2 == 1) continue
                val t = k / ROAD_SAMPLES.toFloat()
                drawCircle(Color(0x734A5668), 1.3.dp.toPx() * perspective(t), roadAt(t, w, h))
            }

            val group = (time / 24f) % 1f
            val ts = FloatArray(HERO_RIDERS.size) { i -> frac(group + 0.32f - i * 0.085f) }
            val at = Array(HERO_RIDERS.size) { i -> roadAt(ts[i], w, h) }
            val fade = FloatArray(HERO_RIDERS.size) { i -> min(1f, min(ts[i], 1f - ts[i]) / 0.07f) }
            val dotR = 8.dp.toPx()

            for (i in 0 until HERO_RIDERS.size - 1) {
                val gap = ts[i] - ts[i + 1]
                if (gap <= 0f || gap > 0.2f) continue // the group is wrapping round the picture's edge
                val a = at[i + 1]
                val b = at[i]
                val dx = b.x - a.x
                val dy = b.y - a.y
                val d = sqrt(dx * dx + dy * dy)
                if (d < dotR * 3) continue
                val alpha = 0.85f * min(fade[i], fade[i + 1])
                val spacing = 7.dp.toPx()
                val n = (d / spacing).toInt()
                val phase = (time * 1.2f) % 1f
                for (k in 0 until n) {
                    val f = (k + phase) / n
                    if (f * d < dotR * 1.6f || (1f - f) * d < dotR * 1.6f) continue
                    drawCircle(P.ok.copy(alpha = alpha), 1.6.dp.toPx(), Offset(a.x + dx * f, a.y + dy * f))
                }
                if (i == 1) {
                    val mid = Offset(a.x + dx / 2, a.y + dy / 2)
                    drawCircle(P.bg.copy(alpha = alpha), 12.dp.toPx(), mid)
                    drawCircle(P.ok.copy(alpha = alpha), 10.dp.toPx(), mid)
                    drawPadlock(mid, 11.dp.toPx(), P.ink.copy(alpha = alpha))
                }
            }
            for (i in 0 until HERO_RIDERS.size) {
                val p = at[i]
                val s = perspective(ts[i]) * 0.5f + 0.5f
                val pulse = 0.5f + 0.5f * sin(time * 2.6f + i)
                val c = HERO_RIDERS[i]
                drawCircle(c.copy(alpha = 0.16f * fade[i]), dotR * s * (1.9f + 0.35f * pulse), p)
                drawCircle(P.bg.copy(alpha = fade[i]), dotR * s * 1.22f, p)
                drawCircle(c.copy(alpha = fade[i]), dotR * s, p)
                drawCircle(Color.White.copy(alpha = 0.45f * fade[i]), dotR * s * 0.3f, Offset(p.x - dotR * s * 0.3f, p.y - dotR * s * 0.3f))
            }
        }
        // The picture melts into the page below it.
        Box(
            Modifier.fillMaxWidth().height(56.dp).align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, P.bg)))
        )
    }
}

/**
 * How a message travels: from your phone, through the public relay, to another rider's phone,
 * sealed the whole way. The relay passes the envelope on; it has no key to open it.
 */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun RelayDiagram(modifier: Modifier) {
    val clock = rememberSeconds()
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(132.dp)) {
            val time = clock.floatValue
            val w = size.width
            val cy = size.height * 0.5f
            val xs = floatArrayOf(w / 6f, w / 2f, w * 5f / 6f)
            val left = xs[0] + 24.dp.toPx()
            val right = xs[2] - 24.dp.toPx()

            val step = 9.dp.toPx()
            var x = left
            while (x <= right) {
                if (x < xs[1] - 22.dp.toPx() || x > xs[1] + 22.dp.toPx()) drawCircle(P.faint, 1.5.dp.toPx(), Offset(x, cy))
                x += step
            }
            drawPhone(xs[0], cy, P.accent)
            drawPhone(xs[2], cy, Color(0xFF33C7FF))
            drawRelay(xs[1], cy, time)

            val p = (time / 4f) % 1f
            val alpha = min(1f, min(p, 1f - p) / 0.08f)
            drawEnvelope(Offset(left + (right - left) * p, cy), alpha)
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            DiagramLabel("Your phone", "holds your keys", Modifier.weight(1f))
            DiagramLabel("Public relay", "passes it on, cannot read it", Modifier.weight(1f))
            DiagramLabel("A rider's phone", "holds their keys", Modifier.weight(1f))
        }
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun DiagramLabel(title: String, detail: String, modifier: Modifier) {
    Column(modifier.padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Txt(title, Ty.SMALL_STRONG, P.text, align = TextAlign.Center)
        Txt(detail, Ty.SMALL, P.dim, align = TextAlign.Center)
    }
}

/** Rings that ripple outward from the middle, behind [content]: someone is being called. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun CallingRings(color: Color, modifier: Modifier, content: @Composable @ComposableTarget(ModuleUi.UI_APPLIER) () -> Unit) {
    val clock = rememberSeconds()
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val t = clock.floatValue
            val maxR = min(size.width, size.height) / 2f
            val minR = maxR * 0.38f
            for (k in 0..2) {
                val f = (t / 2.4f + k / 3f) % 1f
                drawCircle(color.copy(alpha = 0.4f * (1f - f)), minR + (maxR - minR) * f, center, style = Stroke(width = 2.dp.toPx()))
            }
        }
        content()
    }
}

private fun DrawScope.drawPhone(cx: Float, cy: Float, rider: Color) {
    val pw = 30.dp.toPx()
    val ph = 52.dp.toPx()
    val tl = Offset(cx - pw / 2, cy - ph / 2)
    drawRoundRect(P.surfaceHi, tl, Size(pw, ph), CornerRadius(7.dp.toPx()))
    drawRoundRect(P.text2, tl, Size(pw, ph), CornerRadius(7.dp.toPx()), style = Stroke(width = 2.dp.toPx()))
    drawRoundRect(P.text2, Offset(cx - 5.dp.toPx(), tl.y + 4.dp.toPx()), Size(10.dp.toPx(), 2.dp.toPx()), CornerRadius(1.dp.toPx()))
    drawCircle(rider, 6.dp.toPx(), Offset(cx, cy + 2.dp.toPx()))
    val badge = Offset(tl.x + pw - 1.dp.toPx(), tl.y + 3.dp.toPx())
    drawCircle(P.bg, 10.dp.toPx(), badge)
    drawCircle(P.ok, 8.dp.toPx(), badge)
    drawPadlock(badge, 9.dp.toPx(), P.ink)
}

private fun DrawScope.drawRelay(cx: Float, cy: Float, time: Float) {
    val node = Offset(cx, cy - 10.dp.toPx())
    drawRoundRect(P.dim, Offset(cx - 1.5.dp.toPx(), node.y), Size(3.dp.toPx(), 36.dp.toPx()), CornerRadius(1.5.dp.toPx()))
    drawRoundRect(P.dim, Offset(cx - 9.dp.toPx(), node.y + 34.dp.toPx()), Size(18.dp.toPx(), 3.dp.toPx()), CornerRadius(1.5.dp.toPx()))
    drawCircle(P.text2, 5.dp.toPx(), node)
    val pulse = 0.5f + 0.5f * sin(time * 3f)
    val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
    for (k in 0..1) {
        val rr = (if (k == 0) 11 else 18).dp.toPx()
        val a = if (k == 0) 0.35f + 0.45f * pulse else 0.2f + 0.35f * (1f - pulse)
        val tl = Offset(node.x - rr, node.y - rr)
        drawArc(P.dim.copy(alpha = a), 140f, 80f, false, tl, Size(rr * 2, rr * 2), style = stroke)
        drawArc(P.dim.copy(alpha = a), -40f, 80f, false, tl, Size(rr * 2, rr * 2), style = stroke)
    }
}

private fun DrawScope.drawEnvelope(c: Offset, alpha: Float) {
    val ew = 34.dp.toPx()
    val eh = 23.dp.toPx()
    val tl = Offset(c.x - ew / 2, c.y - eh / 2)
    drawRoundRect(P.text.copy(alpha = alpha), tl, Size(ew, eh), CornerRadius(3.dp.toPx()))
    drawPath(Path().apply {
        moveTo(tl.x + 2.dp.toPx(), tl.y + 2.dp.toPx())
        lineTo(c.x, c.y + 2.dp.toPx())
        lineTo(tl.x + ew - 2.dp.toPx(), tl.y + 2.dp.toPx())
    }, P.dim.copy(alpha = alpha), style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round))
    val seal = Offset(c.x, c.y + 2.dp.toPx())
    drawCircle(P.ok.copy(alpha = alpha), 7.dp.toPx(), seal)
    drawPadlock(seal, 8.dp.toPx(), P.ink.copy(alpha = alpha))
}

private const val ROAD_SAMPLES = 180
private const val STAR_COUNT = 26

private val HERO_RIDERS = listOf(Color(0xFFFF7A1A), Color(0xFF33C7FF), Color(0xFFFFC233), Color(0xFFB57BFF))

/** Two cubic segments in fractions of the picture, from bottom-left into the distance top-right. */
private val ROAD = floatArrayOf(
    -0.08f, 0.94f, 0.22f, 1.00f, 0.24f, 0.50f, 0.50f, 0.55f,
    0.50f, 0.55f, 0.76f, 0.60f, 0.74f, 0.20f, 1.08f, 0.27f
)

/** Stars at fixed, scattered places: x, y (fractions), radius (dp). */
private val STARS = FloatArray(STAR_COUNT * 3).also { a ->
    var seed = 7L
    fun next(): Float {
        seed = (seed * 1103515245L + 12345L) and 0x7fffffffL
        return seed / 2147483647f
    }
    for (i in 0 until STAR_COUNT) {
        a[i * 3] = next()
        a[i * 3 + 1] = 0.03f + 0.38f * next()
        a[i * 3 + 2] = 0.5f + next()
    }
}

private fun roadAt(t: Float, w: Float, h: Float): Offset {
    val seg = if (t < 0.5f) 0 else 8
    val s = if (t < 0.5f) t * 2f else (t - 0.5f) * 2f
    val a = 1f - s
    val b0 = a * a * a
    val b1 = 3f * a * a * s
    val b2 = 3f * a * s * s
    val b3 = s * s * s
    val x = b0 * ROAD[seg] + b1 * ROAD[seg + 2] + b2 * ROAD[seg + 4] + b3 * ROAD[seg + 6]
    val y = b0 * ROAD[seg + 1] + b1 * ROAD[seg + 3] + b2 * ROAD[seg + 5] + b3 * ROAD[seg + 7]
    return Offset(x * w, y * h)
}

/** Nearer is bigger: 1 at the bottom-left end of the road, 0.45 at the far end. */
private fun perspective(t: Float): Float = max(0.45f, 1f - 0.55f * t)

private fun frac(x: Float): Float = ((x % 1f) + 1f) % 1f
