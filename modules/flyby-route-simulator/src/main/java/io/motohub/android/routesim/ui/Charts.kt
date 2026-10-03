// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// The four charts of the preview, drawn on a plain Canvas, with one cursor shared between them.
package io.motohub.android.routesim.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableTarget
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.motohub.android.module.ModuleUi
import io.motohub.android.routesim.core.PreviewSeries

private const val PAD_DP = 8f

/**
 * One chart of the ride against its time. [cursor] (0..1 of the ride's time) is shared by all the
 * charts: a tap or a drag on any of them moves it through [onCursor], and the others follow.
 */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun RideChart(
    title: String,
    kind: ChartKind,
    series: PreviewSeries,
    values: FloatArray,
    lineColor: Color,
    cursor: Float,
    onCursor: (Float) -> Unit
) {
    val times = series.timesSeconds
    val range = remember(values, kind) { ChartMath.axis(values, kind) }
    val index = ChartMath.cursorIndex(times, cursor)
    val current = if (index >= 0 && index < values.size) values[index] else Float.NaN
    val paint = remember { android.graphics.Paint().apply { isAntiAlias = true } }
    val moveCursor by rememberUpdatedState(onCursor)

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Txt(title, modifier = Modifier.weight(1f), color = RsColors.Text2, size = 15, weight = FontWeight.Bold)
            Txt(chartReadout(kind, current), color = lineColor, size = 17, weight = FontWeight.Bold)
        }
        Box(Modifier.fillMaxWidth().height(116.dp).clip(RsShape12).background(RsColors.Surface)) {
            if (range == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Txt(Strings.NO_DATA, color = RsColors.Dim, size = 15)
                }
            } else {
                Canvas(
                    Modifier.fillMaxSize()
                        .pointerInput(Unit) {
                            val pad = PAD_DP.dp.toPx()
                            detectTapGestures { p -> moveCursor(ChartMath.fractionOfX(p.x, size.width.toFloat(), pad, pad)) }
                        }
                        .pointerInput(Unit) {
                            val pad = PAD_DP.dp.toPx()
                            detectHorizontalDragGestures(
                                onDragStart = { p -> moveCursor(ChartMath.fractionOfX(p.x, size.width.toFloat(), pad, pad)) },
                                onDragEnd = { },
                                onDragCancel = { },
                                onHorizontalDrag = { change, _ -> moveCursor(ChartMath.fractionOfX(change.position.x, size.width.toFloat(), pad, pad)) }
                            )
                        }
                ) {
                    val w = size.width
                    val h = size.height
                    val pad = PAD_DP * density
                    val top = 8f * density
                    val plotH = h - 16f * density

                    // Three guide lines; the middle one is the zero of the lean chart.
                    for (k in 0..2) {
                        val y = top + plotH * k / 2f
                        drawLine(RsColors.Line.copy(alpha = 0.6f), Offset(0f, y), Offset(w, y), 1f * density)
                    }
                    if (kind == ChartKind.LEAN) {
                        val y0 = ChartMath.yOfValue(0f, range, top, plotH)
                        drawLine(RsColors.Text2.copy(alpha = 0.5f), Offset(0f, y0), Offset(w, y0), 1.5f * density)
                    }

                    // The line, broken where the value is unknown.
                    val n = Math.min(values.size, times.size)
                    val path = Path()
                    var pen = false
                    for (i in 0 until n) {
                        val v = values[i]
                        if (v.isNaN() || v.isInfinite()) {
                            pen = false
                            continue
                        }
                        val x = ChartMath.xOfFraction(ChartMath.fractionOfIndex(times, i), w, pad, pad)
                        val y = ChartMath.yOfValue(v, range, top, plotH)
                        if (!pen) {
                            path.moveTo(x, y)
                            pen = true
                        } else {
                            path.lineTo(x, y)
                        }
                    }
                    drawPath(path, lineColor, style = Stroke(2.5f * density))

                    // The cursor: a vertical bar and a dot on the line.
                    val cx = ChartMath.xOfFraction(ChartMath.fractionOfIndex(times, index), w, pad, pad)
                    drawLine(Color.White, Offset(cx, top), Offset(cx, top + plotH), 2f * density)
                    if (!current.isNaN() && !current.isInfinite()) {
                        val cy = ChartMath.yOfValue(current, range, top, plotH)
                        drawCircle(lineColor, 6f * density, Offset(cx, cy))
                        drawCircle(Color.White, 6f * density, Offset(cx, cy), style = Stroke(2f * density))
                    }

                    // The two ends of the axis.
                    drawIntoCanvas { c ->
                        paint.textSize = 11f * density
                        paint.color = RsColors.Dim.toArgb()
                        c.nativeCanvas.drawText(axisLabel(range.max), pad, top + 10f * density, paint)
                        c.nativeCanvas.drawText(axisLabel(range.min), pad, top + plotH - 2f * density, paint)
                    }
                }
            }
        }
    }
}
