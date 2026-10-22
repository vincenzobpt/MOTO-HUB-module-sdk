// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.mapopt

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableTarget
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import io.motohub.android.module.ModuleUi
import io.motohub.android.module.MotoHubModuleHost

private const val TOKENS_URL = "https://account.mapbox.com/access-tokens/"

/** The module's page under Modules: the token, set or not. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun HomePage(host: MotoHubModuleHost, account: MapboxAccount, onBack: () -> Unit) {
    var saved by remember { mutableStateOf(account.masked) }
    var draft by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var on by remember { mutableStateOf(account.enabled) }

    host.ui.Screen("MapOpt", onBack) {
        host.ui.Paragraph(
            "With your own Mapbox token the 3D POV - in the ride replay and on the Ride Dashboard - " +
                "is drawn by Mapbox with its Standard map: terrain, trees, landmarks and light. " +
                "Without one, or switched off, it keeps the app's own map."
        )
        if (saved.isNotEmpty()) {
            host.ui.SectionLabel("Mapbox")
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (on) "Mapbox in the 3D POV: ON" else "Mapbox in the 3D POV: OFF", color = MaterialTheme.colorScheme.onSurface)
                Switch(checked = on, onCheckedChange = { account.switchOn(it); on = it })
            }
            host.ui.Fact("Token", saved, false)
            host.ui.Fact("Style", MAPBOX_STYLE, true)
            host.ui.SectionLabel("This month")
            UsageBars(account.usage)
            host.ui.ActionRow("Remove the token", "The 3D POV goes back to the app's own map") {
                account.forget()
                saved = ""
            }
            host.ui.Paragraph("Opens with the next 3D POV. Map data © Mapbox © OpenStreetMap.")
        } else {
            host.ui.SectionLabel("Your Mapbox token")
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.trim(); problem = null },
                label = { Text("Public token (pk.…)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            problem?.let { host.ui.Paragraph(it) }
            host.ui.PrimaryButton(if (checking) "Checking…" else "Check and save", !checking && draft.isNotBlank()) {
                checking = true
                val candidate = draft
                Thread {
                    val answer = account.check(candidate)
                    if (answer == null) {
                        account.save(candidate)
                        saved = account.masked
                        draft = ""
                    }
                    problem = answer
                    checking = false
                }.start()
            }
            host.ui.ActionRow("Get a token", "Free at mapbox.com: Account, Tokens, the default public token") {
                runCatching {
                    host.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TOKENS_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
        host.ui.Paragraph("Not made by or affiliated with Mapbox. Your token, your Mapbox account and its terms.")
    }
}

/** One bar per billed kind against its free allowance: amber past 80 %, red once it is used up. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun UsageBars(usage: Usage) {
    val format = NumberFormat.getIntegerInstance()
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Billed.entries.forEach { billed ->
            val count = usage.used(billed)
            val share = (count.toFloat() / billed.allowance).coerceIn(0f, 1f)
            val color = when {
                count >= billed.allowance -> colors.error
                share > 0.8f -> androidx.compose.ui.graphics.Color(0xFFE0A030)
                else -> colors.primary
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(billed.label, color = colors.onSurface)
                    Text(format.format(count) + " / " + format.format(billed.allowance), color = colors.onSurfaceVariant)
                }
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(colors.surfaceVariant)) {
                    if (count > 0) {
                        // A used load is always visible, however small the share.
                        Box(Modifier.fillMaxWidth(maxOf(share, 0.02f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(color))
                    }
                }
            }
        }
    }
    Text(
        "Every 3D POV opened with Mapbox is one map load. Counted on this phone; your Mapbox account page has the exact numbers.",
        color = colors.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall
    )
}
