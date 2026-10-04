// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// The rider's saved plans: one JSON file in the module's storage directory. org.json is the
// platform's. A file that cannot be read is treated as empty, never as a crash.
package io.motohub.android.routesim.core

import io.motohub.android.routesim.sim.DrivingProfile
import io.motohub.android.routesim.sim.RideStyle
import io.motohub.android.routesim.sim.TrafficLevel
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

class PlanStore(private val storageDir: File) {

    private val file: File get() = File(storageDir, FILE_NAME)
    private val lock = Any()

    /** Newest first. */
    fun all(): List<Plan> = synchronized(lock) { read().sortedByDescending { it.savedAtMillis } }

    /** Adds the plan, or replaces the one with the same id. */
    fun save(plan: Plan) = synchronized(lock) {
        val plans = read().filter { it.id != plan.id } + plan
        write(plans)
    }

    fun remove(id: String) = synchronized(lock) {
        val plans = read()
        if (plans.any { it.id == id }) write(plans.filter { it.id != id })
    }

    private fun read(): List<Plan> {
        val f = file
        if (!f.isFile) return emptyList()
        return try {
            val array = JSONObject(f.readText(Charsets.UTF_8)).optJSONArray(KEY_PLANS) ?: return emptyList()
            (0 until array.length()).mapNotNull { i ->
                try { planFromJson(array.getJSONObject(i)) } catch (e: Exception) { null }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun write(plans: List<Plan>) {
        storageDir.mkdirs()
        val root = JSONObject().put(KEY_VERSION, 1).put(KEY_PLANS, JSONArray().apply { plans.forEach { put(planToJson(it)) } })
        val tmp = File(storageDir, "$FILE_NAME.tmp")
        tmp.writeText(root.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "plans could not be written" }
        }
    }

    private fun planToJson(p: Plan): JSONObject = JSONObject()
        .put("id", p.id)
        .put("name", p.name)
        .put("savedAtMillis", p.savedAtMillis)
        .put("stops", JSONArray().apply {
            p.stops.forEach { put(JSONObject().put("lat", it.latitude).put("lon", it.longitude).put("label", it.label)) }
        })
        .put("settings", settingsToJson(p.settings))
        .apply { cleanTitleOverride(p.titleOverride)?.let { put("titleOverride", it) } }
        .apply { if (p.scenic) put("scenic", true) }

    private fun settingsToJson(s: RideSettings): JSONObject = JSONObject()
        .put("startAtMillis", s.startAtMillis)
        .put("style", s.style.name)
        .put("traffic", s.traffic.name)
        .put("profile", profileToJson(s.profile))

    private fun profileToJson(p: DrivingProfile): JSONObject = JSONObject()
        .put("style", p.style.name)
        .put("accelMs2", p.accelMs2)
        .put("brakeMs2", p.brakeMs2)
        .put("lateralAccelMs2", p.lateralAccelMs2)
        .put("overLimitFactor", p.overLimitFactor)
        .put("defaultCruiseKph", p.defaultCruiseKph)
        .put("maxLeanDeg", p.maxLeanDeg)
        .put("idleRpm", p.idleRpm)
        .put("maxRpm", p.maxRpm)
        .put("shiftUpRpm", p.shiftUpRpm)
        .put("shiftDownRpm", p.shiftDownRpm)
        .put("gearRatios", JSONArray().apply { p.gearRatios.forEach { put(it) } })

    private fun planFromJson(o: JSONObject): Plan {
        val stopsJson = o.getJSONArray("stops")
        val stops = (0 until stopsJson.length()).map {
            val s = stopsJson.getJSONObject(it)
            Stop(s.getDouble("lat"), s.getDouble("lon"), s.optString("label", ""))
        }
        return Plan(o.getString("id"), o.optString("name", ""), stops, settingsFromJson(o.getJSONObject("settings")), o.optLong("savedAtMillis", 0L),
            cleanTitleOverride(if (o.isNull("titleOverride")) null else o.optString("titleOverride", "")),
            // Files from before the scenic preference have no such key: they were all routed fastest.
            scenic = o.optBoolean("scenic", false))
    }

    private fun settingsFromJson(o: JSONObject): RideSettings {
        val style = enumOrNull<RideStyle>(o.optString("style")) ?: RideStyle.NORMAL
        val traffic = enumOrNull<TrafficLevel>(o.optString("traffic")) ?: TrafficLevel.LIGHT
        val profile = o.optJSONObject("profile")?.let { profileFromJson(it, style) } ?: DrivingProfile.generic(style)
        return RideSettings(o.getLong("startAtMillis"), style, traffic, profile)
    }

    /** Missing fields fall back on the generic profile of the style, so an older file still opens. */
    private fun profileFromJson(o: JSONObject, fallbackStyle: RideStyle): DrivingProfile {
        val style = enumOrNull<RideStyle>(o.optString("style")) ?: fallbackStyle
        val g = DrivingProfile.generic(style)
        val gears = o.optJSONArray("gearRatios")?.let { a ->
            DoubleArray(a.length()) { a.getDouble(it) }.takeIf { it.isNotEmpty() }
        } ?: g.gearRatios
        return DrivingProfile(
            style = style,
            accelMs2 = o.optDouble("accelMs2", g.accelMs2),
            brakeMs2 = o.optDouble("brakeMs2", g.brakeMs2),
            lateralAccelMs2 = o.optDouble("lateralAccelMs2", g.lateralAccelMs2),
            overLimitFactor = o.optDouble("overLimitFactor", g.overLimitFactor),
            defaultCruiseKph = o.optDouble("defaultCruiseKph", g.defaultCruiseKph),
            maxLeanDeg = o.optDouble("maxLeanDeg", g.maxLeanDeg),
            idleRpm = o.optDouble("idleRpm", g.idleRpm),
            maxRpm = o.optDouble("maxRpm", g.maxRpm),
            shiftUpRpm = o.optDouble("shiftUpRpm", g.shiftUpRpm),
            shiftDownRpm = o.optDouble("shiftDownRpm", g.shiftDownRpm),
            gearRatios = gears,
        )
    }

    private inline fun <reified E : Enum<E>> enumOrNull(name: String): E? =
        enumValues<E>().firstOrNull { it.name == name }

    private companion object {
        const val FILE_NAME = "plans.json"
        const val KEY_VERSION = "version"
        const val KEY_PLANS = "plans"
    }
}
