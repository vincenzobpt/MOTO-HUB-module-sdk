// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Vincenzo Buonomano.
// Part of the MOTO-HUB module SDK; see module-api/LICENSE.
package io.motohub.android.module

/**
 * The kinds of sound a projected app can send along with its picture.
 *
 * Named for what the rider hears, not for any protocol's channel numbers: a module that carries
 * music on channel 6 and spoken directions on channel 4 maps both onto these, and the app never
 * learns the numbers.
 */
enum class ModuleAudioStream {
    /** Music, podcasts - whatever the projected player is playing. */
    MEDIA,

    /** Spoken turn-by-turn directions and the assistant's voice. */
    GUIDANCE,

    /** Chimes and notification sounds. */
    SYSTEM
}

/**
 * Where a module puts the sound it receives, when the app has asked for it.
 *
 * Raw PCM, little-endian 16-bit, interleaved when there is more than one channel - the format
 * every projection protocol the app is likely to meet decodes to, so nothing here has to name
 * a codec. Calls arrive on the module's own transport thread: a sink must take the bytes and
 * get out of the way, never block on playback.
 */
interface ModuleAudioSink {
    /** The far side is about to send [stream] in this format. */
    fun onStreamStarted(stream: ModuleAudioStream, sampleRateHz: Int, channels: Int)

    /** One packet of [stream]. [pcm] is only valid for the duration of the call. */
    fun onPcm(stream: ModuleAudioStream, pcm: ByteArray, offset: Int, length: Int)

    /** The far side has stopped sending [stream]. */
    fun onStreamStopped(stream: ModuleAudioStream)
}

/**
 * The capability a module offers when what it projects can also hand over its sound.
 *
 * Without a sink installed a module lets the phone keep playing on its own - through whatever
 * headset the rider paired - and the app hears nothing. With one, the module asks the far side
 * for the streams at its next session start and delivers them here. Installing a sink mid-session
 * therefore takes effect at the *next* session, and a module says so in its log.
 */
interface ModuleAudio {
    /** Replaces any previous sink; null returns the sound to the phone. */
    fun setAudioSink(sink: ModuleAudioSink?)
}
