// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Vincenzo Buonomano.
// Part of the MOTO-HUB module SDK; see module-api/LICENSE.
package io.motohub.dawarich

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlandTest {

    private fun fix(accuracy: Float = 5f, battery: Float = 0.5f) =
        Fix(45.5, 9.25, 1_704_067_200L, accuracy, 120.4, 13.26f, 270f, battery, false)

    @Test
    fun coordinatesAreLongitudeFirst() {
        val c = Overland.feature(fix()).getJSONObject("geometry").getJSONArray("coordinates")
        assertEquals(9.25, c.getDouble(0), 0.0)
        assertEquals(45.5, c.getDouble(1), 0.0)
    }

    @Test
    fun propertiesCarryTimestampSpeedAndBattery() {
        val p = Overland.feature(fix()).getJSONObject("properties")
        assertEquals("2024-01-01T00:00:00Z", p.getString("timestamp"))
        assertEquals(13.3, p.getDouble("speed"), 0.0)
        assertEquals(5, p.getInt("horizontal_accuracy"))
        assertEquals("unplugged", p.getString("battery_state"))
    }

    @Test
    fun unknownValuesAreLeftOut() {
        val unknown = Fix(1.0, 2.0, 1L, -1f, Double.NaN, -1f, -1f, -1f, false)
        val p = Overland.feature(unknown).getJSONObject("properties")
        assertFalse(p.has("horizontal_accuracy") || p.has("altitude") || p.has("speed") ||
            p.has("course") || p.has("battery_level"))
    }

    @Test
    fun batchWrapsFeaturesWithDeviceId() {
        val body = JSONObject(Overland.batch(listOf(Overland.feature(fix()), Overland.feature(fix()))))
        assertEquals(2, body.getJSONArray("locations").length())
        assertTrue(body.getString("device_id").isNotEmpty())
    }
}
