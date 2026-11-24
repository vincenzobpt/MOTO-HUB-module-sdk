// SPDX-License-Identifier: MPL-2.0
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
// of the MPL was not distributed with this file, you can obtain one at https://mozilla.org/MPL/2.0/.
package io.motohub.paddock.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import io.motohub.android.module.ModuleRideShortcut
import io.motohub.paddock.engine.PaddockController
import io.motohub.paddock.engine.QuickKind
import java.io.File

/**
 * "Go to your Paddock" on the Ride page (contract 46): always shown while Paddock is installed, so
 * the rider reaches it with or without a room. Read by
 * the app every second on the main thread, so every answer comes from memory or one small listing.
 * Loaded only by an app at 46 or later: an older app never asks for it.
 */
internal class RideShortcut(
    private val storage: File,
    private val controller: PaddockController,
    override val featureId: String
) : ModuleRideShortcut {

    // Python may not run yet (it starts when Paddock opens or a ride resumes): the room files say.
    private fun roomsOnDisk(): Int = File(storage, "rooms").list()?.count { it.endsWith(".json") } ?: 0

    override fun shown(): Boolean = true

    override fun title() = "Go to your Paddock"

    override fun subtitle(): String {
        val state = controller.state.value
        state.riding?.let { return "Riding with ${it.name}" }
        val count = state.rooms.size.takeIf { it > 0 } ?: roomsOnDisk()
        return when (count) {
            0 -> "Create or join a room"
            1 -> "1 room"
            else -> "$count rooms"
        }
    }

    override fun badge(): Int = controller.state.value.rooms.sumOf { it.unread }

    /** The newest unread quick message (Stop, Fuel, Emergency...) of the last 15 minutes. */
    private fun latestQuick() = controller.state.value.let { state ->
        val now = System.currentTimeMillis()
        state.rooms.mapNotNull { room ->
            room.last?.takeIf { it.quick && room.unread > 0 && it.fromHash != state.myHash && now - it.atMillis < ALERT_MILLIS }
        }.maxByOrNull { it.atMillis }
    }

    override fun alert(): String? = latestQuick()?.let { "${it.name}: ${it.text}" }

    override fun alertUrgent(): Boolean = latestQuick()?.text == QuickKind.EMERGENCY.label

    /** start_ride writes ride.json, so its time is when the sharing began, across restarts too. */
    override fun liveSinceMillis(): Long =
        if (controller.state.value.riding == null) 0L
        else File(storage, "ride.json").lastModified().takeIf { it > 0 } ?: 0L

    /** Paddock's mark: the pit-lane orange tile with the room flag. */
    override fun icon(sizePx: Int): Bitmap {
        val size = sizePx.coerceIn(24, 256).toFloat()
        val bitmap = Bitmap.createBitmap(size.toInt(), size.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawRoundRect(RectF(0f, 0f, size, size), size * 0.28f, size * 0.28f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF7A1A.toInt() })
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1C0D02.toInt()
            style = Paint.Style.STROKE
            strokeWidth = size * 0.075f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val u = size / 24f
        canvas.drawLine(7f * u, 19f * u, 7f * u, 5f * u, ink)
        canvas.drawPath(Path().apply {
            moveTo(7f * u, 5.5f * u)
            cubicTo(10f * u, 3.8f * u, 12.5f * u, 7.5f * u, 17.5f * u, 5.5f * u)
            lineTo(17.5f * u, 12.5f * u)
            cubicTo(12.5f * u, 14.5f * u, 10f * u, 10.8f * u, 7f * u, 12.5f * u)
        }, ink)
        return bitmap
    }

    private companion object {
        const val ALERT_MILLIS = 15 * 60_000L
    }
}
