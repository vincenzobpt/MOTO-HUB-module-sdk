// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.motionsensor

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.motohub.android.module.ModuleFeature
import io.motohub.android.module.ModuleFeaturePlacement
import io.motohub.android.module.ModuleFeatures
import io.motohub.android.module.ModuleManifest
import io.motohub.android.module.MotoHubModule
import io.motohub.android.module.MotoHubModuleEntry
import io.motohub.android.module.MotoHubModuleHost
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

class MotionSensorEntry : MotoHubModuleEntry {
    override fun create(host: MotoHubModuleHost): MotoHubModule = MotionSensorModule(host)
}

private class MotionSensorModule(private val host: MotoHubModuleHost) : MotoHubModule, ModuleFeatures {

    // Must agree with motohubModule { } in build.gradle.kts.
    override val manifest = ModuleManifest(
        id = "motion-sensor",
        version = "0.1.2",
        contractVersion = 37,
        entryClass = "io.motohub.motionsensor.MotionSensorEntry",
        displayName = "Motion sensor",
        description = "Diagnostics for the ESP32 motion sensor: lean, pitch and g-forces over Bluetooth."
    )

    private val mountingFile = File(host.storage, "mounting.properties")

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> capability(type: Class<T>): T? = when (type) {
        ModuleFeatures::class.java -> this as T
        else -> null
    }

    override fun release() {}

    override fun features(): List<ModuleFeature> = listOf(
        ModuleFeature(
            id = "motion-sensor-diagnostics",
            title = "Motion sensor",
            description = "Live values from the sensor and its commands",
            placement = ModuleFeaturePlacement.MODULES,
            screen = { onBack -> Diagnostics(onBack) }
        )
    )

    @Composable
    private fun Diagnostics(onBack: () -> Unit) {
        val ui = host.ui
        val link = remember { SensorLink(host.context, File(host.storage, "calibration.properties")) { host.log.log(it) } }
        var tick by remember { mutableStateOf(0) }
        var mounting by remember { mutableStateOf(readMounting()) }

        LaunchedEffect(Unit) {
            link.note("Page opened (module ${manifest.version}, app ${host.context.packageName})")
            link.connect()
            // 50 packets a second is too many to redraw; ten times a second reads as live.
            while (true) { delay(100); tick++ }
        }
        DisposableEffect(Unit) { onDispose { link.disconnect() } }

        // Reading tick is what redraws the page; the packet itself is not Compose state.
        val p = link.latest.takeIf { tick >= 0 }?.calibrated(link.calibration)?.oriented(mounting.first, mounting.second)

        ui.Screen("Motion sensor", onBack) {
            ui.SectionLabel("SENSOR")
            ui.Fact("Status", link.status, false)
            link.deviceLabel?.let { ui.Fact("Device", it, true) }
            if (!link.hasPermissions) {
                ui.Paragraph("MOTO-HUB needs the Nearby devices permission to find the sensor.")
                ui.ActionRow("Open MOTO-HUB's app settings", "Permissions ▸ Nearby devices ▸ Allow") {
                    host.context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", host.context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
            if (link.status == SensorLink.NOT_FOUND) {
                ui.Paragraph(
                    "The sensor only shows itself while nothing else is connected to it. Close nRF Connect " +
                        "(or tap Disconnect there), make sure the ESP32 is powered, then tap Connect."
                )
            }
            ui.PrimaryButton(if (link.connected) "Disconnect" else "Connect", !link.status.endsWith("…")) {
                if (link.connected) link.disconnect() else { link.disconnect(); link.connect() }
            }

            ui.SectionLabel("LIVE")
            ui.Fact("Lean", p?.let { lean(it.leanDeg) } ?: "–", false)
            ui.Fact("Pitch", p?.let { pitch(it.pitchDeg) } ?: "–", false)
            ui.Fact("Acceleration / braking", p?.let { g(it.longG) } ?: "–", false)
            ui.Fact("Sideways", p?.let { g(it.latG) } ?: "–", false)
            ui.Fact("Vertical", p?.let { g(it.vertG) } ?: "–", false)
            ui.Fact("Heading", p?.let { "${it.yawDeg.roundToInt()}°" } ?: "–", false)

            ui.SectionLabel("LINK")
            ui.Fact("Packets per second", if (link.connected) link.packetsPerSecond.toString() else "–", false)
            ui.Fact("Received / lost", "${link.received} / ${link.lost}", false)
            ui.Fact("Sensor", p?.let { if (it.sensorOk) "OK" else "No data from the sensor" } ?: "–", false)
            ui.Fact("Fall alert", p?.let { if (it.alert) "ALERT" else if (it.watchingFall) "Watching" else "No" } ?: "–", false)
            p?.let {
                ui.Fact("Packet version", it.version.toString(), true)
                ui.Fact("Sensor clock", "${it.sensorMillis / 1000} s", true)
            }

            ui.SectionLabel("COMMANDS")
            ui.ActionRow(
                "Calibrate",
                "Bike upright and still. Zeroes lean, pitch and g-forces; also resets the statistics. " +
                    (link.calibration?.let { "Last: " + SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(it.takenAt)) }
                        ?: "Not calibrated yet: sideways g includes the box's tilt.")
            ) { link.calibrate() }
            ui.ActionRow("Cancel the fall alert", "Tells the sensor the rider is fine") { link.send("R") }
            ui.ActionRow("Reset the statistics", "Maximum lean and alert count back to zero") { link.send("Z") }
            ui.ActionRow("Read the statistics", link.stats ?: "Maximum lean each side, alerts, time on") { link.readStats() }

            ui.SectionLabel("RECENT EVENTS")
            ui.Paragraph(link.recent().joinToString("\n").ifEmpty { "Nothing yet." })
            ui.Paragraph("Everything above is also in MOTO-HUB's diagnostics report.")

            ui.SectionLabel("MOUNTING")
            ui.Paragraph("If tilting the nose up shows \"nose down\", or leaning right shows \"left\", switch the matching row on.")
            ui.ActionRow("Nose up reads as down", if (mounting.first) "On: pitch and acceleration are flipped" else "Off") {
                mounting = Pair(!mounting.first, mounting.second).also(::writeMounting)
            }
            ui.ActionRow("Right reads as left", if (mounting.second) "On: lean and sideways are flipped" else "Off") {
                mounting = Pair(mounting.first, !mounting.second).also(::writeMounting)
            }
        }
    }

    private fun lean(deg: Float): String {
        val d = abs(deg).roundToInt()
        return if (d == 0) "0°" else "$d° ${if (deg > 0) "right" else "left"}"
    }

    private fun pitch(deg: Float): String {
        val d = abs(deg).roundToInt()
        return if (d == 0) "0°" else "$d° nose ${if (deg > 0) "up" else "down"}"
    }

    private fun g(value: Float) = String.format(Locale.ROOT, "%+.2f g", value)

    private fun readMounting(): Pair<Boolean, Boolean> {
        val p = Properties()
        if (mountingFile.isFile) mountingFile.inputStream().use(p::load)
        return (p.getProperty("flipNose") == "true") to (p.getProperty("flipSide") == "true")
    }

    private fun writeMounting(m: Pair<Boolean, Boolean>) {
        val p = Properties()
        p.setProperty("flipNose", m.first.toString())
        p.setProperty("flipSide", m.second.toString())
        mountingFile.outputStream().use { p.store(it, null) }
        host.log.log("Motion sensor: mounting flipNose=${m.first} flipSide=${m.second}")
    }
}
