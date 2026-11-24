// SPDX-License-Identifier: MPL-2.0
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
// of the MPL was not distributed with this file, you can obtain one at https://mozilla.org/MPL/2.0/.
package io.motohub.paddock.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableTarget
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.motohub.android.module.ModuleUi
import io.motohub.android.module.MotoHubModuleHost
import io.motohub.paddock.engine.EngineStage
import io.motohub.paddock.engine.PaddockController
import kotlinx.coroutines.delay

/** One screen of the module. Welcome and Home are roots, chosen by the state; the rest open on top. */
private sealed interface Screen {
    data object Welcome : Screen
    data object Home : Screen
    data class Room(val id: String) : Screen
    data class EditProfile(val firstTime: Boolean) : Screen
    data object CreateRoom : Screen
    data class Invite(val id: String) : Screen
    data class Chat(val id: String) : Screen
    data class Join(val paste: Boolean) : Screen
    data object Privacy : Screen
    data object Settings : Screen
    data object Relays : Screen
    data object Backup : Screen
    data object Restore : Screen
}

/**
 * The whole module UI. The root follows the state: no profile → Welcome, otherwise Home; when it
 * changes (profile made, ID forgotten) the screens on top are dropped. A room's screens carry its
 * id and close by themselves when the room is gone (left, deleted by its leader, join failed).
 * System back closes an open sheet, then the top screen, and leaves the module from a root.
 */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
