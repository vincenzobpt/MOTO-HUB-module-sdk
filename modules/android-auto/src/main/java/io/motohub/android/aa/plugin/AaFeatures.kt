// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// The screens this module brings with it. The app hosts them without knowing what they are.
package io.motohub.android.aa.plugin

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.motohub.android.aa.AapPhoneHandshake
import io.motohub.android.aa.AaReceiver
import io.motohub.android.aa.AaSelfMode
import io.motohub.android.aa.phone.WirelessHeadUnitTransport
import io.motohub.android.aaplugin.AaPluginContract
import io.motohub.android.module.ModuleFeature
import io.motohub.android.module.ModuleFeaturePlacement
import io.motohub.android.module.ModuleFeatures
import io.motohub.android.module.MotoHubModuleHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * What this module adds to the app's own screens.
 *
 * Everything a rider reads about Android Auto is written here, in the module, because the app
 * carries no words for it: an app with this module missing has nothing to say about Android Auto
 * and says nothing, rather than offering a feature that is not there.
 *
 * Drawn with the app's own vocabulary ([MotoHubModuleHost.ui]) rather than raw Compose. The first
 * version of this page used plain Compose and looked like what it was - a foreign page bolted
 * into the app - and printed ports and filesystem paths at a rider in the same weight as the two
 * lines that actually mattered.
 */
internal class AaFeatures(private val host: MotoHubModuleHost) : ModuleFeatures {

    override fun features(): List<ModuleFeature> = listOf(
        // Under Modules, not Settings: this describes the module, and a rider looking for it
        // goes where they installed it. Settings is for choices - the app keeps its own Android
        // Auto choices, resolution and insets, there.
        ModuleFeature(
            id = "android-auto-status",
            title = "Android Auto",
            description = "What this module does, and whether it can run right now",
            placement = ModuleFeaturePlacement.MODULES,
            screen = { onBack -> AboutScreen(onBack) }
        ),
        // Not listed anywhere by the app - reached from the page above, through the host. A
        // module with only placed screens could offer exactly as many as it had places to be
        // listed in, which is why this placement exists.
        ModuleFeature(
            id = "android-auto-technical",
            title = "Technical details",
            description = "For diagnosing a session that will not start",
            placement = ModuleFeaturePlacement.NONE,
            screen = { onBack -> TechnicalScreen(onBack) }
        ),
        // The inverse of the receiver above: MOTO-HUB as the phone, projecting onto an external
        // head unit (Carpuride, Chigee and the like) over wireless Android Auto. NONE, like the
        // page above: the Modules card opens only a module's first MODULES page, so a second one
        // placed there was unreachable. Reached from the About page instead.
        ModuleFeature(
            id = "android-auto-external",
            title = "External head unit",
            description = "Project MOTO-HUB onto a Carpuride-style display over wireless Android Auto",
            placement = ModuleFeaturePlacement.NONE,
            screen = { onBack -> ExternalHeadUnitScreen(onBack) }
        )
    )

