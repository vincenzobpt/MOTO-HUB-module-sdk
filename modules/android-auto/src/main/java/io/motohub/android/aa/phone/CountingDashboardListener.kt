// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.aa.phone

import io.motohub.android.module.ModuleDashboardFrames
import io.motohub.android.module.ModuleDashboardListener

/** True when the app knows [ModuleDashboardFrames] (contract 49): only then may [CountingDashboardListener] load. */
internal val FRAMES_COUNTED: Boolean by lazy {
    runCatching { Class.forName("io.motohub.android.module.ModuleDashboardFrames") }.isSuccess
}

/**
 * The dashboard listener that also tells the app how many frames went to the head unit, so a scene that
 * composes nothing into the encoder is noticed and built again. Its own class, loaded only on an app
 * that has [ModuleDashboardFrames].
 */
internal class CountingDashboardListener(
    private val stop: (String) -> Unit,
    private val frames: () -> Long
) : ModuleDashboardListener, ModuleDashboardFrames {
    override fun onStopped(reason: String) = stop(reason)
    override fun framesSent(): Long = frames()
}
