// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// The module's page under Modules, drawn with the app's own vocabulary.
package io.motohub.android.routesim.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableTarget
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.motohub.android.module.ModuleUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun AboutScreen(env: RsEnv, onBack: () -> Unit) {
    var plans by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        env.refreshFlyby()
        plans = withContext(Dispatchers.Default) {
            try { env.plans.all().size } catch (_: Throwable) { 0 }
        }
    }
    val ui = env.host.ui
    ui.Screen(Strings.ABOUT_TITLE, onBack) {
        ui.Paragraph(Strings.ABOUT_BLURB)
        ui.SectionLabel(Strings.ABOUT_NOW)
        ui.Fact(
            Strings.ABOUT_FACT_FLYBY,
            when (env.flybyInstalled) {
                true -> Strings.INSTALLED
                false -> Strings.NOT_INSTALLED
                null -> Strings.CHECKING
            },
            false
        )
        ui.Fact(Strings.ABOUT_FACT_PLANS, plans?.let { Strings.plansCount(it) } ?: Strings.CHECKING, false)
        ui.Fact(Strings.ABOUT_FACT_NETWORK, Strings.ABOUT_NETWORK_VALUE, false)
        ui.Fact(Strings.ABOUT_FACT_TUNED, StyleBase.tunedAnswer(env.learned), false)
        ui.ActionRow(Strings.ABOUT_START, Strings.ABOUT_START_HINT) {
            // The host opens it; the planner is a feature shown nowhere else.
            env.host.openFeature(SIMULATE_FEATURE_ID)
        }
        ui.ActionRow(Strings.ABOUT_CALIBRATE, Strings.ABOUT_CALIBRATE_HINT) {
            env.host.openFeature(CALIBRATE_FEATURE_ID)
        }
    }
}
