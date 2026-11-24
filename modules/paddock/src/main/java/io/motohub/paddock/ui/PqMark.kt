// SPDX-License-Identifier: MPL-2.0
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
// of the MPL was not distributed with this file, you can obtain one at https://mozilla.org/MPL/2.0/.
package io.motohub.paddock.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableTarget
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.motohub.android.module.ModuleUi

/**
 * The post-quantum mark: a shield with an italic Q whose tail rests on the shield's curve.
 * 48-unit grid. The Q is the outline of Cormorant Garamond Italic 600 (SIL OFL 1.1, see NOTICE.md),
 * so no font ships with the module.
 */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun PqMark(size: Dp, modifier: Modifier = Modifier) {
    val fill = P.surface
    val edge = P.accent
    val q = P.ok
    Canvas(modifier.size(size)) {
        val u = this.size.width / 48f
        // Thicker edge when small, so it still reads at 16 dp.
        val w = (if (size < 32.dp) 3.2f else 2f) * u
        drawPath(shield(u, 24f, 3.5f, 40.5f, 9f, 23.5f, 33f, 44.5f), fill)
        drawPath(shield(u, 24f, 3.5f, 40.5f, 9f, 23.5f, 33f, 44.5f), edge, style = Stroke(w, join = StrokeJoin.Round))
        if (size >= 48.dp) {
            drawPath(shield(u, 24f, 7.2f, 37.2f, 11.6f, 23.5f, 31.2f, 40.8f), edge.copy(alpha = 0.35f), style = Stroke(0.6f * u))
        }
        drawPath(qGlyph(u), q)
    }
}

/** The Q alone, as tall as the text next to it: for the wordmark post·Q·uantum. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun PqGlyph(height: Dp, color: Color) {
    // The glyph spans x 14.6..36.4, y 9.8..37.8 of the 48 grid.
    Canvas(Modifier.size(width = height * (21.8f / 28f), height = height)) {
        val u = this.size.height / 28f
        translate(-14.6f * u, -9.8f * u) { drawPath(qGlyph(u), color) }
    }
}

/** "postQuantum" with the mark: tells that this room's messages are sealed with its own key. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun PqPill(onClick: () -> Unit) {
    Row(
        Modifier.heightIn(min = 32.dp).clip(RoundedCornerShape(16.dp)).background(P.okSoft)
            .clickable(role = Role.Button, onClickLabel = "What post-quantum means", onClick = onClick)
            .padding(start = 6.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        PqMark(22.dp)
        PqWordmark(P.okText)
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun PqWordmark(color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Txt("post", Ty.SMALL, color, maxLines = 1)
        PqGlyph(15.dp, P.ok)
        Txt("uantum", Ty.SMALL_STRONG, color, maxLines = 1)
    }
}

/** The explanation behind the mark, for a [Sheet]. */
internal const val PQ_EXPLAINED =
    "Messages, quick messages and positions in this room are sealed with the room's own key before " +
        "Reticulum encrypts them again. The key travels only in the room's code and link, never through " +
        "Reticulum: even a future quantum computer that breaks today's encryption finds a sealed message.\n\n" +
        "So the code is the room's password: show it and send the link only to your riders. " +
        "Calls are not covered, and who talks to whom stays visible to the relays as before."

private fun shield(u: Float, cx: Float, top: Float, right: Float, shoulder: Float, waist: Float, curve: Float, tip: Float): Path {
    val left = 2 * cx - right
    return Path().apply {
        moveTo(cx * u, top * u)
        lineTo(right * u, shoulder * u)
        lineTo(right * u, waist * u)
        cubicTo(right * u, curve * u, (cx + (right - cx) * 0.55f) * u, (tip - 4f) * u, cx * u, tip * u)
        cubicTo((cx - (cx - left) * 0.55f) * u, (tip - 4f) * u, left * u, curve * u, left * u, waist * u)
        lineTo(left * u, shoulder * u)
        close()
    }
}

/** Cormorant Garamond Italic 600 "Q", 33 units tall on a baseline at y 31, centred on x 24. */
private fun qGlyph(u: Float): Path = Path().apply {
    fun m(x: Float, y: Float) = moveTo(x * u, y * u)
    fun l(x: Float, y: Float) = lineTo(x * u, y * u)
    fun q(x1: Float, y1: Float, x2: Float, y2: Float) = quadraticTo(x1 * u, y1 * u, x2 * u, y2 * u)
    // Tail.
    m(33.61f, 37.55f); q(32.13f, 37.55f, 30.62f, 36.79f); q(29.11f, 36.03f, 27.72f, 34.54f); q(26.33f, 33.05f, 25.22f, 30.89f)
    l(27.93f, 29.59f); q(28.51f, 31.17f, 29.39f, 32.5f); q(30.27f, 33.84f, 31.28f, 34.82f); q(32.3f, 35.8f, 33.25f, 36.33f)
    q(34.2f, 36.86f, 34.94f, 36.86f); q(35.23f, 36.86f, 35.52f, 36.83f); q(35.8f, 36.79f, 36.03f, 36.73f); q(36.13f, 36.7f, 36.18f, 36.88f)
    q(36.23f, 37.05f, 36.16f, 37.1f); q(35.5f, 37.31f, 34.83f, 37.43f); q(34.16f, 37.55f, 33.61f, 37.55f); close()
    // Bowl.
    m(22.52f, 31.4f); q(19.77f, 31.4f, 17.97f, 29.96f); q(16.17f, 28.52f, 15.47f, 26.07f); q(14.77f, 23.63f, 15.29f, 20.67f)
    q(15.68f, 18.35f, 16.74f, 16.4f); q(17.8f, 14.46f, 19.35f, 13.02f); q(20.9f, 11.59f, 22.79f, 10.8f); q(24.68f, 10.01f, 26.75f, 10.01f)
    q(29.58f, 10.01f, 31.41f, 11.44f); q(33.23f, 12.88f, 33.96f, 15.29f); q(34.68f, 17.7f, 34.13f, 20.67f); q(33.7f, 23.14f, 32.57f, 25.12f)
    q(31.44f, 27.09f, 29.84f, 28.49f); q(28.25f, 29.89f, 26.37f, 30.64f); q(24.49f, 31.4f, 22.52f, 31.4f); close()
    // Counter.
    m(23.93f, 30.54f); q(26.43f, 30.54f, 28.33f, 28.38f); q(30.22f, 26.22f, 30.9f, 22.42f); q(31.34f, 20.09f, 31.16f, 18f)
    q(30.99f, 15.91f, 30.28f, 14.29f); q(29.56f, 12.67f, 28.34f, 11.74f); q(27.12f, 10.8f, 25.45f, 10.8f); q(22.9f, 10.8f, 21.03f, 12.99f)
    q(19.16f, 15.17f, 18.48f, 18.92f); q(18.09f, 21.17f, 18.25f, 23.25f); q(18.41f, 25.34f, 19.1f, 26.98f); q(19.79f, 28.62f, 21.01f, 29.58f)
    q(22.23f, 30.54f, 23.93f, 30.54f); close()
}
