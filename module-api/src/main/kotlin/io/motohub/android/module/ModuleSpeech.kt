// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Vincenzo Buonomano.
// Part of the MOTO-HUB module SDK; see module-api/LICENSE.
package io.motohub.android.module

import java.io.File

/**
 * Offline speech recognition against a small grammar, from a module (contract 39).
 *
 * What "Hey Motohub" is listened for with: the app holds the microphone and hands every 100 ms of
 * 16-bit mono audio to [ModuleSpeechRecognizer.accept]; the recogniser and its native library
 * live in the module, so the APK does not carry them for riders who never turn voice control on.
 */
interface ModuleSpeechRecognizers {
    /**
     * A recogniser for the model unpacked in [modelDirectory], restricted to [grammarJson] (a JSON
     * array of phrases, "[unk]" for anything else). Loading the model takes a second or two:
     * call it off the main thread.
     */
    fun open(modelDirectory: File, sampleRate: Int, grammarJson: String): ModuleSpeechRecognizer
}

interface ModuleSpeechRecognizer : AutoCloseable {
    /** Takes [count] samples; true when an utterance just ended and [result] has it. */
    fun accept(samples: ShortArray, count: Int): Boolean

    /** The utterance that just ended, as JSON with per-word confidence (`{"result":[{"word","conf"}]}`). */
    fun result(): String

    /** Forgets anything half heard, as after MOTO-HUB's own beep or voice. */
    fun reset()

    override fun close()
}
