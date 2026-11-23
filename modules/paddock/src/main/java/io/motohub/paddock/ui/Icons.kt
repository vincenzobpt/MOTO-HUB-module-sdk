// SPDX-License-Identifier: MPL-2.0
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
// of the MPL was not distributed with this file, you can obtain one at https://mozilla.org/MPL/2.0/.
package io.motohub.paddock.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableTarget
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import io.motohub.android.module.ModuleUi

/** Paddock's icons, drawn in code (a module carries no resources). 24-unit grid, rounded 1.8 stroke. */
internal enum class PI {
    BACK, CLOSE, CHEVRON, CHECK, SHIELD, LOCK, KEY, QR, SCAN, USER, USERS, NO_ACCOUNT, PIN, MAP, SHARE,
    BIKE, RELAY, SIGNAL, WARNING, CROWN, FLAG, GEAR, CAMERA, LINK, PASTE, PHONES, PEN, TRASH,
    CHAT, BOLT, SEND, STOP_SIGN, FUEL, GAUGE, CLOCK, PHOTO, DOTS, MORE_VERT, MAILBOX, DOUBLE_CHECK,
    CALL, HANG_UP, MIC, MIC_OFF, SPEAKER
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun PIcon(icon: PI, color: Color, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val u = this.size.width / 24f
        val st = Stroke(width = 1.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun o(x: Float, y: Float) = Offset(x * u, y * u)
        fun Path.m(x: Float, y: Float) = moveTo(x * u, y * u)
        fun Path.l(x: Float, y: Float) = lineTo(x * u, y * u)
        fun Path.c(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) =
            cubicTo(x1 * u, y1 * u, x2 * u, y2 * u, x3 * u, y3 * u)
        fun line(vararg xy: Float) {
            val p = Path()
            p.m(xy[0], xy[1])
            var i = 2
            while (i < xy.size) { p.l(xy[i], xy[i + 1]); i += 2 }
            drawPath(p, color, style = st)
        }
        fun stroke(build: Path.() -> Unit) = drawPath(Path().apply(build), color, style = st)
        fun box(x: Float, y: Float, w: Float, h: Float, r: Float) =
            drawRoundRect(color, o(x, y), Size(w * u, h * u), CornerRadius(r * u), style = st)
        fun block(x: Float, y: Float, w: Float, h: Float) =
            drawRoundRect(color, o(x, y), Size(w * u, h * u), CornerRadius(0.6f * u))
        fun ring(cx: Float, cy: Float, r: Float) = drawCircle(color, r * u, o(cx, cy), style = st)
        fun dot(cx: Float, cy: Float, r: Float) = drawCircle(color, r * u, o(cx, cy))
        fun arc(cx: Float, cy: Float, r: Float, start: Float, sweep: Float) =
            drawArc(color, start, sweep, false, o(cx - r, cy - r), Size(2 * r * u, 2 * r * u), style = st)
        fun user(dx: Float) {
            ring(12f + dx, 8f, 3.6f)
            stroke { m(5f + dx, 20.5f); c(5f + dx, 16.4f, 8f + dx, 14.2f, 12f + dx, 14.2f); c(16f + dx, 14.2f, 19f + dx, 16.4f, 19f + dx, 20.5f) }
        }

        when (icon) {
            PI.BACK -> line(15f, 18.5f, 8.5f, 12f, 15f, 5.5f)
            PI.CALL -> stroke {
                m(6.5f, 3.5f); l(9.5f, 3.5f); l(11f, 8f); l(9f, 9.5f)
                c(10f, 12f, 12f, 14f, 14.5f, 15f); l(16f, 13f); l(20.5f, 14.5f); l(20.5f, 17.5f)
                c(20.5f, 19f, 19f, 20.5f, 17.5f, 20.5f); c(10f, 20f, 4f, 14f, 3.5f, 6.5f)
                c(3.5f, 5f, 5f, 3.5f, 6.5f, 3.5f); close()
            }
            PI.HANG_UP -> stroke {
                m(3f, 14f); c(3f, 11f, 7f, 9f, 12f, 9f); c(17f, 9f, 21f, 11f, 21f, 14f); l(21f, 16f); l(17f, 16f)
                l(16f, 13.5f); c(13.5f, 12.8f, 10.5f, 12.8f, 8f, 13.5f); l(7f, 16f); l(3f, 16f); close()
            }
            PI.MIC, PI.MIC_OFF -> {
                box(9f, 3f, 6f, 11f, 3f)
                arc(12f, 11f, 6f, 0f, 180f)
                line(12f, 17f, 12f, 21f)
                if (icon == PI.MIC_OFF) line(4f, 4f, 20f, 20f)
            }
            PI.SPEAKER -> {
                line(4f, 9.5f, 8f, 9.5f, 13f, 5f, 13f, 19f, 8f, 14.5f, 4f, 14.5f, 4f, 9.5f)
                arc(14f, 12f, 4f, -50f, 100f)
                arc(14f, 12f, 7.5f, -50f, 100f)
            }
            PI.CLOSE -> { line(6.5f, 6.5f, 17.5f, 17.5f); line(17.5f, 6.5f, 6.5f, 17.5f) }
            PI.CHEVRON -> line(9.5f, 6f, 15.5f, 12f, 9.5f, 18f)
            PI.CHECK -> line(5f, 12.5f, 10f, 17.5f, 19f, 7f)
            PI.SHIELD -> {
                stroke {
                    m(12f, 3f); l(19.5f, 6f); l(19.5f, 11.5f); c(19.5f, 16.2f, 16.3f, 19.6f, 12f, 21f)
                    c(7.7f, 19.6f, 4.5f, 16.2f, 4.5f, 11.5f); l(4.5f, 6f); close()
                }
                line(8.8f, 12f, 11.2f, 14.4f, 15.4f, 9.8f)
            }
            PI.LOCK -> {
                box(5f, 10.5f, 14f, 10.5f, 2.5f)
                arc(12f, 8f, 4f, 180f, 180f)
                line(8f, 8f, 8f, 10.5f); line(16f, 8f, 16f, 10.5f)
                dot(12f, 15.2f, 1.5f); line(12f, 15.6f, 12f, 17.8f)
            }
            PI.KEY -> { ring(7.5f, 12f, 3.8f); line(11.3f, 12f, 20.5f, 12f); line(17.5f, 12f, 17.5f, 15.2f); line(14.7f, 12f, 14.7f, 14.4f) }
            PI.QR -> {
                box(3.5f, 3.5f, 7f, 7f, 1.5f); box(13.5f, 3.5f, 7f, 7f, 1.5f); box(3.5f, 13.5f, 7f, 7f, 1.5f)
                block(5.9f, 5.9f, 2.2f, 2.2f); block(15.9f, 5.9f, 2.2f, 2.2f); block(5.9f, 15.9f, 2.2f, 2.2f)
                block(13.5f, 13.5f, 2.6f, 2.6f); block(17.9f, 13.5f, 2.6f, 2.6f); block(15.7f, 15.7f, 2.6f, 2.6f)
                block(13.5f, 17.9f, 2.6f, 2.6f); block(17.9f, 17.9f, 2.6f, 2.6f)
            }
            PI.SCAN -> {
                line(3.5f, 8.5f, 3.5f, 3.5f, 8.5f, 3.5f); line(15.5f, 3.5f, 20.5f, 3.5f, 20.5f, 8.5f)
                line(20.5f, 15.5f, 20.5f, 20.5f, 15.5f, 20.5f); line(8.5f, 20.5f, 3.5f, 20.5f, 3.5f, 15.5f)
                line(7f, 12f, 17f, 12f)
            }
            PI.USER -> user(0f)
            PI.USERS -> {
                ring(9f, 8.2f, 3.3f)
                stroke { m(2.8f, 20f); c(2.8f, 16.2f, 5.6f, 14f, 9f, 14f); c(12.4f, 14f, 15.2f, 16.2f, 15.2f, 20f) }
                arc(16.6f, 8.8f, 2.7f, -120f, 240f)
                stroke { m(17.2f, 13.9f); c(19.7f, 14.3f, 21.3f, 16.2f, 21.3f, 19.4f) }
            }
            PI.NO_ACCOUNT -> { user(0f); line(4f, 3.5f, 20.5f, 20f) }
            PI.PIN -> {
                stroke {
                    m(12f, 21.3f); c(12f, 21.3f, 5f, 15f, 5f, 9.8f); c(5f, 5.9f, 8.1f, 2.8f, 12f, 2.8f)
                    c(15.9f, 2.8f, 19f, 5.9f, 19f, 9.8f); c(19f, 15f, 12f, 21.3f, 12f, 21.3f); close()
                }
                ring(12f, 9.8f, 2.6f)
            }
            PI.MAP -> {
                line(3.5f, 6.5f, 9f, 4.5f, 15f, 6.5f, 20.5f, 4.5f, 20.5f, 17.5f, 15f, 19.5f, 9f, 17.5f, 3.5f, 19.5f, 3.5f, 6.5f)
                line(9f, 4.5f, 9f, 17.5f); line(15f, 6.5f, 15f, 19.5f)
            }
            PI.SHARE -> {
                ring(17.5f, 5.5f, 2.6f); ring(6.5f, 12f, 2.6f); ring(17.5f, 18.5f, 2.6f)
                line(8.8f, 10.7f, 15.2f, 6.8f); line(8.8f, 13.3f, 15.2f, 17.2f)
            }
            PI.BIKE -> {
                // A motorcycle, not a bicycle: tank and seat, an engine block, a swingarm.
                ring(5.5f, 16.5f, 3.4f); ring(18.5f, 16.5f, 3.4f)
                line(18.5f, 16.5f, 15.6f, 8.6f)
                line(14.2f, 7.8f, 17.6f, 7.2f)
                line(4.2f, 10.4f, 8.8f, 10.4f, 11.2f, 9.2f, 15.4f, 9.6f)
                line(9.6f, 12.2f, 14.4f, 12.2f, 13.4f, 15.2f, 10.4f, 15.2f, 9.6f, 12.2f)
                line(5.5f, 16.5f, 10.4f, 14.6f)
                line(8.8f, 10.4f, 9.6f, 12.2f); line(14.4f, 12.2f, 16.4f, 11.2f)
            }
            PI.RELAY -> {
                ring(12f, 9f, 1.9f); line(12f, 11f, 12f, 21f); line(8.5f, 21f, 15.5f, 21f); line(9.5f, 21f, 12f, 14.5f, 14.5f, 21f)
                arc(12f, 9f, 5f, 140f, 80f); arc(12f, 9f, 5f, -40f, 80f)
                arc(12f, 9f, 8.5f, 145f, 70f); arc(12f, 9f, 8.5f, -35f, 70f)
            }
            PI.SIGNAL -> { block(3.5f, 15f, 3f, 5f); block(8.5f, 11.5f, 3f, 8.5f); block(13.5f, 8f, 3f, 12f); block(18.5f, 4f, 3f, 16f) }
            PI.WARNING -> {
                stroke { m(12f, 3.8f); l(21f, 19.6f); l(3f, 19.6f); close() }
                line(12f, 9.6f, 12f, 14f); dot(12f, 16.9f, 1.15f)
            }
            PI.CROWN -> {
                stroke { m(4f, 17.5f); l(3.5f, 8f); l(8.5f, 12f); l(12f, 5.5f); l(15.5f, 12f); l(20.5f, 8f); l(20f, 17.5f); close() }
                line(4.5f, 20.5f, 19.5f, 20.5f)
            }
            PI.FLAG -> {
                line(5.5f, 21f, 5.5f, 3.5f)
                stroke {
                    m(5.5f, 4.5f); c(8.5f, 3f, 11f, 6f, 14f, 5f); c(16f, 4.3f, 17.5f, 4f, 19f, 4.6f); l(19f, 13.4f)
                    c(17.5f, 12.8f, 16f, 13.1f, 14f, 13.8f); c(11f, 14.8f, 8.5f, 11.8f, 5.5f, 13.3f)
                }
            }
            PI.GEAR -> {
                ring(12f, 12f, 3f)
                stroke {
                    for (k in 0 until 8) {
                        val a = k * 45.0
                        val pts = doubleArrayOf(6.9, a - 17, 9.2, a - 9, 9.2, a + 9, 6.9, a + 17)
                        var i = 0
                        while (i < pts.size) {
                            val rad = Math.toRadians(pts[i + 1])
                            val x = (12 + pts[i] * Math.cos(rad)).toFloat()
                            val y = (12 + pts[i] * Math.sin(rad)).toFloat()
                            if (k == 0 && i == 0) m(x, y) else l(x, y)
                            i += 2
                        }
                    }
                    close()
                }
            }
            PI.CAMERA -> { box(2.5f, 7f, 13.5f, 10f, 2.2f); line(16f, 10.5f, 21.5f, 7.5f, 21.5f, 16.5f, 16f, 13.5f); ring(9.25f, 12f, 2.6f) }
            PI.LINK -> { box(2.5f, 8.5f, 10f, 7f, 3.5f); box(11.5f, 8.5f, 10f, 7f, 3.5f) }
            PI.PASTE -> {
                box(5f, 4.5f, 14f, 17f, 2.2f); box(8.5f, 2.8f, 7f, 3.6f, 1.2f)
                line(8.5f, 11f, 15.5f, 11f); line(8.5f, 14.5f, 13.5f, 14.5f)
            }
            PI.PHONES -> {
                box(2.5f, 4.5f, 7f, 15f, 1.8f); box(14.5f, 4.5f, 7f, 15f, 1.8f)
                line(10.6f, 10f, 13.4f, 10f); line(12.6f, 9.2f, 13.4f, 10f, 12.6f, 10.8f)
                line(13.4f, 14f, 10.6f, 14f); line(11.4f, 13.2f, 10.6f, 14f, 11.4f, 14.8f)
            }
            PI.PEN -> { line(4f, 20f, 4.5f, 16.5f, 15.5f, 5.5f, 18.5f, 8.5f, 7.5f, 19.5f, 4f, 20f); line(13.5f, 7.5f, 16.5f, 10.5f) }
            PI.CHAT -> line(4f, 5f, 20f, 5f, 20f, 16f, 9f, 16f, 4f, 20f, 4f, 5f)
            PI.BOLT -> line(13f, 2f, 4f, 14f, 11f, 14f, 10f, 22f, 19f, 10f, 12f, 10f, 13f, 2f)
            PI.SEND -> { line(4f, 12f, 20f, 4f, 14f, 20f, 11f, 14f, 4f, 12f); line(11f, 14f, 20f, 4f) }
            PI.STOP_SIGN -> { line(8f, 3f, 16f, 3f, 21f, 8f, 21f, 16f, 16f, 21f, 8f, 21f, 3f, 16f, 3f, 8f, 8f, 3f); line(8f, 12f, 16f, 12f) }
            PI.FUEL -> {
                box(4f, 3f, 10f, 18f, 2f); line(4f, 9f, 14f, 9f)
                line(14f, 8f, 17f, 10f, 17f, 17f); arc(19f, 17f, 2f, 0f, 180f); line(21f, 17f, 21f, 9f, 18f, 6f)
            }
            PI.GAUGE -> { arc(12f, 16f, 8f, 180f, 180f); line(12f, 16f, 8f, 11f); ring(12f, 16f, 1.4f) }
            PI.CLOCK -> { ring(12f, 12f, 9f); line(12f, 7f, 12f, 12f, 15f, 14f) }
            PI.PHOTO -> { line(4f, 8f, 7f, 8f, 9f, 5f, 15f, 5f, 17f, 8f, 20f, 8f, 20f, 19f, 4f, 19f, 4f, 8f); ring(12f, 13f, 3.5f) }
            PI.DOTS -> { dot(5f, 12f, 2f); dot(12f, 12f, 2f); dot(19f, 12f, 2f) }
            PI.MORE_VERT -> { dot(12f, 5f, 2f); dot(12f, 12f, 2f); dot(12f, 19f, 2f) }
            PI.MAILBOX -> { line(4f, 7f, 12f, 3f, 20f, 7f, 12f, 11f, 4f, 7f); line(4f, 7f, 4f, 17f, 12f, 21f, 20f, 17f, 20f, 7f); line(12f, 11f, 12f, 21f) }
            PI.DOUBLE_CHECK -> { line(1f, 12f, 6f, 17f, 15f, 7f); line(10f, 16f, 11f, 17f, 21f, 7f) }
            PI.TRASH -> {
                line(4f, 7f, 20f, 7f); line(6f, 7f, 7f, 20f, 17f, 20f, 18f, 7f)
                line(9f, 7f, 9f, 4f, 15f, 4f, 15f, 7f); line(10f, 11f, 10f, 16f); line(14f, 11f, 14f, 16f)
            }
        }
    }
}

/** A tiny closed padlock for drawings: [c] is its centre, [s] its height in pixels. */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPadlock(c: Offset, s: Float, color: Color) {
    val w = s * 0.78f
    val bodyH = s * 0.56f
    val top = c.y - s / 2 + s * 0.44f
    drawRoundRect(color, Offset(c.x - w / 2, top), Size(w, bodyH), CornerRadius(s * 0.12f))
    val r = w * 0.3f
    drawArc(color, 180f, 180f, false, Offset(c.x - r, top - r * 1.25f), Size(2 * r, 2 * r), style = Stroke(width = s * 0.12f))
    drawRoundRect(color, Offset(c.x - r - s * 0.06f, top - r * 0.3f), Size(s * 0.12f, r * 0.45f))
    drawRoundRect(color, Offset(c.x + r - s * 0.06f, top - r * 0.3f), Size(s * 0.12f, r * 0.45f))
}
