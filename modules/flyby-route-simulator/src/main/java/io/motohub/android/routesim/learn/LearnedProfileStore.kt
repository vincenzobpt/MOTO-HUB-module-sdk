// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// The learned profile: one JSON file in the module's storage directory. org.json is the
// platform's (as PlanStore). A file that cannot be read is treated as absent, never as a crash;
// "back to generic" is clear().
package io.motohub.android.routesim.learn

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject

class LearnedProfileStore(private val dir: File) {

    private val file: File get() = File(dir, FILE_NAME)
    private val lock = Any()

    /** The saved profile, or null when there is none or the file is damaged. */
    fun load(): LearnedProfile? = synchronized(lock) {
        val f = file
        if (!f.isFile) return null
        try {
            fromJson(JSONObject(String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)))
        } catch (e: Exception) {
            null
        }
    }

    fun save(p: LearnedProfile) = synchronized(lock) {
        dir.mkdirs()
        val tmp = File(dir, "$FILE_NAME.tmp")
        Files.write(tmp.toPath(), toJson(p).toString().toByteArray(StandardCharsets.UTF_8))
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "the learned profile could not be written" }
        }
    }

    /** Back to the generic profile. */
    fun clear() = synchronized(lock) {
        file.delete()
        File(dir, "$FILE_NAME.tmp").delete()
        Unit
    }

    private fun toJson(p: LearnedProfile): JSONObject {
        val o = JSONObject()
            .put(KEY_VERSION, 1)
            .put("learnedAtMillis", p.learnedAtMillis)
            .put("ridesUsed", p.ridesUsed)
            .put("kmUsed", finiteOrZero(p.kmUsed))
            .put("rpmRidesUsed", p.rpmRidesUsed)
        putIfFinite(o, "maxLeanDeg", p.maxLeanDeg)
        putIfFinite(o, "lateralAccelMs2", p.lateralAccelMs2)
        putIfFinite(o, "accelMs2", p.accelMs2)
        putIfFinite(o, "brakeMs2", p.brakeMs2)
        putIfFinite(o, "overLimitFactor", p.overLimitFactor)
        putIfFinite(o, "idleRpm", p.idleRpm)
        putIfFinite(o, "maxRpm", p.maxRpm)
        putIfFinite(o, "shiftUpRpm", p.shiftUpRpm)
        putIfFinite(o, "shiftDownRpm", p.shiftDownRpm)
        val gears = p.gearRatios
        if (gears != null && gears.isNotEmpty() && gears.all { it.isFinite() }) {
            o.put("gearRatios", JSONArray().apply { gears.forEach { put(it) } })
        }
        return o
    }

    private fun fromJson(o: JSONObject): LearnedProfile {
        val gears = o.optJSONArray("gearRatios")?.let { a ->
            DoubleArray(a.length()) { a.getDouble(it) }.also { g ->
                require(g.isNotEmpty() && g.all { r -> r.isFinite() && r > 0.0 })
            }
        }
        return LearnedProfile(
            learnedAtMillis = o.getLong("learnedAtMillis"),
            ridesUsed = o.getInt("ridesUsed"),
            kmUsed = o.getDouble("kmUsed"),
            rpmRidesUsed = o.getInt("rpmRidesUsed"),
            maxLeanDeg = optional(o, "maxLeanDeg"),
            lateralAccelMs2 = optional(o, "lateralAccelMs2"),
            accelMs2 = optional(o, "accelMs2"),
            brakeMs2 = optional(o, "brakeMs2"),
            overLimitFactor = optional(o, "overLimitFactor"),
            gearRatios = gears,
            idleRpm = optional(o, "idleRpm"),
            maxRpm = optional(o, "maxRpm"),
            shiftUpRpm = optional(o, "shiftUpRpm"),
            shiftDownRpm = optional(o, "shiftDownRpm"),
        )
    }

    private fun optional(o: JSONObject, key: String): Double? =
        if (o.has(key) && !o.isNull(key)) o.getDouble(key).also { require(it.isFinite()) } else null

    private fun putIfFinite(o: JSONObject, key: String, v: Double?) {
        if (v != null && v.isFinite()) o.put(key, v)
    }

    private fun finiteOrZero(v: Double): Double = if (v.isFinite()) v else 0.0

    private companion object {
        const val FILE_NAME = "learned-profile.json"
        const val KEY_VERSION = "version"
    }
}
