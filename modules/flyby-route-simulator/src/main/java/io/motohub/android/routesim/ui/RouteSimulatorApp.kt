// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// The simulator's screens and how they lead to one another.
package io.motohub.android.routesim.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableTarget
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.motohub.android.module.ModuleUi

/** The id of the planner among the module's features; it is shown nowhere, reached by id. */
internal const val SIMULATE_FEATURE_ID = "simulate"

/** The same planner, offered inside Flyby through [io.motohub.android.module.ModuleExtensions]. */
internal const val SIMULATE_EXTENSION_ID = "simulate-in-flyby"

/** The calibration page on its own, reached from the module's page under Modules; shown nowhere else. */
internal const val CALIBRATE_FEATURE_ID = "calibrate"

private enum class Page { PLANNER, PREVIEW, PLANS, CALIBRATION }

/**
 * The planner and the two screens it leads to, on the app's page ground.
 *
 * The planner is the first page (the calibration page, when [startOnCalibration]) and keeps everything the rider set; the preview and the saved
 * plans are pushed over it and Back pops them, so a ride can be regenerated and a plan opened
 * without losing the stops. Back on the planner leaves the module's screen through [onExit], which
 * is the way back the host gave it.
 */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun RouteSimulatorApp(env: RsEnv, startOnCalibration: Boolean, onExit: () -> Unit) {
    val planner = remember { PlannerModel(env) }
    val plans = remember { PlansModel(env) }
    val calibration = remember { CalibrationModel(env) }
    var stack by remember { mutableStateOf<List<Page>>(listOf(if (startOnCalibration) Page.CALIBRATION else Page.PLANNER)) }

    LaunchedEffect(Unit) { env.refreshFlyby() }
    DisposableEffect(Unit) {
        onDispose {
            planner.dispose()
            plans.cancel()
            calibration.cancel()
        }
    }

    val go: (Page) -> Unit = { next -> stack = ArrayList(stack).apply { add(next) } }
    val back: () -> Unit = {
        if (stack.size > 1) {
            if (stack[stack.size - 1] == Page.PREVIEW) {
                planner.preview?.cancel()
                planner.preview = null
            }
            stack = ArrayList(stack.subList(0, stack.size - 1))
        } else {
            onExit()
        }
    }

    env.host.ui.Backdrop(back) {
        Box(Modifier.fillMaxSize().background(RsColors.Bg)) {
            when (stack[stack.size - 1]) {
                Page.PLANNER -> PlannerScreen(env, planner, { go(Page.PREVIEW) }, { go(Page.PLANS) }, { go(Page.CALIBRATION) }, back)
                Page.PREVIEW -> {
                    val preview = planner.preview
                    if (preview != null) {
                        PreviewScreen(env, preview, back)
                    } else {
                        // Nothing to preview (it was cancelled): fall back to the planner.
                        LaunchedEffect(Unit) { back() }
                    }
                }
                Page.PLANS -> PlansScreen(env, plans, { plan ->
                    planner.load(plan)
                    stack = listOf(Page.PLANNER)
                }, back)
                Page.CALIBRATION -> CalibrationScreen(env, calibration, back)
            }
        }
    }
}
