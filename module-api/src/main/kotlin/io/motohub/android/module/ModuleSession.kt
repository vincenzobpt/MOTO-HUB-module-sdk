// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Vincenzo Buonomano.
// Part of the MOTO-HUB module SDK; see module-api/LICENSE.
package io.motohub.android.module

import kotlinx.coroutines.flow.StateFlow

/**
 * What a module's session is doing, in words the app can draw without knowing what the session is.
 *
 * The app has to show progress, refuse a second start, and say why one ended - all of which it
 * did with an Android-Auto-shaped vocabulary it should never have had. These six states are the
 * whole of what a host needs from any session: something is starting, something is ready but not
 * yet delivering, something is delivering, or it is over and here is why.
 */
sealed interface ModuleSessionState {
    data object Idle : ModuleSessionState

    /** Asked for, not yet able to receive anything. */
    data object Preparing : ModuleSessionState

    /** Able to receive, but nothing has arrived. The wait a rider is most likely to misread. */
    data object Ready : ModuleSessionState

    /** Delivering. */
    data object Streaming : ModuleSessionState

    data class Stopped(val reason: String) : ModuleSessionState

    data class Failed(val message: String) : ModuleSessionState
}

/**
 * Something to tell the rider while a session is starting.
 *
 * [actionable] is the whole reason this is not a plain string. Some of these are narration - "asking
 * the far side to project" - and some are an instruction the rider has to carry out by hand before
 * anything can work. They need different treatment on screen, and only the module knows which is
 * which. The app used to decide by comparing the text against two known strings, which meant those
 * strings could never move and a reworded one silently became narration.
 */
class ModuleSessionDetail(
    val text: String,
    val actionable: Boolean = false,
    /** Where to do it, when the instruction needs a place to be carried out. */
    val where: String? = null,
    /** What has to be true before that place exists at all. */
    val prerequisite: String? = null
)

/**
 * The one session a module may have running, shared with the host.
 *
 * Shared rather than owned by either side because both need it: the module drives the session and
 * knows what it is doing, while the app owns the foreground service that keeps it alive and the
 * screens that report it. An Android manifest cannot gain components at runtime, so the service
 * is the app's by necessity - this is how the two halves agree on what is happening inside it.
 *
 * One session at a time, deliberately: [isActive] is what lets a host refuse a second start, and
 * every module so far competes for the same fixed local resources anyway.
 */
interface ModuleSessionRuntime {
    val state: StateFlow<ModuleSessionState>

    /**
     * What the session is doing while it sits in [ModuleSessionState.Ready], or null when there
     * is nothing more specific to say.
     *
     * Its own channel because that wait is where a rider is most likely to think something is
     * broken: "ready" can last several seconds and several attempts, and a screen that claims to
     * be connected while nothing visibly happens is a screen that gets restarted by hand.
     */
    val startupDetail: StateFlow<ModuleSessionDetail?>

    fun publish(state: ModuleSessionState)

    fun publishStartupDetail(detail: ModuleSessionDetail?)

    /** Whether a session is up or on its way up, which is what a second start has to refuse. */
    fun isActive(): Boolean
}
