// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.immich

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.motohub.android.module.ModuleUi
import io.motohub.android.module.MotoHubModuleHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.NumberFormat

/** The module's page under Modules: is it connected, to what, how many photos are on the map. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun SettingsPage(host: MotoHubModuleHost, server: ImmichServer, onBack: () -> Unit) {
    var url by remember { mutableStateOf(server.serverUrl) }
    var key by remember { mutableStateOf(server.apiKey) }
    var testing by remember { mutableStateOf(false) }
    var testProblem by remember { mutableStateOf<String?>(null) }
    var tested by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    // The filter changed and the server is being asked which photos it lets through.
    var counting by remember { mutableStateOf(false) }
    // Bumped when work on another thread ends, so what is read from the server is read again.
    var version by remember { mutableIntStateOf(0) }

    // Opening the page is a good moment for the cheap check for new photos.
    LaunchedEffect(Unit) {
        if (server.configured && server.base != null) {
            withContext(Dispatchers.IO) {
                server.update()
                server.shown()
            }
            version++
        }
    }

    host.ui.Backdrop(onBack) {
        version.let { }
        Column(
            Modifier.fillMaxSize().background(Ink.Ground).verticalScroll(rememberScrollState())
                .statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassKey("‹", false, onBack)
                Spacer(Modifier.width(6.dp))
                Txt("Immich", 26.sp, Ink.Text, FontWeight.Bold, Modifier, 1)
            }
            Spacer(Modifier.height(14.dp))
            Hero(server, busy || counting)

            Eyebrow("Server", Modifier)
            Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Field("Address", "https://photos.example.com", url, false) {
                    url = it
                    val before = server.base
                    server.serverUrl = it
                    // Only another server makes what is held stale; a trailing slash does not.
                    if (server.base != before) server.forget()
                    server.save()
                    tested = false
                    version++
                }
                Field("API key", "Paste the key made in Immich", key, true) {
                    key = it
                    val before = server.apiKey.trim()
                    server.apiKey = it
                    if (it.trim() != before) server.forget()
                    server.save()
                    tested = false
                    version++
                }
                SideButton(if (testing) "Testing…" else "Test connection", !testing && server.configured, Modifier.fillMaxWidth()) {
                    testing = true
                    Thread {
                        testProblem = server.check()
                        tested = true
                        testing = false
                        version++
                    }.start()
                }
                if (tested) {
                    val problem = testProblem
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Txt(if (problem == null) "✓" else "✕", 18.sp, if (problem == null) Ink.Good else Ink.Bad, FontWeight.Bold, Modifier, 1)
                        Spacer(Modifier.width(10.dp))
                        Txt(
                            problem ?: "The server accepts this key.",
                            14.sp, if (problem == null) Ink.Good else Ink.Bad, FontWeight.Medium, Modifier.weight(1f), 4
                        )
                    }
                }
            }

            Eyebrow("Photos on the map", Modifier)
            Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Txt(
                    "Every photo that carries a position becomes a pin on the NAV map; new ones come in by themselves. " +
                        "Switch them on or off there: Layers, Immich photos.",
                    14.sp, Ink.Muted, FontWeight.Normal, Modifier, 4
                )
                server.lastError?.let { Txt(it, 14.sp, Ink.Bad, FontWeight.Medium, Modifier, 4) }
                MainButton(
                    if (busy) "Downloading…" else if (server.held() == null) "Download the photos" else "Update the photos",
                    !busy && server.configured && server.base != null,
                    Modifier.fillMaxWidth()
                ) {
                    busy = true
                    Thread {
                        server.refresh()
                        server.shown()
                        busy = false
                        version++
                    }.start()
                }
            }

            Eyebrow("What the map shows", Modifier)
            MapFilterCard(server) {
                counting = true
                version++
                Thread {
                    server.shown()
                    counting = false
                    version++
                }.start()
            }

            Eyebrow("On this phone", Modifier)
            Row(Modifier.fillMaxWidth().card().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Txt("Saved images", 16.sp, Ink.Text, FontWeight.SemiBold, Modifier, 1)
                    Txt(
                        Words.bytes(server.imageCacheBytes()) + " of " + Words.bytes(ImmichServer.IMAGE_CACHE_BYTES) +
                            " kept so photos open at once; the oldest go first",
                        13.sp, Ink.Muted, FontWeight.Normal, Modifier, 2
                    )
                }
                Spacer(Modifier.width(12.dp))
                Box(
                    Modifier.clip(RoundedCornerShape(12.dp)).background(Ink.PanelHigh)
                        .clickable { server.clearImages(); Bitmaps.clear(); version++ }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) { Txt("Clear", 15.sp, Ink.Accent, FontWeight.Bold, Modifier, 1) }
            }

            Eyebrow("The key", Modifier)
            KeyHelp()
            Spacer(Modifier.height(28.dp))
        }
    }
}

/** The status at a glance: who is connected to which Immich, and how many photos are on the map. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun Hero(server: ImmichServer, busy: Boolean) {
    val who = server.identity
    val held = server.held()
    val shown = server.shownCount()
    val filtered = !server.filter.everything
    val connected = server.configured && who != null
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(Ink.AccentDeep, Color(0xFF6C3FC8))))
            .padding(22.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (connected) Ink.Good else Color(0x99FFFFFF)))
            Spacer(Modifier.width(8.dp))
            // An address and a key that are saved but not asked yet are neither: the account is only
            // known after Test connection, so "not connected" would contradict the photos below.
            val status = when {
                connected -> "CONNECTED"
                server.configured -> "NOT CHECKED YET"
                else -> "NOT CONNECTED"
            }
            Txt(status, 12.sp, Color(0xD9FFFFFF), FontWeight.Bold, Modifier, 1)
        }
        Spacer(Modifier.height(10.dp))
        Txt(who?.user ?: "Your Immich", 28.sp, Color.White, FontWeight.Bold, Modifier, 1)
        val line = listOfNotNull(who?.version?.let { "Immich $it" }, server.base?.removePrefix("https://")).joinToString(" · ")
        Txt(line.ifEmpty { "Enter the address and the key below" }, 14.sp, Color(0xCCFFFFFF), FontWeight.Medium, Modifier, 2)
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Txt(
                when {
                    busy -> "…"
                    held == null || shown == null -> "—"
                    else -> NumberFormat.getIntegerInstance().format(shown)
                },
                40.sp, Color.White, FontWeight.Bold, Modifier, 1
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.padding(bottom = 7.dp)) {
                Txt("photos on the map", 14.sp, Color(0xE6FFFFFF), FontWeight.SemiBold, Modifier, 1)
                val of = if (held != null && filtered) "of " + NumberFormat.getIntegerInstance().format(held.size) else null
                val at = if (held != null && server.lastRefresh > 0) "updated " + updated(server.lastRefresh) else null
                listOfNotNull(of, at).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                    Txt(it, 12.sp, Color(0xB3FFFFFF), FontWeight.Normal, Modifier, 2)
                }
            }
        }
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
@OptIn(ExperimentalLayoutApi::class)
private fun KeyHelp() {
    Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Txt("Make it in Immich: Account settings, API keys, New API key. Give it these permissions:", 14.sp, Ink.Muted, FontWeight.Normal, Modifier, 4)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("map.read", "asset.read", "asset.view", "album.read", "user.read").forEach { Chip(it, Ink.Accent, Ink.PanelHigh) }
        }
        Txt("MOTO-HUB only reads: it never changes your library. The key stays on this phone, sealed.", 13.sp, Ink.Faint, FontWeight.Normal, Modifier, 3)
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun Field(label: String, hint: String, value: String, secret: Boolean, onChange: (String) -> Unit) {
    var shown by remember { mutableStateOf(!secret) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Txt(label, 13.sp, Ink.Muted, FontWeight.SemiBold, Modifier.padding(start = 2.dp), 1)
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ink.PanelHigh).padding(start = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1f).padding(vertical = 15.dp)) {
                if (value.isEmpty()) Txt(hint, 16.sp, Ink.Faint, FontWeight.Normal, Modifier, 1)
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = TextStyle(color = Ink.Text, fontSize = 16.sp),
                    cursorBrush = SolidColor(Ink.Accent),
                    visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (secret) {
                Box(Modifier.clickable { shown = !shown }.padding(horizontal = 16.dp, vertical = 15.dp)) {
                    Txt(if (shown) "Hide" else "Show", 14.sp, Ink.Accent, FontWeight.Bold, Modifier, 1)
                }
            } else {
                Spacer(Modifier.width(16.dp))
            }
        }
    }
}

private fun updated(millis: Long): String =
    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(millis))
