// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.dawarich

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableTarget
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.motohub.android.module.ModuleFeature
import io.motohub.android.module.ModuleFeaturePlacement
import io.motohub.android.module.ModuleFeatures
import io.motohub.android.module.ModuleLiveStatus
import io.motohub.android.module.ModuleManifest
import io.motohub.android.module.ModuleRideTrack
import io.motohub.android.module.ModuleUi
import io.motohub.android.module.MotoHubModule
import io.motohub.android.module.MotoHubModuleEntry
import io.motohub.android.module.MotoHubModuleHost

class DawarichEntry : MotoHubModuleEntry {
    override fun create(host: MotoHubModuleHost): MotoHubModule = DawarichModule(host)
}

private class DawarichModule(private val host: MotoHubModuleHost) : MotoHubModule {

    // Must agree with motohubModule { } in build.gradle.kts.
    override val manifest = ModuleManifest(
        id = "dawarich",
        version = "0.6.0",
        contractVersion = 27,
        entryClass = "io.motohub.dawarich.DawarichEntry",
        displayName = "Dawarich",
        description = "Sends your position to your own Dawarich server while you ride."
    )

    private val tracker = Tracker(host)
    private val features = DawarichFeatures(host, tracker)
    private val liveStatus = object : ModuleLiveStatus {
        override val live = tracker.pill
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> capability(type: Class<T>): T? = when (type) {
        ModuleFeatures::class.java -> features as T
        ModuleLiveStatus::class.java -> liveStatus as T
        else -> null
    }

    override fun release() = tracker.release()
}

private class DawarichFeatures(private val host: MotoHubModuleHost, private val tracker: Tracker) : ModuleFeatures {

    override fun features(): List<ModuleFeature> = listOf(
        ModuleFeature(
            id = "dawarich-settings",
            title = "Dawarich",
            description = "Records your rides to your own Dawarich server",
            placement = ModuleFeaturePlacement.MODULES,
            screen = { onBack -> Page(onBack) }
        ),
        ModuleFeature(
            id = SERVER_FEATURE,
            title = "Server settings",
            description = "Address, API key, connection test",
            placement = ModuleFeaturePlacement.NONE,
            screen = { onBack -> ServerPage(onBack) }
        ),
        ModuleFeature(
            id = STATUS_FEATURE,
            title = "Status",
            description = "What is waiting, what was sent, permissions",
            placement = ModuleFeaturePlacement.NONE,
            screen = { onBack -> StatusPage(onBack) }
        ),
        ModuleFeature(
            id = "dawarich-send-ride",
            title = "Send to Dawarich",
            description = "Sends this ride's track to your Dawarich server",
            placement = ModuleFeaturePlacement.RIDE_ACTION,
            screen = { onBack -> SendRide(onBack) }
        )
    )

    @Composable
    private fun SendRide(onBack: () -> Unit) {
        val entry = remember { host.openedFor() }
        var status by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        // Sent whole before: the first tap only asks, the second sends again.
        var sentBefore by remember { mutableStateOf(entry?.let { tracker.sentRides.get(it.id) }) }
        var confirming by remember { mutableStateOf(false) }
        // Rides sent before this module kept a record: the server itself says whether it has them.
        var onServer by remember { mutableStateOf<Int?>(null) }
        var checking by remember { mutableStateOf(entry != null && sentBefore == null && tracker.configured) }
        LaunchedEffect(entry) {
            if (!checking || entry == null) return@LaunchedEffect
            onServer = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                tracker.pointsOnServer(entry.dateMillis, entry.dateMillis + entry.durationMillis)
            }
            checking = false
        }
        host.ui.Screen("Send to Dawarich", onBack) {
            if (entry == null) {
                host.ui.Paragraph("Open this from a recorded ride.")
                return@Screen
            }
            host.ui.Fact("Ride", entry.title, false)
            host.ui.Fact("Distance", String.format("%.1f km", entry.distanceMeters / 1000.0), false)
            val previous = sentBefore
            if (previous != null) {
                host.ui.SectionLabel("ALREADY SENT")
                host.ui.Fact("Sent on", dateTime(previous.atMillis), false)
                host.ui.Fact("Points", previous.points.toString(), false)
                host.ui.Fact("To", previous.server, false)
                host.ui.Paragraph(
                    if (previous.server == tracker.serverUrl.trim()) {
                        "This ride is already on your server. Send it again only if you deleted it there: " +
                            "points the server already has are ignored."
                    } else {
                        "It went to a different server than the one set now. Send it again to put it on this one too."
                    }
                )
            } else if ((onServer ?: 0) > 0) {
                host.ui.SectionLabel("ALREADY ON YOUR SERVER")
                host.ui.Fact("Points in this ride's time", onServer.toString(), false)
                host.ui.Paragraph(
                    "Your server already has points from this ride, sent earlier or by live tracking. " +
                        "Send it again only to fill gaps: points the server already has are ignored."
                )
            } else {
                host.ui.Paragraph(
                    "Fills any gap live tracking left. Points the server already has are ignored, " +
                        "so sending a ride twice is harmless."
                )
            }
            if (checking) host.ui.Fact("Server", "Checking whether it has this ride…", false)
            if (!tracker.configured) host.ui.Paragraph("Set the server address and API key on the Dawarich page first.")
            if (status.isNotEmpty()) host.ui.Fact("Status", status, false)
            if (confirming) host.ui.Paragraph("Send this ride again?")
            val askFirst = previous != null || (onServer ?: 0) > 0
            val label = when {
                busy -> "Sending…"
                !askFirst -> "Send"
                confirming -> "Yes, send it again"
                else -> "Send again…"
            }
            host.ui.PrimaryButton(label, !busy && !checking && tracker.configured) {
                if (askFirst && !confirming) {
                    confirming = true
                    return@PrimaryButton
                }
                confirming = false
                busy = true
                status = "Reading the ride…"
                Thread {
                    try {
                        val track = host.rides.track(entry, MAX_POINTS)
                        if (track == null) {
                            status = "This ride no longer exists."
                        } else {
                            val fixes = fixesOf(entry.dateMillis, track)
                            val result = tracker.sendTrack(fixes) { status = "Sent $it of ${fixes.size}" }
                            status = result.message
                            if (result.complete) {
                                val sent = SentRide(System.currentTimeMillis(), fixes.size, tracker.serverUrl.trim())
                                tracker.sentRides.mark(entry.id, sent)
                                sentBefore = sent
                            }
                        }
                    } catch (e: Exception) {
                        status = "Failed: ${e.message}"
                    } finally {
                        busy = false
                    }
                }.start()
            }
        }
    }