    @Composable
    private fun ExternalHeadUnitScreen(onBack: () -> Unit) {
        var running by remember { mutableStateOf(false) }
        var result by remember { mutableStateOf<String?>(null) }
        var logText by remember { mutableStateOf("") }

        host.ui.Screen("External head unit", onBack) {
            host.ui.Paragraph(
                "Connects MOTO-HUB to an external head unit (Carpuride, Chigee or similar) over " +
                    "wireless Android Auto: it pairs over Bluetooth, takes the head unit's Wi-Fi " +
                    "credentials, joins that network and runs the Android Auto handshake. Pair the " +
                    "head unit in Android's Bluetooth settings first, then pick it below."
            )
            host.ui.Paragraph(
                "Accept the Wi-Fi prompt when it appears. If you use an always-on VPN, turn it off " +
                    "first — it blocks the connection to the head unit's network."
            )

            host.ui.SectionLabel("PAIRED DEVICES")
            val adapter = host.context.getSystemService(BluetoothManager::class.java)?.adapter
            val bonded = try { adapter?.bondedDevices?.toList() ?: emptyList() } catch (_: SecurityException) { emptyList() }
            if (bonded.isEmpty()) {
                host.ui.Fact("Bluetooth", "No paired devices — pair the head unit in Android settings first", technical = false)
            }
            for (device in bonded) {
                val name = deviceName(device)
                host.ui.ActionRow(name, device.address) {
                    if (!running) {
                        running = true
                        result = null
                        logText = ""
                        CoroutineScope(Dispatchers.IO).launch {
                            val sink: (String) -> Unit = { line ->
                                host.log.log("[Extend] $line")
                                logText += "$line\n"
                            }
                            val transport = WirelessHeadUnitTransport.connect(host.context, device, sink)
                            val outcome = if (transport.connection == null) {
                                AapPhoneHandshake.Outcome(false, transport.detail)
                            } else {
                                AapPhoneHandshake.run(host.context, ModuleIdentity, transport.connection, sink, host)
                            }
                            result = if (outcome.success) "OK: ${outcome.detail}" else "FAIL: ${outcome.detail}"
                            running = false
                        }
                    }
                }
            }

            host.ui.SectionLabel("RESULT")
            if (running) host.ui.Fact("Status", "Running…", technical = false)
            result?.let { host.ui.Fact("Result", it, technical = false) }
            if (logText.isNotEmpty()) host.ui.Fact("Log", logText, technical = true)
            host.ui.Paragraph("The full log is also saved to diagnostics — send a report from Settings after a run.")

            host.ui.SectionLabel("IDENTITY")
            host.ui.Fact(
                "Certificate",
                if (ModuleIdentity.isAvailable()) "bundled" else "MISSING — build with -PincludeAndroidAutoIdentity=true",
                technical = true
            )
        }
    }

    private fun deviceName(device: BluetoothDevice): String =
        try { device.name ?: device.address } catch (_: SecurityException) { device.address }

    @Composable
    private fun AboutScreen(onBack: () -> Unit) {
        host.ui.Screen("Android Auto", onBack) {
            host.ui.Paragraph(
                "This module runs Google's Android Auto on the motorcycle's screen: its maps, its " +
                    "music, its messages, drawn by Android Auto itself and sent to the dashboard. " +
                    "Start it from RIDE once the motorcycle is connected."
            )
            host.ui.Paragraph(
                "It is not part of MOTO-HUB. It was downloaded, checked against the signing key, and " +
                    "loaded - and removing it takes Android Auto out of the app entirely."
            )

            host.ui.SectionLabel("RIGHT NOW")
            // Two facts, both in words a rider can act on: whether the app Android Auto needs is
            // even on this phone, and whether it has ever answered since MOTO-HUB started. Anything
            // more precise than that belongs behind the row below.
            host.ui.Fact(
                "Google's Android Auto app",
                AaSelfMode.lastGearheadVersion?.let { "installed, version $it" } ?: "not seen yet",
                technical = false
            )
            host.ui.Fact(
                "Has connected since MOTO-HUB started",
                if (AaReceiver.hasAndroidAutoConnectedSinceStart()) "yes" else "not yet",
                technical = false
            )

                host.ui.ActionRow(
                    title = "Technical details",
                    description = "Ports, identity, and where this module keeps its files"
                ) {
                    // The host opens it; this module never learns what is on screen or how to get there.
                    host.openFeature("android-auto-technical")
                }
                host.ui.ActionRow(
                    title = "External head unit",
                    description = "Show MOTO-HUB on a Carpuride, Chigee or similar display"
                ) {
                    host.openFeature("android-auto-external")
                }
        }
    }

    @Composable
    private fun TechnicalScreen(onBack: () -> Unit) {
        host.ui.Screen("Technical details", onBack) {
            host.ui.Paragraph(
                "Nothing here can be changed, and nothing here needs to be. It is what to quote in a " +
                    "support report when a session will not start."
            )

            host.ui.SectionLabel("LOCAL SOCKETS")
            host.ui.Paragraph(
                "Both are fixed and local to this phone. Only one session can hold them, which is why " +
                    "starting a second one stops the first instead of failing to bind."
            )
            host.ui.Fact("Receiver", AaPluginContract.RECEIVER_PORT.toString(), technical = true)
            host.ui.Fact("Head unit server", AaPluginContract.HEAD_UNIT_SERVER_PORT.toString(), technical = true)

            host.ui.SectionLabel("THIS MODULE")
            host.ui.Fact(
                "Head unit identity",
                if (ModuleIdentity.isAvailable()) "carried by this module" else "missing",
                technical = true
            )
                host.ui.Fact("Files", host.storage.absolutePath, technical = true)
        }
    }
}
