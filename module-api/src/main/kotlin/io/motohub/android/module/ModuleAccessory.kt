// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Vincenzo Buonomano.
// Part of the MOTO-HUB module SDK; see module-api/LICENSE.
package io.motohub.android.module

import java.io.InputStream

/**
 * A USB accessory the app has already opened, handed to a module to talk over.
 *
 * The app keeps the file descriptor and the accessory's lifecycle because it is the app that
 * Android hands the permission and the intent to; the module gets the two streams and nothing
 * else about it.
 */
interface ModuleAccessoryStreams {
    val input: InputStream
    fun write(bytes: ByteArray)
    fun close()
}

/** What a probe found, in a sentence a diagnostics report can carry. */
class ModuleProbeOutcome(val success: Boolean, val detail: String)

/**
 * The capability a module offers when it can tell whether a USB accessory is one of its own.
 *
 * Diagnostics only: this is how a rider finds out whether the cable and the head unit they
 * plugged in actually speak the protocol, without starting a session on the bike.
 */
interface ModuleAccessoryProbe {
    fun probe(host: MotoHubModuleHost, streams: ModuleAccessoryStreams): ModuleProbeOutcome
}

/**
 * The capability a module offers when it can sit between a real head unit and Android Auto.
 *
 * Diagnostics, and a way in. MOTO-HUB holds the USB accessory the head unit created and, at the
 * same time, dials Android Auto's own head unit server on the loopback - so Google's app believes
 * it is talking to a head unit, the head unit believes it is talking to a phone, and every byte
 * between them passes through here where it can be read. It is the same trick the wireless
 * dongles play, minus the wireless.
 *
 * What it answers is the question no amount of reasoning could: what the phone actually puts on
 * that wire first. That MOTO-HUB's own handshake attempt got no reply is only interesting once
 * there is something to compare it against.
 */
interface ModuleAccessoryBridge {
    fun bridge(host: MotoHubModuleHost, streams: ModuleAccessoryStreams): ModuleProbeOutcome
}