    private fun dateTime(millis: Long): String =
        java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
            .format(java.util.Date(millis))

    /** A recorded track as fixes. Times are milliseconds since the first point; the ride starts at [startMillis]. */
    private fun fixesOf(startMillis: Long, t: ModuleRideTrack): List<Fix> {
        val out = ArrayList<Fix>(t.latitudes.size)
        for (i in 0 until t.latitudes.size) {
            if (t.latitudes[i].isNaN() || t.longitudes[i].isNaN()) continue
            val kph = t.speedsKph[i]
            out.add(
                Fix(
                    t.latitudes[i], t.longitudes[i], (startMillis + t.timesMillis[i]) / 1000L, -1f,
                    t.altitudesMeters[i], if (kph.isNaN()) -1f else kph / 3.6f, -1f, -1f, false
                )
            )
        }
        return out
    }

    /** Re-reads the tracker every second: it works on its own threads. */
    @Composable
    private fun ticking(): Int {
        var tick by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(1000)
                tick++
            }
        }
        return tick
    }

    /** One thing per screen: is it recording, start or stop it, and who records. The rest is one tap away. */
    @Composable
    private fun Page(onBack: () -> Unit) {
        var useApp by remember { mutableStateOf(tracker.useApp) }
        var auto by remember { mutableStateOf(tracker.autoTrack) }
        val appInstalled = remember { tracker.app.installed() }
        ticking().let { }
        host.ui.Screen("Dawarich", onBack) {
            val recording = tracker.recording
            StatusCard(
                recording = recording,
                title = if (recording) "Recording" else "Not recording",
                detail = when {
                    useApp -> "With the Dawarich app · " + tracker.app.lastResult
                    recording -> "With MOTO-HUB · ${tracker.sent} points sent"
                    !tracker.configured -> "Set your server first: Server settings, below"
                    else -> "With MOTO-HUB"
                }
            )
            host.ui.PrimaryButton(if (recording) "Stop recording" else "Start recording", useApp || tracker.configured) {
                if (recording) tracker.stopNow() else tracker.startNow()
            }

            host.ui.SectionLabel("WHO RECORDS")
            ChoiceCard("MOTO-HUB", "Follows the GPS itself and sends it to your server", !useApp) {
                choose(false)
                useApp = false
            }
            ChoiceCard(
                "The Dawarich app",
                if (appInstalled) "MOTO-HUB starts and stops the Dawarich app" else "Install the Dawarich app to choose it",
                useApp
            ) {
                if (appInstalled) {
                    choose(true)
                    useApp = true
                }
            }
            if (useApp) {
                host.ui.ActionRow("Allow MOTO-HUB in the Dawarich app", "Once: turn on Android automation there") {
                    tracker.app.openAutomationSettings()
                    tracker.retryApp()
                }
            }

            host.ui.SectionLabel("AUTOMATIC")
            host.ui.ActionRow(
                "Start with the ride: " + if (auto) "on" else "off",
                "Records whenever MOTO-HUB is connected to the motorcycle"
            ) { auto = !auto; tracker.autoTrack = auto; tracker.save() }

            host.ui.SectionLabel("MORE")
            host.ui.ActionRow("Server settings", "Address, API key, connection test") { host.openFeature(SERVER_FEATURE) }
            host.ui.ActionRow("Status", "What is waiting, what was sent, permissions") { host.openFeature(STATUS_FEATURE) }
        }
    }

    private fun choose(app: Boolean) {
        tracker.useApp = app
        tracker.retryApp()
        tracker.save()
    }

    @Composable
    private fun ServerPage(onBack: () -> Unit) {
        var url by remember { mutableStateOf(tracker.serverUrl) }
        var key by remember { mutableStateOf(tracker.apiKey) }
        var live by remember { mutableStateOf(tracker.live) }
        var test by remember { mutableStateOf("") }
        host.ui.Screen("Server settings", onBack) {
            host.ui.Paragraph("Your own Dawarich server. MOTO-HUB sends rides here, and its own recording when it records.")
            Field("Address (https://…)", url, false) { url = it; tracker.serverUrl = it; tracker.save() }
            Field("API key", key, true) { key = it; tracker.apiKey = it; tracker.save() }
            host.ui.ActionRow("Test connection", "Asks the server whether it accepts this key") {
                test = "Testing…"
                Thread { test = tracker.check() }.start()
            }
            if (test.isNotEmpty()) host.ui.Fact("Connection", test, false)
            host.ui.SectionLabel("WHEN MOTO-HUB RECORDS")
            host.ui.ActionRow(
                "Send each point at once: " + if (live) "on" else "off",
                if (live) "Each point goes out the moment it arrives" else "Points go out together every 20 seconds"
            ) { live = !live; tracker.live = live; tracker.save() }
        }
    }

    @Composable
    private fun StatusPage(onBack: () -> Unit) {
        ticking().let { }
        host.ui.Screen("Status", onBack) {
            if (tracker.useApp) {
                host.ui.Fact("Dawarich app", tracker.app.lastResult, false)
            } else {
                host.ui.Fact("Following the GPS", if (tracker.tracking) "yes" else "no", false)
                host.ui.Fact(
                    "Location permission",
                    if (tracker.hasPermission) "granted" else "missing: grant it to MOTO-HUB in Android settings",
                    false
                )
            }
            host.ui.Fact("Waiting to send", tracker.queued().toString(), false)
            host.ui.Fact("Sent", tracker.sent.toString(), false)
            host.ui.Fact("Last upload", tracker.lastUpload, false)
        }
    }

    @Composable
    @ComposableTarget(ModuleUi.UI_APPLIER)
    private fun StatusCard(recording: Boolean, title: String, detail: String) {
        val accent = if (recording) Color(0xFF34C759) else Color(0x80808080)
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .border(2.dp, accent, RoundedCornerShape(16.dp)).padding(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(accent))
                Spacer(Modifier.width(10.dp))
                BasicText(title, style = TextStyle(color = LocalContentColor.current, fontSize = 26.sp, fontWeight = FontWeight.Bold))
            }
            Spacer(Modifier.height(6.dp))
            BasicText(detail, style = TextStyle(color = LocalContentColor.current.copy(alpha = 0.7f), fontSize = 15.sp))
        }
    }

    @Composable
    @ComposableTarget(ModuleUi.UI_APPLIER)
    private fun ChoiceCard(title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
        val edge = if (selected) Color(0xFF34C759) else Color(0x40808080)
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .border(if (selected) 2.dp else 1.dp, edge, RoundedCornerShape(14.dp))
                .clickable(onClick = onClick).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(20.dp).clip(CircleShape).border(2.dp, edge, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(edge))
            }
            Spacer(Modifier.width(14.dp))
            Column {
                BasicText(title, style = TextStyle(color = LocalContentColor.current, fontSize = 18.sp, fontWeight = FontWeight.SemiBold))
                BasicText(detail, style = TextStyle(color = LocalContentColor.current.copy(alpha = 0.7f), fontSize = 14.sp))
            }
        }
    }

    @Composable
    @ComposableTarget(ModuleUi.UI_APPLIER)
    private fun Field(label: String, value: String, secret: Boolean, onChange: (String) -> Unit) {
        var shown by remember { mutableStateOf(!secret) }
        host.ui.Fact(label, if (secret) (if (shown) "shown" else "hidden") else "", false)
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .border(1.dp, Color(0x80808080), RoundedCornerShape(12.dp)).padding(14.dp)
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = LocalContentColor.current, fontSize = 16.sp),
                cursorBrush = SolidColor(LocalContentColor.current),
                visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (secret) host.ui.ActionRow(if (shown) "Hide the key" else "Show the key", "") { shown = !shown }
    }
}

private const val MAX_POINTS = 100_000
private const val SERVER_FEATURE = "dawarich-server"
private const val STATUS_FEATURE = "dawarich-status"
