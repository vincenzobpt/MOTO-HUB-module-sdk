// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.aa

import io.motohub.android.module.ModuleAudioSink
import io.motohub.android.module.ModuleAudioStream

/**
 * Where Android Auto's audio goes when the app has asked for it.
 *
 * Process-wide like [AaNavigationGuidance], for the same reason: the sink is installed by the
 * app through the module capability, and consumed by whichever transport happens to be running.
 * The two never meet directly.
 *
 * Whether Android Auto sends audio at all is decided once per session, at service discovery -
 * [wantsAudio] is read there. A sink installed after that point sees nothing until the next
 * session, which is worth a log line because the rider will otherwise think the feature is broken.
 */
object AaAudioTap {

    /** What we advertise for the media sink - the format headunit-revived has always negotiated. */
    const val MEDIA_SAMPLE_RATE = 48_000
    const val MEDIA_CHANNELS = 2

    /** What we advertise for the speech sink. */
    const val SPEECH_SAMPLE_RATE = 16_000
    const val SPEECH_CHANNELS = 1

    /** The system-sounds sink is always advertised; Android Auto refuses a head unit without one. */
    const val SYSTEM_SAMPLE_RATE = 16_000
    const val SYSTEM_CHANNELS = 1

    /** Media messages carry a 64-bit timestamp after the type on type 0, none on type 1. */
    private const val TIMESTAMPED_OFFSET = 10
    private const val PLAIN_OFFSET = 2

    @Volatile
    private var sink: ModuleAudioSink? = null

    /** True while a sink is installed - what service discovery asks before claiming the streams. */
    val wantsAudio: Boolean get() = sink != null

    fun install(newSink: ModuleAudioSink?) {
        val hadSink = sink != null
        sink = newSink
        when {
            newSink != null && !hadSink -> AaLog.i(
                "Audio sink installed: media and speech will be claimed at the next service discovery."
            )
            newSink == null && hadSink -> AaLog.i(
                "Audio sink removed: the phone keeps its own audio from the next session."
            )
        }
    }

    internal fun started(channel: Int) {
        val stream = streamOf(channel) ?: return
        val target = sink ?: return
        val (rate, channels) = formatOf(channel)
        AaLog.i("Audio %s started: %d Hz, %d ch", Channel.name(channel), rate, channels)
        runCatching { target.onStreamStarted(stream, rate, channels) }
            .onFailure { AaLog.e(it) }
    }

    internal fun stopped(channel: Int) {
        val stream = streamOf(channel) ?: return
        val target = sink ?: return
        AaLog.i("Audio %s stopped", Channel.name(channel))
        runCatching { target.onStreamStopped(stream) }
            .onFailure { AaLog.e(it) }
    }

    /** One media data message on an audio channel; a no-op with nobody listening. */
    internal fun deliver(message: AapMessage) {
        val target = sink ?: return
        val stream = streamOf(message.channel) ?: return
        val offset = if (message.type == 0) TIMESTAMPED_OFFSET else PLAIN_OFFSET
        val length = message.size - offset
        if (length <= 0) return
        runCatching { target.onPcm(stream, message.data, offset, length) }
            .onFailure { AaLog.e(it) }
    }

    private fun streamOf(channel: Int): ModuleAudioStream? = when (channel) {
        Channel.ID_AUD -> ModuleAudioStream.MEDIA
        Channel.ID_AU1 -> ModuleAudioStream.GUIDANCE
        Channel.ID_AU2 -> ModuleAudioStream.SYSTEM
        else -> null
    }

    private fun formatOf(channel: Int): Pair<Int, Int> = when (channel) {
        Channel.ID_AUD -> MEDIA_SAMPLE_RATE to MEDIA_CHANNELS
        Channel.ID_AU1 -> SPEECH_SAMPLE_RATE to SPEECH_CHANNELS
        else -> SYSTEM_SAMPLE_RATE to SYSTEM_CHANNELS
    }
}
