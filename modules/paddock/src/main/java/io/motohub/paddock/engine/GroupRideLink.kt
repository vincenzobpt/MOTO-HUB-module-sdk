// SPDX-License-Identifier: MPL-2.0
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
// of the MPL was not distributed with this file, you can obtain one at https://mozilla.org/MPL/2.0/.
package io.motohub.paddock.engine

import io.motohub.android.module.GroupRideMember
import io.motohub.android.module.GroupRideMessage
import io.motohub.android.module.GroupRideSnapshot
import io.motohub.android.module.ModuleGroupRide
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The room I ride with, for MOTO-HUB's dashboards (contract 48). The engine builds the snapshot
 * after every poll; [snapshot] only hands it over, so the render thread never waits for Python.
 */
internal class GroupRideLink(private val engine: PaddockEngine) : ModuleGroupRide {
    override fun snapshot(): GroupRideSnapshot? = engine.groupRide
    override fun sendQuick(kind: String): String? = engine.sendQuickToRiding(kind)
}

/** The pure part of the snapshot: no Android, so a JVM test can check it. */
internal object GroupRideBuild {
    /** Below this my heading is noise (standing still), so ahead/behind is not said. */
    const val MIN_HEADING_SPEED = 1.0

    /** The other riders of [room], nearest first (unknown distances last, by name). */
    fun members(room: Room, meLat: Double?, meLon: Double?, meBearing: Double?, meSpeed: Double?, nowSeconds: Long): List<GroupRideMember> =
        room.members.filter { !it.isMe }.map { m ->
            val lat = m.lat
            val lon = m.lon
            val known = meLat != null && meLon != null && lat != null && lon != null
            val distance = if (known) distanceMeters(meLat, meLon, lat, lon) else Double.NaN
            val ahead = if (known && meBearing != null && meSpeed != null && meSpeed > MIN_HEADING_SPEED) {
                val to = bearingDegrees(meLat, meLon, lat, lon)
                abs(((to - meBearing + 540.0) % 360.0) - 180.0) < 90.0
            } else null
            GroupRideMember(
                name = m.name,
                color = argb(m.color),
                speedMetersPerSecond = m.speed?.toFloat() ?: Float.NaN,
                distanceMeters = distance.toFloat(),
                ahead = ahead,
                ageSeconds = m.fixAt?.let { (nowSeconds - it).coerceAtLeast(0).toInt() } ?: -1
            )
        }.sortedWith(compareBy({ it.distanceMeters.isNaN() }, { it.distanceMeters }, { it.name }))

    /** Chat messages of the room without its own events, oldest first, the last [max]. */
    fun messages(chat: List<ChatMessage>, max: Int = 8): List<GroupRideMessage> =
        chat.filter { it.kind != ChatKind.EVENT }.takeLast(max).map { c ->
            GroupRideMessage(
                id = c.id, from = c.name, color = argb(c.color),
                quick = c.quick?.wire, text = if (c.kind == ChatKind.QUICK) "" else c.text, atMillis = c.atMillis, mine = c.mine,
                delivered = if (c.mine) c.delivery?.delivered ?: 0 else 0,
                total = if (c.mine) c.delivery?.total ?: 0 else 0
            )
        }

    /** "#RRGGBB" to opaque ARGB; anything else the first rider colour. */
    fun argb(color: String): Int =
        (color.takeIf { it.matches(Regex("#[0-9A-Fa-f]{6}")) } ?: RiderColors.first()).substring(1).toInt(16) or (0xFF shl 24)

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * 6_371_000.0 * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Initial bearing from point 1 to point 2, 0..360. */
    fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }
}
