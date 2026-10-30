// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.dashcam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableTarget
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.motohub.android.dashcam.DashcamController
import io.motohub.android.dashcam.ReportOrigin
import io.motohub.android.dashcam.ReportPhase
import io.motohub.android.module.ModuleUi

/**
 * Under the error of a camera this module could not show: while the probe runs, that it does;
 * then the make and model, optional, and the report sent with one press. On an app that cannot
 * send it for the module, where in Diagnostics to send it by hand.
 */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun ReportCard(controller: DashcamController, origin: ReportOrigin, modifier: Modifier = Modifier) {
    val report by controller.report.collectAsState()
    if (report.origin != origin || report.phase == ReportPhase.NONE) return
    var model by remember { mutableStateOf("") }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Dc.surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when (report.phase) {
            ReportPhase.PROBING -> Text("Studying the camera… this takes up to half a minute.", color = Dc.text, fontSize = 15.sp, lineHeight = 21.sp)
            ReportPhase.READY -> {
                Text("Help us add this camera", color = Dc.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(
                    "What the camera answers has been written down, with passwords and serial numbers left out. " +
                        "Send it, and support for it can be added.",
                    color = Dc.dim, fontSize = 14.sp, lineHeight = 20.sp
                )
                if (report.canSend) {
                    Field("Make and model (optional)", model, false) { model = it.take(80) }
                    BigButton("Send report", null, true, false) { controller.sendReport(model) }
                } else {
                    Text(
                        "Open Settings ▸ 8 Diagnostics ▸ 8.4 Send diagnostics now, and write the camera's make and model in the note.",
                        color = Dc.text, fontSize = 14.sp, lineHeight = 20.sp
                    )
                }
            }
            ReportPhase.SENDING -> Text("Sending the report…", color = Dc.text, fontSize = 15.sp)
            ReportPhase.SENT -> Text(
                "Report sent. Your Support ID is ${report.supportId}: write it on Discord with the camera's make and model.",
                color = Dc.text, fontSize = 15.sp, lineHeight = 21.sp
            )
            ReportPhase.NOT_SENT -> {
                Text(
                    "The report could not leave the phone now; the app keeps trying for half an hour. " +
                        "Your Support ID is ${report.supportId}.",
                    color = Dc.text, fontSize = 15.sp, lineHeight = 21.sp
                )
                if (report.canSend) BigButton("Try again", null, true, false) { controller.sendReport(model) }
            }
            ReportPhase.NONE -> Unit
        }
    }
}
