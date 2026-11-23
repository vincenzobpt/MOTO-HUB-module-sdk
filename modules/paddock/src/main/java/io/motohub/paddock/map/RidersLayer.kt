// SPDX-License-Identifier: MPL-2.0
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
// of the MPL was not distributed with this file, you can obtain one at https://mozilla.org/MPL/2.0/.
package io.motohub.paddock.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import io.motohub.android.module.MotoHubModuleHost
import io.motohub.android.module.ModuleMapLayer
import io.motohub.android.module.ModuleMapLayerPin
import io.motohub.paddock.engine.Freshness
import io.motohub.paddock.engine.Member
import io.motohub.paddock.engine.PaddockController

/**
 * The room's riders on the NAV map (contract 30), asked again every few seconds by an app at
 * contract 44 or later; an older app asks only when the map moves. Me excluded: the app draws me.
 */
internal class RidersLayer(
    private val host: MotoHubModuleHost,
    private val controller: PaddockController,
    private val featureId: String
) : ModuleMapLayer {

    override val title = "Paddock riders"

    override val refreshSeconds: Int get() = 5

    // People, not photos (contract 45): drawn above everything, never grouped or hidden, name beside.
    override val live: Boolean get() = true

    override fun pinsNear(latitude: Double, longitude: Double, radiusMeters: Int, limit: Int): Array<ModuleMapLayerPin> {
        val now = System.currentTimeMillis() / 1000
        // Every room's riders, each person once (the freshest fix wins when they share two rooms).
        val riders = controller.state.value.rooms.flatMap { it.members }
            .filter { !it.isMe }.groupBy { it.hash }.values.map { same -> same.maxBy { it.fixAt ?: 0 } }
        val pins = ArrayList<ModuleMapLayerPin>()
        for (m in riders) {
            if (m.isMe) continue
            val lat = m.lat ?: continue
            val lon = m.lon ?: continue
            val freshness = m.freshness(now)
            if (freshness == Freshness.NONE) continue
            pins.add(ModuleMapLayerPin(pinId(m), lat, lon, argb(m.color, freshness), "🏍", label(m, freshness, now)))
            if (pins.size >= limit) break
        }
        return pins.toTypedArray()
    }

    override fun onPinTap(pinId: String) = host.openFeature(featureId)

    /** The rider's avatar - their colour and initials - instead of a generic symbol (contract 31). */
    override fun pinImage(pinId: String, sizePx: Int): Bitmap? {
        // A plain loop: Sequence.flatMap is shrunk out of the app (checkModuleLinkage).
        var m: Member? = null
        for (room in controller.state.value.rooms) {
            m = room.members.firstOrNull { pinId(it) == pinId }
            if (m != null) break
        }
        if (m == null) return null
        val size = sizePx.coerceIn(24, 256)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = argb(m.color, Freshness.FRESH) }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, fill)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isLight(m.color)) 0xFF10141A.toInt() else 0xFFFFFFFF.toInt()
            textSize = size * 0.42f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(io.motohub.paddock.ui.initials(m.name), size / 2f, size / 2f - (text.descent() + text.ascent()) / 2f, text)
        return bitmap
    }

    // The colour joins the id: a rider who changes colour gets a new picture, not a cached old one.
    private fun pinId(m: Member) = "${m.hash}:${m.color}"

    // Short: an app before contract 45 draws it as a badge over the avatar.
    private fun label(m: Member, freshness: Freshness, now: Long): String {
        val name = m.name.substringBefore(' ').take(12)
        return if (freshness == Freshness.FRESH) name
        else "$name · ${((now - (m.fixAt ?: now)) / 60).coerceAtLeast(1)} min"
    }

    private fun argb(hex: String, freshness: Freshness): Int {
        val rgb = hex.removePrefix("#").toLongOrNull(16)?.toInt() ?: 0xFF7A1A
        // A stale position is drawn faded: still there, clearly not live.
        val alpha = if (freshness == Freshness.FRESH) 0xFF else 0x88
        return (alpha shl 24) or (rgb and 0xFFFFFF)
    }

    private fun isLight(hex: String): Boolean {
        val rgb = hex.removePrefix("#").toLongOrNull(16)?.toInt() ?: return false
        val r = rgb shr 16 and 0xFF
        val g = rgb shr 8 and 0xFF
        val b = rgb and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000 > 160
    }
}
