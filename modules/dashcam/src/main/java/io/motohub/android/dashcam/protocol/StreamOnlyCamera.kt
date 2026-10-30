// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.dashcam.protocol

import io.motohub.android.dashcam.net.CameraHttp
import java.io.IOException
import java.net.URI

/**
 * A camera that offers its picture and nothing else the module knows how to press: an IP camera,
 * or a dashcam of a family not spoken here, on the same network as the phone. Its standard RTSP
 * stream at [url] - found through ONVIF or by trying the usual addresses - is all there is: no
 * recording button, no settings, no files.
 *
 * Not one of [CameraProtocol.all]: nothing detects it. The search on the phone's network finds the
 * stream and makes one of these for it.
 */
class StreamOnlyCamera(private val url: String, override val family: String) : CameraProtocol {
    override val experimental = true
    override val controllable = false
    override val defaultHosts: List<String> = emptyList()
    override val folders: List<CameraFolder> = emptyList()

    private val streamPort = runCatching { URI(url).port }.getOrDefault(-1).takeIf { it > 0 } ?: if (url.startsWith("http", ignoreCase = true)) 80 else 554

    override fun detect(http: CameraHttp): CameraIdentity? = null

    /** The stream's port still answering is all a camera like this can say about being there. */
    override fun heartbeat(http: CameraHttp) {
        if (!http.reachable(streamPort)) throw IOException("The camera's video port does not answer.")
    }

    override fun prepareLive(http: CameraHttp): String = url

    override fun status(http: CameraHttp) = CameraStatus(null, null, null, null, null, null)

    override fun setRecording(http: CameraHttp, on: Boolean) = unsupported()
    override fun snapshot(http: CameraHttp) = unsupported()
    override fun settings(http: CameraHttp): List<CameraSetting> = emptyList()
    override fun changeSetting(http: CameraHttp, setting: CameraSetting, option: Int) = unsupported()
    override fun files(http: CameraHttp, folder: CameraFolder): List<CameraFile> = emptyList()
    override fun delete(http: CameraHttp, file: CameraFile) = unsupported()
    override fun formatCard(http: CameraHttp) = unsupported()

    private fun unsupported(): Nothing = throw CameraException("This camera shows its picture only: it has no buttons the phone can press.")
}