fun PaddockApp(
    controller: PaddockController,
    host: MotoHubModuleHost,
    onBack: () -> Unit,
    /** A room to open straight away, from a notification about it; back from it goes to Home. */
    startRoom: String? = null,
    /** With [startRoom]: its chat on top of it, from a notification about a message. */
    startChat: Boolean = false
) {
    val state by controller.state.collectAsState()
    val profile by controller.profile.collectAsState()
    val disconnected by controller.disconnected.collectAsState()
    val root: Screen = if (profile == null) Screen.Welcome else Screen.Home
    var stack by remember(root, startRoom, startChat) {
        mutableStateOf<List<Screen>>(
            when {
                root != Screen.Home || startRoom == null -> emptyList()
                startChat -> listOf(Screen.Room(startRoom), Screen.Chat(startRoom))
                else -> listOf(Screen.Room(startRoom))
            }
        )
    }
    val top = if (stack.isEmpty()) root else stack[stack.size - 1]
    val slot = remember { BackSlot() }
    val push: (Screen) -> Unit = { s -> stack = ArrayList(stack).apply { add(s) } }
    // Create and join hand over to the new room: it takes their place, so back goes to Home.
    val replaceTop: (Screen) -> Unit = { s ->
        stack = ArrayList(stack.subList(0, if (stack.isEmpty()) 0 else stack.size - 1)).apply { add(s) }
    }
    LaunchedEffect(state.rooms, stack) {
        // Only a running engine knows the rooms: opened from a notification at a cold start, the
        // room arrives a moment later and must not be dropped before it does.
        if (state.stage != EngineStage.RUNNING) return@LaunchedEffect
        val kept = ArrayList<Screen>(stack.size)
        for (s in stack) {
            val gone = (s is Screen.Room && state.room(s.id) == null) || (s is Screen.Invite && state.room(s.id) == null) ||
                (s is Screen.Chat && state.room(s.id) == null)
            if (!gone) kept.add(s)
        }
        if (kept.size != stack.size) stack = kept
    }
    val back: () -> Unit = {
        val sheet = slot.handler
        when {
            // The call screen covers everything: back must not pop a screen hidden under it.
            state.call != null -> {}
            sheet != null -> sheet()
            stack.isNotEmpty() -> stack = ArrayList(stack.subList(0, stack.size - 1))
            else -> onBack()
        }
    }

    LaunchedEffect(profile != null) {
        if (profile != null) controller.ensureStarted()
    }

    var toast by remember { mutableStateOf<ToastMessage?>(null) }
    var toastSeq by remember { mutableIntStateOf(0) }
    val showToast: (ToastMessage) -> Unit = { m ->
        toast = m
        toastSeq++
    }
    LaunchedEffect(controller) {
        controller.events.collect { showToast(toastFor(it)) }
    }
    LaunchedEffect(toastSeq) {
        if (toast != null) {
            delay(4200)
            toast = null
        }
    }

    host.ui.Backdrop(back) {
        FollowAppTheme()
        LightSystemBars()
        Box(Modifier.fillMaxSize().background(P.bg)) {
            key(top) {
                Enter {
                    val p = profile
                    when (top) {
                        Screen.Welcome -> WelcomeScreen({ push(Screen.EditProfile(true)) }, { push(Screen.Restore) }, { push(Screen.Privacy) }, back)
                        Screen.Home -> if (p != null) HomeScreen(
                            state, p,
                            onOpenRoom = { push(Screen.CreateRoom) },
                            onScan = { push(Screen.Join(paste = false)) },
                            onPaste = { push(Screen.Join(paste = true)) },
                            onRoom = { id -> push(Screen.Room(id)) },
                            onPrivacy = { push(Screen.Privacy) },
                            onSettings = { push(Screen.Settings) },
                            onRetry = { controller.ensureStarted() },
                            onRestart = { context -> controller.restartMotoHub(context) },
                            onBack = back,
                            disconnected = disconnected,
                            onDisconnect = { controller.disconnect() },
                            onConnect = { controller.connect() }
                        )
                        is Screen.Room -> {
                            val r = state.room(top.id)
                            if (r != null) RoomScreen(
                                controller, r, state.riding, state.myHash, slot,
                                onInvite = { push(Screen.Invite(top.id)) },
                                onChat = { push(Screen.Chat(top.id)) },
                                onToast = showToast,
                                onBack = back
                            )
                        }
                        is Screen.Chat -> {
                            val r = state.room(top.id)
                            if (r != null) ChatScreen(controller, r, slot, showToast, back)
                        }
                        is Screen.EditProfile -> ProfileScreen(
                            if (top.firstTime) null else p,
                            // The first time, the new profile changes the root by itself.
                            onSave = { controller.saveProfile(it); if (!top.firstTime) back() },
                            onBack = back
                        )
                        Screen.CreateRoom -> CreateRoomScreen(controller, state.rooms, { id -> replaceTop(Screen.Room(id)) }, back)
                        is Screen.Invite -> {
                            val r = state.room(top.id)
                            if (r != null) InviteScreen(controller, r, back)
                        }
                        is Screen.Join -> JoinScreen(controller, state.rooms, slot, top.paste, { id -> replaceTop(Screen.Room(id)) }, back)
                        Screen.Privacy -> PrivacyScreen(back)
                        Screen.Settings -> SettingsScreen(
                            controller, state, p, slot,
                            onEditProfile = { push(Screen.EditProfile(false)) },
                            onBackup = { push(Screen.Backup) },
                            onRestore = { push(Screen.Restore) },
                            onRelays = { push(Screen.Relays) },
                            onBack = back
                        )
                        Screen.Relays -> RelaysScreen(controller, state, back)
                        Screen.Backup -> BackupScreen(controller, showToast, back)
                        Screen.Restore -> RestoreScreen(
                            controller, slot, replacing = p != null,
                            onDone = {
                                if (p == null) {
                                    // A key is not a profile: the name, colour and bike come next.
                                    stack = listOf(Screen.EditProfile(true))
                                    showToast(ToastMessage("Rider ID restored. Now choose how riders see you.", PI.CHECK, P.ok))
                                } else {
                                    stack = emptyList()
                                    showToast(ToastMessage("Rider ID restored.", PI.CHECK, P.ok))
                                }
                            },
                            onBack = back
                        )
                    }
                }
            }
            // A call covers every screen until it ends; the room is still there underneath.
            state.call?.let { CallScreen(controller, it) }
            Toast(toast) { toast = null }
        }
    }
}

/**
 * Light status and navigation bar icons while Paddock is on screen: its ground is dark, and the
 * app's own screens may have set dark icons. The appearance before is put back on the way out.
 */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun LightSystemBars() {
    val context = LocalContext.current
    val light = P.isLight
    DisposableEffect(context, light) {
        val bars = context.findActivity()?.window?.insetsController
        val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        val before = bars?.systemBarsAppearance ?: 0
        // Bar icons that read on Paddock's ground: dark on a light theme, light on a dark one.
        bars?.setSystemBarsAppearance(if (light) mask else 0, mask)
        // The app's window pans for the keyboard and Paddock's screens pad for it (imePadding): both
        // together lift the chat's composer twice the keyboard's height. While Paddock is shown the
        // window stays put and imePadding alone makes room.
        val window = context.findActivity()?.window
        val inputBefore = window?.attributes?.softInputMode
        window?.setSoftInputMode(
            ((inputBefore ?: 0) and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST.inv()) or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        )
        onDispose {
            bars?.setSystemBarsAppearance(before and mask, mask)
            if (inputBefore != null) window.setSoftInputMode(inputBefore)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
