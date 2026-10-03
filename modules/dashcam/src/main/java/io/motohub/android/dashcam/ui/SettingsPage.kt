// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.dashcam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableTarget
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.motohub.android.dashcam.DashcamController
import io.motohub.android.dashcam.protocol.CameraSetting
import io.motohub.android.module.ModuleUi
import java.util.Locale

/** Which group a setting belongs in, by what the camera calls it. */
private fun groupOf(key: String): String = when (key.lowercase(Locale.ROOT)) {
    "rec_split_duration", "encodec", "rec_resolution", "2003", "loopingvideo", "parking_monitor", "gsensor" -> "Recording"
    "mic", "osd", "logo_osd", "video_flip", "wdr", "ev", "2007", "2008", "audio", "soundrecord" -> "Picture and sound"
    else -> "Camera"
}

private fun isOnOff(s: CameraSetting): Boolean =
    s.options.size == 2 && s.options.map { it.lowercase(Locale.ROOT) }.let { it.contains("on") && it.contains("off") }

/**
 * The camera's own settings, as the camera lists them: on/off as switches, a few choices as a
 * segmented bar, a long list (the language) as a row that opens its choices. Formatting the card
 * sits apart, in red, behind a confirmation.
 */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun SettingsPage(controller: DashcamController, back: () -> Unit) {
    var settings by remember { mutableStateOf<List<CameraSetting>?>(null) }
    var reload by remember { mutableStateOf(0) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var confirmFormat by remember { mutableStateOf(false) }

    LaunchedEffect(reload) {
        controller.run(null, { p, h -> p.settings(h) }) { result ->
            val list = result.getOrNull() ?: emptyList()
            controller.lastSettings = list
            settings = list
        }
    }
    val change: (CameraSetting, Int) -> Unit = { setting, option ->
        controller.run("${setting.label}: ${setting.options[option]}", { p, h -> p.changeSetting(h, setting, option) }) { reload++ }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            PageHeader("Camera settings", back) {}
            val list = settings
            when {
                list == null -> Text("Asking the camera…", color = Dc.dim, fontSize = 15.sp, modifier = Modifier.padding(4.dp))
                list.isEmpty() -> Text("The camera did not list any settings.", color = Dc.dim, fontSize = 15.sp, modifier = Modifier.padding(4.dp))
                else -> for (group in listOf("Recording", "Picture and sound", "Camera")) {
                    val inGroup = list.filter { groupOf(it.key) == group }
                    if (inGroup.isEmpty()) continue
                    Column {
                        SectionTitle(group)
                        Card {
                            inGroup.forEachIndexed { index, setting ->
                                if (index > 0) Hairline()
                                when {
                                    isOnOff(setting) -> {
                                        val onIndex = setting.options.indexOfFirst { it.equals("on", ignoreCase = true) }
                                        val on = setting.current == onIndex
                                        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 16.dp),
                                            verticalAlignment = Alignment.CenterVertically) {
                                            Text(setting.label, color = Dc.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                            DcSwitch(on) { change(setting, if (on) 1 - onIndex else onIndex) }
                                        }
                                    }
                                    setting.options.size <= 4 -> Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text(setting.label, color = Dc.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                        if (setting.key == "encodec") {
                                            Text("H.265 writes about half as much to the card. The picture comes back a few seconds after a change.",
                                                color = Dc.dim, fontSize = 13.sp, lineHeight = 18.sp)
                                        }
                                        Segmented(setting.options, setting.current) { change(setting, it) }
                                    }
                                    else -> Column {
                                        val open = expanded == setting.key
                                        Row(Modifier.fillMaxWidth().height(60.dp).clickable { expanded = if (open) null else setting.key }.padding(horizontal = 16.dp),
                                            verticalAlignment = Alignment.CenterVertically) {
                                            Text(setting.label, color = Dc.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                            Text(setting.options.getOrNull(setting.current) ?: "", color = Dc.dim, fontSize = 15.sp, modifier = Modifier.padding(end = 8.dp))
                                            DcIcon(Ico.CHEVRON, Dc.faint, 18.dp)
                                        }
                                        if (open) {
                                            setting.options.forEachIndexed { i, option ->
                                                Row(Modifier.fillMaxWidth().height(52.dp).clickable { expanded = null; if (i != setting.current) change(setting, i) }
                                                    .background(if (i == setting.current) Dc.accentSoft else Dc.surface).padding(horizontal = 28.dp),
                                                    verticalAlignment = Alignment.CenterVertically) {
                                                    Text(option, color = if (i == setting.current) Dc.accent else Dc.text2, fontSize = 15.sp,
                                                        fontWeight = if (i == setting.current) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
                                                    if (i == setting.current) DcIcon(Ico.CHECK, Dc.accent, 18.dp)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Column {
                SectionTitle("The card")
                Card {
                    Row(Modifier.fillMaxWidth().height(60.dp).clickable { confirmFormat = true }.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        DcIcon(Ico.CARD, Dc.dangerText, 20.dp)
                        Text("Format the SD card", color = Dc.dangerText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        if (confirmFormat) {
            ConfirmDialog(
                "Format the SD card?",
                "Every video and photo on the camera is erased. Files saved on this phone are kept.",
                "Erase everything",
                onCancel = { confirmFormat = false }
            ) {
                confirmFormat = false
                controller.run("Card formatted", { p, h -> p.formatCard(h) }) { controller.refreshStatus() }
            }
        }
    }
}
