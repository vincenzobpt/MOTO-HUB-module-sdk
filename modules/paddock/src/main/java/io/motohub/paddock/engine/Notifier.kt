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

    /** [roomId]: the room the news is about, opened by a tap; null opens Paddock's home. */
    fun post(title: String, text: String, roomId: String? = null) {
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
            // MOTO-HUB opens the module page named in the extras (plain strings: no link to the app).
            // One request code per notification, so each keeps its own room.
            val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                it.putExtra(EXTRA_MODULE_ID, "paddock")
                    .putExtra(EXTRA_FEATURE_ID, roomId?.let { id -> "room:$id" } ?: "paddock")
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                PendingIntent.getActivity(context, nextId, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
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

    /**
     * New chat messages in [roomId]: one notification per room, replaced by the next message, so a
     * busy chat does not fill the shade. A tap opens that room's chat. An emergency rings loud.
     */
    fun postMessages(roomId: String, roomName: String, text: String, unread: Int, urgent: Boolean) {
        val manager = manager ?: return
        runCatching {
            if (!messagesCreated) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_MESSAGES, "Paddock messages", NotificationManager.IMPORTANCE_HIGH)
                        .apply { description = "Chat messages in your Paddock rooms" }
                )
                messagesCreated = true
            }
            val icon = context.resources.getIdentifier("ic_notification", "drawable", context.packageName)
                .takeIf { it != 0 } ?: android.R.drawable.stat_notify_chat
            val id = messageId(roomId)
            val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                it.putExtra(EXTRA_MODULE_ID, "paddock")
                    .putExtra(EXTRA_FEATURE_ID, "chat:$roomId")
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                PendingIntent.getActivity(context, id, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
            val n = Notification.Builder(context, CHANNEL_MESSAGES)
                .setSmallIcon(icon)
                .setContentTitle(roomName)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .apply {
                    if (unread > 1) setSubText("$unread unread")
                    if (urgent) setCategory(Notification.CATEGORY_ALARM)
                    open?.let { setContentIntent(it) }
                }
                .build()
            manager.notify(id, n)
        }
    }

    /** The room's chat is on screen: its message notification has nothing left to say. */
    fun cancelMessages(roomId: String) {
        runCatching { manager?.cancel(messageId(roomId)) }
    }

    /**
     * A push said [name] wrote in [roomId] (contract 51): the message itself comes over Reticulum once
     * Paddock reconnects, and replaces this notification (same id) with its text.
     */
    fun postWakeMessage(roomId: String, roomName: String, name: String): Boolean {
        if (manager == null) return false
        postMessages(roomId, roomName, "$name sent you a message. Open it to read it.", 0, urgent = false)
        return true
    }

    /**
     * A push said [name] is calling from [roomId]: it rings like a call and opens the room, where
     * Paddock reconnects and the caller, still dialling, gets through; the real ringing notification
     * then takes this one's place (same id as VoiceCall's). Gone by itself when the caller gives up.
     */
    fun postWakeCall(roomId: String, roomName: String, name: String): Boolean {
        val manager = manager ?: return false
        return runCatching {
            // Its own channel, with the phone's ringtone: woken by a push, Paddock cannot play a ringtone
            // itself (VoiceCall's channel is silent because VoiceCall rings on its own).
            manager.createNotificationChannel(
                NotificationChannel(RING_CHANNEL, "Paddock incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Rings when a rider of your Paddock rooms calls while MOTO-HUB is in the background"
                    setSound(
                        android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE),
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 800, 600, 800, 600)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
            val icon = context.resources.getIdentifier("ic_notification", "drawable", context.packageName)
                .takeIf { it != 0 } ?: android.R.drawable.sym_call_incoming
            fun page(code: Int, overLock: Boolean) = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                it.putExtra(EXTRA_MODULE_ID, "paddock").putExtra(EXTRA_FEATURE_ID, "room:$roomId")
                    .putExtra(EXTRA_SHOW_WHEN_LOCKED, overLock)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                PendingIntent.getActivity(context, code, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
            val n = Notification.Builder(context, RING_CHANNEL)
                .setSmallIcon(icon)
                .setContentTitle("$name is calling")
                .setContentText("Paddock · $roomName. Open to answer.")
                .setCategory(Notification.CATEGORY_CALL)
                .setAutoCancel(true)
                .setTimeoutAfter(WAKE_CALL_MILLIS)
                .apply {
                    page(WAKE_CALL_ID + 1, false)?.let { setContentIntent(it) }
                    page(WAKE_CALL_ID + 2, true)?.let { setFullScreenIntent(it, true) }
                }
                .build()
            // Rings again and again, like a phone call, until tapped, replaced or timed out.
            n.flags = n.flags or Notification.FLAG_INSISTENT
            manager.notify(WAKE_CALL_ID, n)
            true
        }.getOrDefault(false)
    }

    private var messagesCreated = false

    private fun messageId(roomId: String) = MESSAGES_BASE_ID + (roomId.hashCode() and 0x3FF)

    private companion object {
        const val CHANNEL_MESSAGES = "paddock_messages"
        const val MESSAGES_BASE_ID = 47_200
        const val CHANNEL = "paddock_rooms"
        const val BASE_ID = 47_100
        const val EXTRA_MODULE_ID = "io.motohub.android.extra.OPEN_MODULE_ID"
        const val EXTRA_FEATURE_ID = "io.motohub.android.extra.OPEN_MODULE_FEATURE_ID"
        const val EXTRA_SHOW_WHEN_LOCKED = "io.motohub.android.extra.SHOW_WHEN_LOCKED"
        // VoiceCall's notification id: the call it shows replaces the push's ringing.
        const val RING_CHANNEL = "paddock_ring"
        const val WAKE_CALL_ID = 47_090
        // The caller keeps dialling 90 s (paddock_voice.WOKEN_RING_S).
        const val WAKE_CALL_MILLIS = 90_000L
    }
}
