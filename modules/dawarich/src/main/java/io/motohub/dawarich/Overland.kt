// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.dawarich

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** One fix, in the units Android reports. NaN / negative mean "not known". */
class Fix(
    val latitude: Double,
    val longitude: Double,
    val epochSeconds: Long,
    val accuracyMeters: Float,
    val altitudeMeters: Double,
    val speedMetersPerSecond: Float,
    val bearingDegrees: Float,
    val batteryFraction: Float,
    val charging: Boolean
)

/** Dawarich's Overland endpoint: `POST /api/v1/overland/batches`, GeoJSON features. */
object Overland {

    const val DEVICE_ID = "moto-hub"

    fun feature(fix: Fix): JSONObject {
        val props = JSONObject()
        props.put("timestamp", Instant.ofEpochSecond(fix.epochSeconds).toString())
        if (fix.accuracyMeters >= 0f) props.put("horizontal_accuracy", Math.round(fix.accuracyMeters))
        if (!fix.altitudeMeters.isNaN()) props.put("altitude", Math.round(fix.altitudeMeters))
        if (fix.speedMetersPerSecond >= 0f) props.put("speed", Math.round(fix.speedMetersPerSecond * 10f) / 10.0)
        if (fix.bearingDegrees >= 0f) props.put("course", fix.bearingDegrees.toDouble())
        if (fix.batteryFraction >= 0f) {
            props.put("battery_level", fix.batteryFraction.toDouble())
            props.put("battery_state", if (fix.charging) "charging" else "unplugged")
        }
        val coordinates = JSONArray()
        coordinates.put(fix.longitude) // GeoJSON order: lon, lat
        coordinates.put(fix.latitude)
        val geometry = JSONObject()
        geometry.put("type", "Point")
        geometry.put("coordinates", coordinates)
        val feature = JSONObject()
        feature.put("type", "Feature")
        feature.put("geometry", geometry)
        feature.put("properties", props)
        return feature
    }

    /** [features] are queued lines, each one a [feature]. */
    fun batch(features: List<JSONObject>): String {
        val locations = JSONArray()
        for (f in features) locations.put(f)
        val body = JSONObject()
        body.put("locations", locations)
        body.put("device_id", DEVICE_ID)
        return body.toString()
    }
}
