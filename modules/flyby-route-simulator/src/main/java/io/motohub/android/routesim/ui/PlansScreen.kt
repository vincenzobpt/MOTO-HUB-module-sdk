// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// The plans the module remembers: open one in the planner, delete it, or file its route in the NAV.
package io.motohub.android.routesim.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableTarget
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.motohub.android.module.ModuleUi
import io.motohub.android.routesim.core.Plan

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun PlansScreen(env: RsEnv, model: PlansModel, onOpen: (Plan) -> Unit, onBack: () -> Unit) {
    LaunchedEffect(Unit) { model.reload() }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        TopBar(Strings.PLANS_TITLE, onBack) { }
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!model.loaded) {
                Txt(Strings.CHECKING, color = RsColors.Dim, size = 15)
            } else if (model.plans.isEmpty()) {
                Txt(Strings.PLANS_EMPTY, color = RsColors.Dim, size = 16)
            } else {
                for (plan in model.plans) {
                    PlanCard(env, model, plan, onOpen)
                }
            }
        }
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun PlanCard(env: RsEnv, model: PlansModel, plan: Plan, onOpen: (Plan) -> Unit) {
    val busy = model.busyId != null
    val thisBusy = model.busyId == plan.id
    val armed = model.confirmDeleteId == plan.id
    Card {
        Txt(plan.name, size = 18, weight = FontWeight.Bold, maxLines = 2)
        Txt(
            Strings.planStops(plan.stops.size) + "  ·  " + WhenMath.formatDate(plan.settings.startAtMillis, env.zone) +
                "  ·  " + Strings.style(plan.settings.style) + ", " + Strings.traffic(plan.settings.traffic),
            color = RsColors.Dim, size = 14, maxLines = 2
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton(Strings.PLAN_OPEN, Modifier.weight(1f), !busy, true) { onOpen(plan) }
            TextAction(
                if (armed) Strings.PLAN_DELETE_CONFIRM else Strings.PLAN_DELETE,
                RsColors.Bad, !busy
            ) { model.delete(plan) }
        }
        BigButton(Strings.PLAN_SAVE_AS_ROUTE, Modifier.fillMaxWidth(), !busy, false) { model.saveAsRoute(plan) }
        if (thisBusy) {
            Txt(model.busyText, color = RsColors.Accent, size = 14, weight = FontWeight.Bold)
        } else if (model.noticeId == plan.id && model.notice.isNotEmpty()) {
            Txt(model.notice, color = if (model.noticeIsError) RsColors.Bad else RsColors.Ok, size = 14, maxLines = 3)
        }
    }
}
