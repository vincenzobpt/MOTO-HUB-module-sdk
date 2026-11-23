// SPDX-License-Identifier: MPL-2.0
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
// of the MPL was not distributed with this file, you can obtain one at https://mozilla.org/MPL/2.0/.
package io.motohub.paddock.engine

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.chaquo.python.PyObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Everything around a call that is not the network: the ringtone and the ringback, the
 * notification with Answer / Decline / Hang up, the audio while connected, and the 20 ms loop
 * that brings arrived packets from Python to [VoiceAudio]. Driven by the engine's poll: [update]
 * gets each new [Call] (or null) once a second; media never waits for that poll.
 */
internal class VoiceCall(
    private val context: Context,
    private val python: () -> PyObject?,
    private val actions: Actions,
    private val log: (String) -> Unit
) {
    interface Actions {
        fun answer()
        fun decline()
        fun hangUp()
    }

    private val main = Handler(Looper.getMainLooper())
    /** Python calls for media, off the engine's one-a-second thread. */
    private val exec = Executors.newSingleThreadScheduledExecutor { Thread(it, "paddock-voice").apply { isDaemon = true } }
    private val audio = VoiceAudio(context, send = { p -> exec.execute { runCatching { python()?.callAttr("voice_send", p) } } }, log = log)
    private var pulling: ScheduledFuture<*>? = null
    private var ringtone: Ringtone? = null
    private var ringback: ToneGenerator? = null
    private var shown: Call? = null
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private var channelMade = false

    private val _audioState = MutableStateFlow(CallAudio())
    val audioState: StateFlow<CallAudio> = _audioState

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                ACTION_ANSWER -> actions.answer()
                ACTION_DECLINE -> actions.decline()
                ACTION_HANG_UP -> actions.hangUp()
            }
        }
    }

    init {
        val filter = IntentFilter().apply { addAction(ACTION_ANSWER); addAction(ACTION_DECLINE); addAction(ACTION_HANG_UP) }
        runCatching { context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED) }
    }

    /** Whether Android lets an incoming call light the screen (Settings: full-screen notifications). */
    fun fullScreenAllowed(): Boolean = notifications?.canUseFullScreenIntent() ?: false

    fun micAllowed(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** On the main thread: the call as the engine sees it now. */
    fun update(call: Call?) {
        val before = shown
        shown = call
        if (call?.stage == before?.stage && call?.peer == before?.peer) return
        stopTones()
        when (call?.stage) {
            CallStage.RINGING_IN -> {
                ring()
                notifyCall(call, "${call.name} is calling", ringing = true)
            }
            CallStage.CALLING, CallStage.RINGING_OUT -> {
                if (call.stage == CallStage.RINGING_OUT) ringback()
                notifyCall(call, "Calling ${call.name}…", ringing = false)
            }
            CallStage.CONNECTED -> {
                startAudio()
                notifyCall(call, "In a call with ${call.name}", ringing = false)
            }
            null -> {
                stopAudio()
                notifications?.cancel(NOTIFICATION_ID)
            }
        }
        proximity()
    }

    private var earLock: PowerManager.WakeLock? = null

    /**
     * Screen off at the ear, like a phone call: while the call rings out or is on, through the
     * phone's own earpiece (not on the speaker, not on a headset).
     */
    private fun proximity() {
        val st = _audioState.value
        val want = shown?.stage.let { it == CallStage.CONNECTED || it == CallStage.CALLING || it == CallStage.RINGING_OUT } &&
            !st.speaker && !st.headset
        runCatching {
            if (want && earLock == null) {
                val pm = context.getSystemService(PowerManager::class.java)
                if (pm?.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) == true) {
                    earLock = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "paddock:ear").apply {
                        setReferenceCounted(false); acquire(4 * 60 * 60 * 1000L)
                    }
                }
            } else if (!want) {
                earLock?.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
                earLock = null
            }
        }
    }

    fun setMuted(muted: Boolean) {
        audio.muted = muted
        _audioState.value = _audioState.value.copy(muted = muted)
    }

    fun setSpeaker(on: Boolean) {
        audio.setSpeaker(on)
        _audioState.value = _audioState.value.copy(speaker = on)
        proximity()
    }

    private fun startAudio() {
        if (!micAllowed()) log("call without the microphone permission: the other rider hears nothing")
        val headset = audio.headsetConnected()
        _audioState.value = CallAudio(muted = false, speaker = false, headset = headset)
        audio.muted = false
        audio.start(speakerOn = false)
        pulling?.cancel(false)
        pulling = exec.scheduleWithFixedDelay(::pull, 0, PULL_MILLIS, TimeUnit.MILLISECONDS)
    }

    private fun stopAudio() {
        pulling?.cancel(false)
        pulling = null
        exec.execute { audio.stop() }
    }

    /** Arrived packets, [u16 length][packet]..., into the jitter buffer. */
    private fun pull() {
        val raw = runCatching { python()?.callAttr("voice_pull")?.toJava(ByteArray::class.java) }.getOrNull() ?: return
        val b = ByteBuffer.wrap(raw)
        while (b.remaining() >= 2) {
            val n = b.short.toInt() and 0xFFFF
            if (b.remaining() < n) break
            audio.receive(ByteArray(n).also { b.get(it) })
        }
    }

    private fun ring() {
        runCatching {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(context, uri)?.apply {
                audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                isLooping = true
                play()
            }
        }
    }

    private fun ringback() {
        runCatching {
            ringback = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70).apply { startTone(ToneGenerator.TONE_SUP_RINGTONE) }
        }
    }

    private fun stopTones() {
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching { ringback?.stopTone(); ringback?.release() }
        ringback = null
    }

    private fun notifyCall(call: Call, title: String, ringing: Boolean) {
        val nm = notifications ?: return
        runCatching {
            if (!channelMade) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Paddock calls", NotificationManager.IMPORTANCE_HIGH)
                        .apply { description = "Calls from riders in your Paddock rooms"; setSound(null, null) }
                )
                channelMade = true
            }
            val icon = context.resources.getIdentifier("ic_notification", "drawable", context.packageName)
                .takeIf { it != 0 } ?: android.R.drawable.sym_call_incoming
            // Straight to Paddock's page, where the call screen shows; the full-screen one also asks
            // MOTO-HUB (0.1.74+) to light the screen and show above the lock screen. Older apps
            // ignore the extras and just open.
            fun page(code: Int, overLock: Boolean) = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                it.putExtra(EXTRA_MODULE_ID, "paddock").putExtra(EXTRA_FEATURE_ID, "paddock")
                    .putExtra(EXTRA_SHOW_WHEN_LOCKED, overLock)
                PendingIntent.getActivity(context, code, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
            val open = page(1, false)
            fun action(label: String, what: String, code: Int) = Notification.Action.Builder(
                null, label,
                PendingIntent.getBroadcast(
                    context, code, Intent(what).setPackage(context.packageName),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            ).build()
            val b = Notification.Builder(context, CHANNEL)
                .setSmallIcon(icon)
                .setContentTitle(title)
                .setContentText("Paddock · ${roomName(call)}")
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_CALL)
                .apply { open?.let { setContentIntent(it) } }
            if (ringing) {
                // Answering needs the microphone: without the permission, open the app instead.
                if (micAllowed()) b.addAction(action("Answer", ACTION_ANSWER, 11))
                b.addAction(action("Decline", ACTION_DECLINE, 12))
                page(2, true)?.let { b.setFullScreenIntent(it, true) }
            } else {
                b.addAction(action("Hang up", ACTION_HANG_UP, 13))
            }
            nm.notify(NOTIFICATION_ID, b.build())
        }
    }

    private var roomName: (Call) -> String = { "" }
    fun setRoomNames(f: (Call) -> String) { roomName = f }

    fun release() {
        shown = null
        proximity()
        stopTones()
        stopAudio()
        notifications?.cancel(NOTIFICATION_ID)
        runCatching { context.unregisterReceiver(receiver) }
        exec.shutdown()
    }

    private companion object {
        const val CHANNEL = "paddock_calls"
        const val NOTIFICATION_ID = 47_090
        // MOTO-HUB's ModuleFeatureLaunchRequest extras, by value: no link to the app's classes.
        const val EXTRA_MODULE_ID = "io.motohub.android.extra.OPEN_MODULE_ID"
        const val EXTRA_FEATURE_ID = "io.motohub.android.extra.OPEN_MODULE_FEATURE_ID"
        const val EXTRA_SHOW_WHEN_LOCKED = "io.motohub.android.extra.SHOW_WHEN_LOCKED"
        const val PULL_MILLIS = 20L
        const val ACTION_ANSWER = "io.motohub.paddock.CALL_ANSWER"
        const val ACTION_DECLINE = "io.motohub.paddock.CALL_DECLINE"
        const val ACTION_HANG_UP = "io.motohub.paddock.CALL_HANG_UP"
    }
}
