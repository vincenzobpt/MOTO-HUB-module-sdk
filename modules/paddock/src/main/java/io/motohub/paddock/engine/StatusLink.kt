// SPDX-License-Identifier: MPL-2.0
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
// of the MPL was not distributed with this file, you can obtain one at https://mozilla.org/MPL/2.0/.
package io.motohub.paddock.engine

import io.motohub.android.module.ModuleStatus
import io.motohub.android.module.ModuleStatusLevel
import io.motohub.android.module.ModuleStatusState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Paddock's card in Settings ▸ Modules (contract 50): online or offline, the room it rides with,
 * unread messages. Loaded only by name, so the module still runs on an app before 50.
 */
internal class StatusLink(controller: PaddockController) : ModuleStatus {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val status: StateFlow<ModuleStatusState?> =
        combine(controller.state, controller.disconnected, ::describe)
            .stateIn(scope, SharingStarted.Eagerly, describe(controller.state.value, controller.disconnected.value))

    fun release() = scope.cancel()

    private fun describe(state: PaddockState, disconnected: Boolean): ModuleStatusState {
        val unread = state.rooms.sumOf { it.unread }
        val unreadText = if (unread > 0) " · $unread unread" else ""
        if (disconnected) return ModuleStatusState(ModuleStatusLevel.OFF, "Disconnected", "Open Paddock and tap Connect")
        return when (state.stage) {
            EngineStage.STOPPED -> ModuleStatusState(
                ModuleStatusLevel.OFF, "Offline",
                if (state.rooms.isEmpty()) "Connects when you open Paddock" else "Connects when you go to your Paddock"
            )
            EngineStage.STARTING -> ModuleStatusState(ModuleStatusLevel.CONNECTING, "Connecting", state.startStep.ifBlank { null })
            EngineStage.FAILED -> ModuleStatusState(ModuleStatusLevel.ATTENTION, "Not working", state.error)
            EngineStage.RUNNING -> {
                val riding = state.riding
                when {
                    state.standby && riding == null -> ModuleStatusState(
                        ModuleStatusLevel.READY, "Standby", "Connects again when you open MOTO-HUB"
                    )
                    !state.online -> ModuleStatusState(ModuleStatusLevel.CONNECTING, "Connecting", "Reaching the relay")
                    riding != null -> ModuleStatusState(
                        ModuleStatusLevel.ACTIVE, "Riding",
                        "${riding.name} · ${riding.members.size} riders$unreadText"
                    )
                    else -> ModuleStatusState(
                        ModuleStatusLevel.READY, "Online",
                        when (state.rooms.size) {
                            0 -> "No room yet"
                            1 -> "1 room$unreadText"
                            else -> "${state.rooms.size} rooms$unreadText"
                        }
                    )
                }
            }
        }
    }
}
