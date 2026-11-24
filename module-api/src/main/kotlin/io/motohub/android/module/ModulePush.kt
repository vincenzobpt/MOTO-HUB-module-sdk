// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Vincenzo Buonomano.
// Part of the MOTO-HUB module SDK; see module-api/LICENSE.
package io.motohub.android.module

/**
 * A push for this module (contract 51): someone rang this phone through the token the module shared
 * ([MotoHubModuleHost.pushToken]), with "m" = the module's id in the data.
 *
 * A doorbell, not a mailbox: the data says what to open (a room, a call), the module fetches the
 * real content its own way once the rider opens it.
 */
interface ModulePushReceiver {
    /**
     * Shows the notification for [data] (every key the sender put in, "m" included) and returns true,
     * or returns false and the app shows a plain one that opens MOTO-HUB.
     *
     * Called on a background thread, often in a process just started for it, with seconds to spare:
     * read what is on disk and post the notification, nothing slower - no network, no engine start.
     * A high-priority push that shows nothing teaches Android to deliver the next ones late.
     */
    fun onPush(data: Map<String, String>): Boolean
}
