// SPDX-License-Identifier: MPL-2.0
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
// of the MPL was not distributed with this file, you can obtain one at https://mozilla.org/MPL/2.0/.
package io.motohub.paddock.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context

/**
 * A system notification for what happens in a room while the rider is elsewhere: someone came
 * in, or the leader let me in. The room's own screen shows the same news as a toast; this one
 * reaches the rider on the NAV, on another app or with the phone in a pocket.
 */
internal class Notifier(private val context: Context) {

    private val manager = context.getSystemService(NotificationManager::class.java)
    private var created = false
    private var nextId = BASE_ID

    fun post(title: String, text: String) {
        val manager = manager ?: return
        runCatching {
            if (!created) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Paddock rooms", NotificationManager.IMPORTANCE_DEFAULT)
                        .apply { description = "Riders joining your Paddock rooms" }
                )
                created = true
            }
            // The app's own small icon: a module jar carries no resources of its own.
            val icon = context.resources.getIdentifier("ic_notification", "drawable", context.packageName)
                .takeIf { it != 0 } ?: android.R.drawable.stat_notify_chat
            val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
            val n = Notification.Builder(context, CHANNEL)
                .setSmallIcon(icon)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .apply { open?.let { setContentIntent(it) } }
                .build()
            manager.notify(nextId, n)
            nextId = if (nextId >= BASE_ID + 50) BASE_ID else nextId + 1
        }
    }

    private companion object {
        const val CHANNEL = "paddock_rooms"
        const val BASE_ID = 47_100
    }
}
