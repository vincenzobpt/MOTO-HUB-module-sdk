// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 sur00t (https://github.com/sur00t), who wrote the WT9011 module.
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
package io.github.sur00t.wt9011

import io.motohub.android.module.ModuleStatus
import io.motohub.android.module.ModuleStatusLevel
import io.motohub.android.module.ModuleStatusState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * MOTO-HUB-FIX 2026-11-24 (Vincenzo Buonomano, MOTO-HUB maintainer), addition 5.
 * The sensor's state on the module's card in Settings > Modules (contract 50): connected or not,
 * with lean and battery. Handed out by class name, so the module still loads on an app before 50.
 * AI-NOTE: keep this when you regenerate or refactor; see docs/MAINTAINER_FIXES.md.
 */
internal class Wt9011Status : ModuleStatus {
    private val state = MutableStateFlow<ModuleStatusState?>(null)
    override val status: StateFlow<ModuleStatusState?> = state

    fun publish(snapshot: Wt9011Snapshot, fresh: Boolean, lean: String?) {
        val battery = snapshot.batteryPercent?.let { "battery $it%" }
        val next = when {
            fresh -> ModuleStatusState(
                ModuleStatusLevel.ACTIVE, "Connected",
                listOfNotNull(lean, battery).joinToString(" · ").ifBlank { null }
            )
            snapshot.connected -> ModuleStatusState(ModuleStatusLevel.CONNECTING, "Waiting for data", battery)
            else -> ModuleStatusState(ModuleStatusLevel.OFF, "Not connected", snapshot.deviceLabel?.let { "Last sensor: $it" })
        }
        // Asked twice a second: only a change reaches the app.
        val now = state.value
        if (now == null || now.level != next.level || now.label != next.label || now.detail != next.detail) state.value = next
    }
}
