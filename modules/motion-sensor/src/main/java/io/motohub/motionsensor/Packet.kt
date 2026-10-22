// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.motionsensor

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * One sample from the sensor's third characteristic (firmware V6, packet version 1): 20 bytes,
 * little-endian. The layout is written out at the top of the firmware file.
 */
class Packet(
    val version: Int,
    val alert: Boolean,
    val sensorOk: Boolean,
    val watchingFall: Boolean,
    val seq: Int,
    val sensorMillis: Long,
    /** Degrees, + = right. */
    val leanDeg: Float,
    /** Degrees, + = nose up once [oriented] agrees with the mounting. */
    val pitchDeg: Float,
    val yawDeg: Float,
    val longG: Float,
    /** + = towards the right. */
    val latG: Float,
    val vertG: Float
) {
    /**
     * Takes out the box's own tilt, measured by [Calibration] with the bike upright and still. The
     * firmware zeroes the lean and the forward g itself; the box rolled on its mount still puts
     * gravity on the sideways axis, and nothing zeroes the pitch.
     */
    fun calibrated(c: Calibration?): Packet {
        if (c == null) return this
        val cs = cos(c.rollRad)
        val sn = sin(c.rollRad)
        return Packet(
            version, alert, sensorOk, watchingFall, seq, sensorMillis,
            leanDeg, pitchDeg - c.pitchDeg, yawDeg, longG, latG * cs - vertG * sn, latG * sn + vertG * cs
        )
    }

    /** The rider's two switches: the firmware cannot know how the box sits on the bike. */
    fun oriented(flipNose: Boolean, flipSide: Boolean): Packet {
        val n = if (flipNose) -1f else 1f
        val s = if (flipSide) -1f else 1f
        return Packet(
            version, alert, sensorOk, watchingFall, seq, sensorMillis,
            leanDeg * s, pitchDeg * n, yawDeg, longG * n, latG * s, vertG
        )
    }

    companion object {
        const val SIZE = 20

        fun decode(bytes: ByteArray): Packet? {
            if (bytes.size < SIZE || bytes[0].toInt() != 1) return null
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val version = b.get().toInt()
            val bits = b.get().toInt()
            val seq = b.short.toInt() and 0xFFFF
            val millis = b.int.toLong() and 0xFFFFFFFFL
            return Packet(
                version = version,
                alert = bits and 0x01 != 0,
                sensorOk = bits and 0x02 != 0,
                watchingFall = bits and 0x04 != 0,
                seq = seq,
                sensorMillis = millis,
                leanDeg = b.short / 100f,
                // Version 1 sends the WitMotion pitch, a right-hand turn about an axis pointing
                // left: + = nose down. Turned round here so pitch and acceleration agree.
                pitchDeg = -b.short / 100f,
                yawDeg = b.short / 100f,
                longG = b.short / 1000f,
                latG = b.short / 1000f,
                vertG = b.short / 1000f
            )
        }

        /**
         * Packets missing between [previous] and [seq], which wraps at 65535. A jump larger than
         * a few seconds of packets is the ESP32 restarting, not loss, and counts as none.
         */
        fun lostBetween(previous: Int, seq: Int): Int {
            val gap = ((seq - previous - 1) and 0xFFFF)
            return if (gap > 500) 0 else gap
        }
    }
}

/** The box's tilt on its mount, in the units of a decoded [Packet] before [Packet.oriented]. */
class Calibration(val rollRad: Float, val pitchDeg: Float, val takenAt: Long) {
    companion object {
        /** From the mean sideways g, vertical g and pitch of the bike standing upright. */
        fun of(latG: Float, vertG: Float, pitchDeg: Float, takenAt: Long) =
            Calibration(atan2(latG, vertG), pitchDeg, takenAt)
    }
}
