// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.dashcam.plugin

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import io.motohub.android.module.ModuleProjectionTile

/**
 * The camera's tile among the dashboard's map sources: the road through a viewfinder, with the
 * red REC light, so it reads as a camera at a glance and never as the projected-screen picture.
 * Everything is allocated once; the app calls these every frame while the tile animates.
 */
internal object DashcamTile : ModuleProjectionTile {

    override val accent: Int = 0xFFFF5A5F.toInt()

    override val caption: String = "The dashcam's live picture; tap the panel for the camera's full view"

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFakeBoldText = true }
    private val path = Path()
    private val rect = RectF()

    override fun drawPicture(canvas: Canvas, width: Float, height: Float, accent: Int, phase: Float) {
        val w = width
        val h = height
        val horizon = h * 0.46f
        // Night sky, dark ground.
        fill.color = 0xFF0B1320.toInt()
        canvas.drawRect(0f, 0f, w, horizon, fill)
        fill.color = 0xFF10161C.toInt()
        canvas.drawRect(0f, horizon, w, h, fill)
        // The road running to the horizon.
        fill.color = 0xFF222C35.toInt()
        path.reset()
        path.moveTo(w * 0.06f, h)
        path.lineTo(w * 0.47f, horizon)
        path.lineTo(w * 0.53f, horizon)
        path.lineTo(w * 0.94f, h)
        path.close()
        canvas.drawPath(path, fill)
        // Centre dashes coming towards the camera.
        line.color = 0xFFE8EDF2.toInt()
        for (i in 0 until 4) {
            val t = ((i + phase) / 4f) % 1f
            val y0 = horizon + (h - horizon) * t * t
            val y1 = horizon + (h - horizon) * Math.min(t + 0.07f, 1f).let { it * it }
            line.strokeWidth = h * (0.01f + 0.03f * t)
            canvas.drawLine(w * 0.5f, y0, w * 0.5f, y1, line)
        }
        // Viewfinder corners.
        line.color = 0xCCFFFFFF.toInt()
        line.strokeWidth = h * 0.025f
        val m = h * 0.08f
        val l = h * 0.16f
        corner(canvas, m, m, l, l)
        corner(canvas, w - m, m, -l, l)
        corner(canvas, m, h - m, l, -l)
        corner(canvas, w - m, h - m, -l, -l)
        // REC, its light blinking while the tile is chosen.
        val on = phase % 0.5f < 0.3f
        fill.color = if (on) accent else 0xFF5A2A2D.toInt()
        val r = h * 0.05f
        canvas.drawCircle(m + l * 0.55f + r, m + l * 0.55f + r, r, fill)
        text.color = 0xFFFFFFFF.toInt()
        text.textSize = h * 0.12f
        canvas.drawText("REC", m + l * 0.55f + r * 2.8f, m + l * 0.55f + r * 1.75f, text)
    }

    private fun corner(canvas: Canvas, x: Float, y: Float, dx: Float, dy: Float) {
        canvas.drawLine(x, y, x + dx, y, line)
        canvas.drawLine(x, y, x, y + dy, line)
    }

    /** A video camera: the body and the lens hood. */
    override fun drawMark(canvas: Canvas, size: Float, color: Int) {
        val s = size
        line.color = color
        line.strokeWidth = s * 0.11f
        rect.set(s * 0.08f, s * 0.28f, s * 0.64f, s * 0.74f)
        canvas.drawRoundRect(rect, s * 0.08f, s * 0.08f, line)
        path.reset()
        path.moveTo(s * 0.64f, s * 0.44f)
        path.lineTo(s * 0.92f, s * 0.30f)
        path.lineTo(s * 0.92f, s * 0.72f)
        path.lineTo(s * 0.64f, s * 0.58f)
        canvas.drawPath(path, line)
        fill.color = color
        canvas.drawCircle(s * 0.22f, s * 0.40f, s * 0.05f, fill)
    }
}
