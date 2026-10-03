// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.dashcam.protocol

import io.motohub.android.dashcam.net.CameraHttp

/** A failure a rider can read: what went wrong, in words, not an exception's class name. */
class CameraException(message: String) : Exception(message)

/** Who answered: enough to tell the rider which camera this is and which family speaks for it. */
data class CameraIdentity(
    val model: String,
    val maker: String,
    val firmware: String,
    /** Anything else worth showing on the module's page, already labelled. */
    val details: List<Pair<String, String>>
)

/** What the camera says about itself right now. Null fields are things this camera does not report. */
data class CameraStatus(
    val recording: Boolean?,
    val batteryPercent: Int?,
    val charging: Boolean?,
    val cardReady: Boolean?,
    val cardFreeMb: Long?,
    val cardTotalMb: Long?
)

/**
 * One of the camera's own settings, as the camera lists it: [options] are what the rider reads,
 * [values] what goes back to the camera for each one, [current] the index of the one in use
 * (-1 when the camera did not say).
 */
data class CameraSetting(
    val key: String,
    val label: String,
    val options: List<String>,
    val values: List<String>,
    val current: Int
)

/** A folder on the camera's card: loop recordings, locked events, parking, photos. */
data class CameraFolder(val id: String, val label: String)

/** A file on the camera's card. [path] is what the camera serves it at over HTTP. */
data class CameraFile(
    val path: String,
    val name: String,
    val folder: String,
    val sizeBytes: Long,
    val createdMillis: Long,
    val durationSeconds: Int,
    val photo: Boolean
)

/**
 * One family of dashcam firmware and how it is spoken to.
 *
 * Many brands, few firmwares: the camera on a rider's bike is usually a white-label box running
 * one of a handful of chipset vendors' stacks (eeasytech, Novatek, HiSilicon, MStar), and within a
 * family the HTTP commands and the RTSP address are the same whatever the logo says. Each
 * implementation here is written from the protocol, not from anyone's code.
 *
 * [experimental] is true for a family nobody has run this module against yet: the module says so
 * to the rider, and asks for a report when something does not work.
 *
 * Every call blocks on the network: call them off the main thread. Failures throw
 * [CameraException] with a reason, or the IOException the socket gave.
 */
interface CameraProtocol {
    /** The family's name, as shown to the rider ("eeasytech"). */
    val family: String
    val experimental: Boolean

    /** Where this family's cameras usually sit, tried when the network does not say. */
    val defaultHosts: List<String>

    /**
     * Whether the camera opens its live picture only while recording. Such a camera is switched to
     * recording to open the stream, and recording is switched off again once the picture is up:
     * recording 4K to the card throttles the stream (the LINGTUO S1 sent 11 pictures a second
     * while recording, 30 without). It is switched back on when the live view closes.
     */
    val liveNeedsRecording: Boolean get() = false

    /** The folders this family keeps files in, in the order a rider expects them. */
    val folders: List<CameraFolder>

    /** Null when what answers at [http] is not a camera of this family. Must not change anything on the camera. */
    fun detect(http: CameraHttp): CameraIdentity?

    /** Says hello the way the camera's own app does, so it treats the phone as its client. */
    fun logon(http: CameraHttp, phoneAddress: String?) {}

    /** Says goodbye; best effort. */
    fun logout(http: CameraHttp, phoneAddress: String?) {}

    /** Called every few seconds while connected; some cameras drop a client that goes quiet. */
    fun heartbeat(http: CameraHttp)

    /** Sets the camera's clock to the phone's; best effort. */
    fun syncClock(http: CameraHttp) {}

    /** Readies the live picture and returns the RTSP address to open. */
    fun prepareLive(http: CameraHttp): String

    fun status(http: CameraHttp): CameraStatus
    fun setRecording(http: CameraHttp, on: Boolean)
    fun snapshot(http: CameraHttp)

    /** Protects the clip being recorded from being overwritten by the loop. Not every camera can. */
    fun lockClip(http: CameraHttp) {
        throw CameraException("This camera cannot lock a clip.")
    }
    fun settings(http: CameraHttp): List<CameraSetting>
    fun changeSetting(http: CameraHttp, setting: CameraSetting, option: Int)

    /** Some cameras must leave recording mode to list or serve their files. */
    fun enterFiles(http: CameraHttp) {}
    fun exitFiles(http: CameraHttp) {}
    fun files(http: CameraHttp, folder: CameraFolder): List<CameraFile>
    fun delete(http: CameraHttp, file: CameraFile)
    fun formatCard(http: CameraHttp)

    companion object {
        /** Every family the module speaks, the tested one first: detection goes in this order. */
        val all: List<CameraProtocol> = listOf(EeasyProtocol, NovatekProtocol, HisiliconProtocol, MstarProtocol)
    }
}
