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

/** The id of the page that opens on the Experiences and leads to the planner; it is shown nowhere, reached by id. */
internal const val SIMULATE_FEATURE_ID = "simulate"

/** The same page, offered inside Flyby through [io.motohub.android.module.ModuleExtensions]. */
internal const val SIMULATE_EXTENSION_ID = "simulate-in-flyby"

/** The calibration page on its own, reached from the module's page under Modules; shown nowhere else. */
internal const val CALIBRATE_FEATURE_ID = "calibrate"

private enum class Page { EXPERIENCES, PLANNER, PREVIEW, PLANS, CALIBRATION }

/**
 * The Experiences page, the planner and the screens it leads to, on the app's page ground.
 *
 * The Experiences page is the first (the calibration page, when [startOnCalibration]). A card or
 * "Plan by hand" pushes the planner over it. A card replaces the planner's stops by the experience's
 * (and plans its road the scenic way, as the card was). "Plan by hand" keeps the planner as it is when
 * it holds stops the rider made themselves; it starts a blank plan only when the planner is empty or
 * holds an experience's stops. The preview and
 * the saved plans are pushed over the planner and Back pops them, so a ride can be regenerated and
 * a plan opened without losing the stops. A saved plan that is opened replaces the stack by the
 * Experiences page and the planner, so Back from it is still the Experiences page. Back on the first
 * page leaves the module's screen through [onExit], which is the way back the host gave it.
 */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun RouteSimulatorApp(env: RsEnv, startOnCalibration: Boolean, onExit: () -> Unit) {
    val experiences = remember { ExperiencesModel(env) }
    val planner = remember { PlannerModel(env) }
    val plans = remember { PlansModel(env) }
    val calibration = remember { CalibrationModel(env) }
    var stack by remember { mutableStateOf<List<Page>>(listOf(if (startOnCalibration) Page.CALIBRATION else Page.EXPERIENCES)) }

    LaunchedEffect(Unit) { env.refreshFlyby() }
    DisposableEffect(Unit) {
        onDispose {
            experiences.dispose()
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
                Page.EXPERIENCES -> ExperiencesScreen(
                    env, experiences,
                    { plan ->
                        planner.openExperience(plan)
                        go(Page.PLANNER)
                    },
                    {
                        planner.planByHand()
                        go(Page.PLANNER)
                    },
                    { go(Page.PLANS) },
                    back
                )
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
                    stack = listOf(Page.EXPERIENCES, Page.PLANNER)
                }, back)
                Page.CALIBRATION -> CalibrationScreen(env, calibration, back)
            }
        }
    }
}
