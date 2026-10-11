// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Vincenzo Buonomano.
// Part of the MOTO-HUB module SDK; see module-api/LICENSE.
package io.motohub.dawarich

import java.io.File
import java.util.Properties

/** A ride sent whole to a server: when, how many points, and to which address. */
class SentRide(val atMillis: Long, val points: Int, val server: String)

/**
 * The rides "Send to Dawarich" delivered in full, kept so a second send can say it is one.
 * A send cut short is not remembered: the ride is not on the server yet.
 */
class SentRides(private val file: File) {

    @Synchronized
    fun get(rideId: String): SentRide? {
        val line = read().getProperty(rideId) ?: return null
        val parts = line.split('|', limit = 3)
        if (parts.size < 3) return null
        val at = parts[0].toLongOrNull() ?: return null
        return SentRide(at, parts[1].toIntOrNull() ?: 0, parts[2])
    }

    @Synchronized
    fun mark(rideId: String, ride: SentRide) {
        val p = read()
        p.setProperty(rideId, "${ride.atMillis}|${ride.points}|${ride.server}")
        file.outputStream().use { p.store(it, null) }
    }

    private fun read(): Properties = Properties().also { p ->
        if (file.isFile) file.inputStream().use { p.load(it) }
    }
}
