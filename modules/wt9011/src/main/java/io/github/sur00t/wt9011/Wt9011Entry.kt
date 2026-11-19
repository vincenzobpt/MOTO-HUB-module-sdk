// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 sur00t (https://github.com/sur00t), who wrote the WT9011 module.
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.github.sur00t.wt9011

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.motohub.android.module.ModuleFeature
import io.motohub.android.module.ModuleFeaturePlacement
import io.motohub.android.module.ModuleFeatures
import io.motohub.android.module.ModuleLiveState
import io.motohub.android.module.ModuleLiveStatus
import io.motohub.android.module.ModuleManifest
import io.motohub.android.module.ModuleMotionListener
import io.motohub.android.module.ModuleMotionSource
import io.motohub.android.module.MotoHubModule
import io.motohub.android.module.MotoHubModuleEntry
import io.motohub.android.module.MotoHubModuleHost
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The module the app loads: a WT9011DCL on the bike, read directly over Bluetooth LE.
 *
 * The sensor is a BLE peripheral that streams as soon as somebody subscribes, so this module is the
 * central: it scans for it, connects, subscribes and decodes. Nothing else is needed between the
 * sensor and the phone.
 *
 * Three surfaces, in increasing order of how much they cost the rider's attention:
 *
 * - The screen, which is the bench: raw channels, processed lean, the two mounting switches, the
 *   zero, and the battery. Every empirical constant in `Wt9011Axes` is checked here on the bike.
 * - The Ride-page pill, which is the only lean a rider sees while riding: the contract has no
 *   lean-HUD, and this module does not draw on the Ride page.
 * - The log, which gets one line per notable event so a diagnostics report stays one file.
 *
 * And one that costs none: every sample goes to MOTO-HUB itself (ModuleMotionSource, contract 43),
 * which records it in the trip and shows it on the Ride Dashboard instead of the phone's sensors.
 *
 * Written by sur00t (https://github.com/sur00t).
 */
class Wt9011Entry : MotoHubModuleEntry {
    override fun create(host: MotoHubModuleHost): MotoHubModule = Wt9011Module(host)
}

private class Wt9011Module(private val host: MotoHubModuleHost) :
    MotoHubModule,
    ModuleFeatures,
    ModuleLiveStatus,
    ModuleMotionSource {

    // Must agree with motohubModule { } in build.gradle.kts.
    override val manifest = ModuleManifest(
        id = "wt9011",
        version = "0.2.0",
        contractVersion = 43,
        entryClass = "io.github.sur00t.wt9011.Wt9011Entry",
        displayName = "WT9011 IMU",
        description = "Lean, g-forces and heading from a WitMotion WT9011DCL over Bluetooth LE."
    )

    private val link = Wt9011Link(host.context, host.storage, host.log)

    private val liveState = MutableStateFlow<ModuleLiveState?>(null)
    override val live: StateFlow<ModuleLiveState?> = liveState

    private val main = Handler(Looper.getMainLooper())
    private var lastPillLabel: String? = null

    /**
     * The pill is driven from here and not from the screen: the whole point of it is that lean is
     * on the Ride page while the diagnostics screen is closed. Twice a second is enough to read a
     * lean angle and cheap enough to leave running.
     */
    private val pillTicker = object : Runnable {
        override fun run() {
            publishPill()
            main.postDelayed(this, PILL_PERIOD_MS)
        }
    }

    init {
        // `create` may run on a background thread; everything below lands on the main looper.
        main.post {
            // Never on its own before the rider has connected a sensor once: until then any device
            // named WT... nearby could be someone else's.
            if (link.autoConnect && link.pinnedAddress != null) link.connect()
        }
        main.postDelayed(pillTicker, PILL_PERIOD_MS)
    }

    override fun features(): List<ModuleFeature> = listOf(
        ModuleFeature(
            id = FEATURE_DIAGNOSTICS,
            title = "WT9011 IMU",
            description = "Lean, g-forces and heading from a WitMotion WT9011DCL over Bluetooth LE.",
            placement = ModuleFeaturePlacement.MODULES,
            screen = { onBack -> DiagnosticsScreen(onBack) }
        )
    )

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> capability(type: Class<T>): T? = when {
        type.isInstance(this) -> this as T
        else -> null
    }

    override fun addMotionListener(listener: ModuleMotionListener) = link.addMotionListener(listener)

    override fun removeMotionListener(listener: ModuleMotionListener) = link.removeMotionListener(listener)

    override fun release() {
        main.removeCallbacks(pillTicker)
        liveState.value = null
        link.release()
    }

    private fun publishPill() {
        val snapshot = link.snapshot()
        val ride = snapshot.ride
        val fresh = snapshot.connected && snapshot.frameAgeMs in 0..PILL_STALE_MS
        val label: String? = if (fresh && ride != null) {
            val side = if (ride.leanDeg >= 0f) "right" else "left"
            String.format(
                Locale.ROOT,
                "Lean %.0f° %s · %.2f g",
                abs(ride.leanDeg),
                side,
                ride.longG
            )
        } else {
            null
        }
        if (label != lastPillLabel) {
            lastPillLabel = label
            liveState.value = label?.let { ModuleLiveState(it, FEATURE_DIAGNOSTICS) }
        }
    }

    // ---------------------------------------------------------------------------------------
    // The screen
    // ---------------------------------------------------------------------------------------

    @Composable
    private fun DiagnosticsScreen(onBack: () -> Unit) {
        val ui = host.ui
        var snapshot by remember { mutableStateOf(link.snapshot()) }
        var message by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(Unit) {
            // Ten redraws a second. The sensor sends twenty frames a second, so half of what
            // arrives is deliberately not drawn: a bench readout does not need it, and neither
            // does the phone's battery.
            while (true) {
                snapshot = link.snapshot()
                delay(RENDER_PERIOD_MS)
            }
        }

        val ride = snapshot.ride
        val raw = snapshot.raw

        ui.Screen(title = "WT9011 IMU", onBack = onBack) {
            ui.Paragraph(
                text = "Reads a WitMotion WT9011DCL directly over Bluetooth LE: the module scans, " +
                    "connects and subscribes itself, so nothing sits between the sensor and the " +
                    "phone. While it streams, MOTO-HUB records lean and g-forces from it instead " +
                    "of the phone's sensors, and the Ride Dashboard shows them. The heading is the " +
                    "sensor's own magnetic bearing, not true north."
            )
            message?.let { ui.Paragraph(text = it) }

            ui.SectionLabel(text = "Link")
            ui.Fact(label = "Status", value = snapshot.status, technical = false)
            ui.Fact(label = "Sensor", value = snapshot.deviceLabel ?: NOTHING, technical = false)
            ui.Fact(
                label = "Frames",
                value = if (snapshot.connected) {
                    String.format(Locale.ROOT, "%d per second", snapshot.packetsPerSecond)
                } else {
                    NOTHING
                },
                technical = false
            )
            ui.Fact(label = "Last frame", value = ageText(snapshot.frameAgeMs), technical = false)
            ui.Fact(label = "Frames received", value = snapshot.received.toString(), technical = true)
            ui.ActionRow(
                title = if (snapshot.connected) "Reconnect" else "Connect",
                description = when {
                    snapshot.connected -> "The link is up and the sensor is streaming."
                    snapshot.pinnedAddress != null -> "Looks for this module's sensor and subscribes to it."
                    else -> "Looks for a sensor whose name starts with WT, subscribes to it and remembers it."
                },
                onClick = {
                    if (snapshot.connected) link.reconnect() else link.connect()
                    message = if (snapshot.connected) {
                        "Dropping the link, then looking for the sensor again."
                    } else {
                        "Looking for a WT sensor…"
                    }
                }
            )
            ui.ActionRow(
                title = "Disconnect",
                description = if (snapshot.autoConnect) {
                    "The link is set to come up on its own. Tapping stops it doing that."
                } else {
                    "Nothing reconnects by itself until you tap Connect."
                },
                onClick = {
                    link.disconnect()
                    message = "Disconnected. Lean leaves the Ride page."
                }
            )

            snapshot.pinnedAddress?.let { address ->
                ui.ActionRow(
                    title = "Forget this sensor",
                    description = "Only $address is used. Tap for a replaced or borrowed unit: the " +
                        "next Connect takes the first WT sensor it finds.",
                    onClick = { message = link.forgetSensor() }
                )
            }

            ui.SectionLabel(text = "Ride")
            ui.Fact(label = "Lean", value = ride?.let { degrees(it.leanDeg, 1) } ?: NOTHING, technical = false)
            ui.Fact(label = "Nose", value = ride?.let { degrees(it.pitchDeg, 1) } ?: NOTHING, technical = false)
            ui.Fact(label = "Heading", value = ride?.let { heading(it.yawDeg) } ?: NOTHING, technical = false)
            ui.Fact(label = "Forward", value = ride?.let { gForces(it.longG) } ?: NOTHING, technical = false)
            ui.Fact(label = "Lateral", value = ride?.let { gForces(it.latG) } ?: NOTHING, technical = false)
            ui.Fact(label = "Vertical", value = ride?.let { gForces(it.vertG) } ?: NOTHING, technical = false)

            ui.SectionLabel(text = "Sensor, as it sends it")
            ui.Paragraph(
                text = "The sensor's own channels, before this module's zero or the rider's two " +
                    "switches. These are the numbers the bench tests in the README quote."
            )
            ui.Fact(label = "Roll", value = raw?.let { degrees(it.rollDeg, 2) } ?: NOTHING, technical = true)
            ui.Fact(label = "Pitch", value = raw?.let { degrees(it.pitchDeg, 2) } ?: NOTHING, technical = true)
            ui.Fact(label = "Yaw", value = raw?.let { degrees(it.yawDeg, 2) } ?: NOTHING, technical = true)
            ui.Fact(label = "AX", value = raw?.let { gForces(it.ax) } ?: NOTHING, technical = true)
            ui.Fact(label = "AY", value = raw?.let { gForces(it.ay) } ?: NOTHING, technical = true)
            ui.Fact(label = "AZ", value = raw?.let { gForces(it.az) } ?: NOTHING, technical = true)
            ui.Fact(
                label = "Output content",
                value = snapshot.outputContent?.toString() ?: NOTHING,
                technical = true
            )

            if (snapshot.displacementSuspected) {
                ui.Paragraph(
                    text = "No axis is reading gravity, so the sensor is probably not sending " +
                        "accelerometer + gyro + angle. Send the set-up below; if that does not " +
                        "fix it, the unit is in displacement mode."
                )
            }

            ui.SectionLabel(text = "Mounting")
            ui.Paragraph(
                text = "The sensor cannot know which way the bracket points, so these two switches " +
                    "and the zero are the rider's. The zero is taken in the sensor's own frame, " +
                    "so it stays good when a switch changes."
            )
            ui.ActionRow(
                title = "Nose direction",
                description = if (snapshot.mounting.flipNose) {
                    "Currently reversed: nose-up reads as nose-down. Tap to put it back."
                } else {
                    "Currently normal: nose-up reads as nose-up. Tap to reverse it."
                },
                onClick = {
                    link.setMounting(
                        flipNose = !snapshot.mounting.flipNose,
                        flipSide = snapshot.mounting.flipSide
                    )
                    message = "Nose direction changed."
                }
            )
            ui.ActionRow(
                title = "Left and right",
                description = if (snapshot.mounting.flipSide) {
                    "Currently mirrored: right leans read as left. Tap to put it back."
                } else {
                    "Currently as fitted: right leans read as right. Tap to mirror it."
                },
                onClick = {
                    link.setMounting(
                        flipNose = snapshot.mounting.flipNose,
                        flipSide = !snapshot.mounting.flipSide
                    )
                    message = "Left and right swapped."
                }
            )
            ui.ActionRow(
                title = "Zero now",
                description = snapshot.zero?.let {
                    String.format(
                        Locale.ROOT,
                        "Zeroed at lean %.1f°, nose %.1f°. Tap to capture the present attitude instead.",
                        it.leanDeg,
                        it.pitchDeg
                    )
                } ?: "Nothing captured yet: readings are the sensor's own. Tap with the bike upright.",
                onClick = { message = link.captureZero() }
            )
            ui.ActionRow(
                title = "Clear zero",
                description = if (snapshot.zero == null) {
                    "There is no zero to clear."
                } else {
                    "Throws the captured attitude away and goes back to the sensor's own."
                },
                onClick = { message = link.clearZero() }
            )
            ui.ActionRow(
                title = "Send set-up",
                description = if (snapshot.configured) {
                    "Rate, bandwidth, 9-axis and horizontal mounting are already in the sensor's " +
                        "flash. Tap to write them again, for a replaced unit."
                } else {
                    "Has not been written yet. It runs by itself on the first connection; tap to " +
                        "do it now."
                },
                onClick = { message = link.configureSensor() }
            )

            ui.SectionLabel(text = "Power and health")
            ui.Fact(
                label = "Battery",
                value = snapshot.batteryPercent?.let { String.format(Locale.ROOT, "%d %%", it) } ?: NOTHING,
                technical = false
            )
            ui.Fact(
                label = "Battery raw",
                value = snapshot.batteryRaw?.toString() ?: NOTHING,
                technical = true
            )
            ui.Fact(
                label = "Temperature",
                value = snapshot.temperatureC?.let { String.format(Locale.ROOT, "%.1f °C", it) } ?: NOTHING,
                technical = true
            )
            ui.Fact(
                label = "Auto-connect",
                value = if (snapshot.autoConnect) "on" else "off",
                technical = true
            )

            ui.SectionLabel(text = "Recent")
            ui.Paragraph(
                text = snapshot.recent.joinToString("\n").ifEmpty { "Nothing yet." }
            )
        }
    }

    private companion object {
        const val FEATURE_DIAGNOSTICS = "wt9011-diagnostics"
        const val RENDER_PERIOD_MS = 100L
        const val PILL_PERIOD_MS = 500L
        const val PILL_STALE_MS = 1000L
        const val NOTHING = "—"
    }
}

private fun degrees(value: Float, decimals: Int): String =
    String.format(Locale.ROOT, "%+.${decimals}f°", value)

private fun gForces(value: Float): String = String.format(Locale.ROOT, "%+.2f g", value)

/** The bearing as a rider reads a compass, 0…360, said out loud as magnetic. */
private fun heading(yawDeg: Float): String =
    String.format(Locale.ROOT, "%.0f° magnetic", bearingDeg(yawDeg))

private fun ageText(ageMs: Long): String = when {
    ageMs < 0L -> "—"
    ageMs < 1000L -> String.format(Locale.ROOT, "%d ms ago", ageMs)
    else -> String.format(Locale.ROOT, "%.1f s ago", ageMs / 1000f)
}
