// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.aa.phone

/**
 * What a head unit said about itself in its ServiceDiscoveryResponse, as the phone role reads it.
 *
 * Field numbers are the protocol's (the same ones `ServiceDiscoveryResponse.kt` writes on the
 * head-unit side): response 1 = services; service 1 = id, 2 = sensor source, 3 = media sink,
 * 4 = input source; media sink 1 = codec, 4 = video configurations, 6 = display; video
 * configuration 1 = resolution, 2 = frame rate, 3/4 = width/height margin, 5 = density.
 *
 * A service's id is the channel its frames travel on. Units choose their own, so nothing here
 * assumes the head-unit side's fixed numbering.
 */
internal class PhoneDiscovery(
    val make: String,
    val model: String,
    val headUnitMake: String,
    val headUnitModel: String,
    val video: VideoService?,
    val input: InputService?,
    val sensors: SensorService?
) {
    class VideoConfig(
        /** The index the unit uses for it in Config and Start. */
        val index: Int,
        val resolution: Int,
        val frameRate: Int,
        val marginWidth: Int,
        val marginHeight: Int,
        val density: Int
    ) {
        val size: Pair<Int, Int>? get() = RESOLUTIONS[resolution]
        val fps: Int get() = if (frameRate == FRAME_RATE_60) 60 else 30

        override fun toString(): String {
            val s = size?.let { "${it.first}x${it.second}" } ?: "resolution#$resolution"
            return "$s@${fps}fps density=$density margin=${marginWidth}x$marginHeight"
        }
    }

    class VideoService(val channel: Int, val codec: Int, val displayId: Int, val configs: List<VideoConfig>)

    class InputService(
        val channel: Int,
        val keycodes: IntArray,
        val touchWidth: Int,
        val touchHeight: Int,
        val displayId: Int
    )

    class SensorService(val channel: Int, val types: IntArray)

    companion object {
        /** Codec numbers from the protocol's MediaCodecType. */
        const val CODEC_VIDEO_H264_BP = 3

        const val FRAME_RATE_60 = 1

        /** The protocol's VideoCodecResolutionType, in pixels. */
        val RESOLUTIONS: Map<Int, Pair<Int, Int>> = mapOf(
            1 to (800 to 480),
            2 to (1280 to 720),
            3 to (1920 to 1080),
            4 to (2560 to 1440),
            5 to (3840 to 2160),
            6 to (720 to 1280),
            7 to (1080 to 1920),
            8 to (1440 to 2560),
            9 to (2160 to 3840)
        )

        fun parse(body: ByteArray): PhoneDiscovery {
            val response = ProtoMessage.parse(body)
            val services = response.messages(1)

            // The main screen is the video sink on display 0; a unit with a second screen (LIVI
            // has one) lists its sinks in any order, and the first is not necessarily the main.
            val videoServices = services.mapNotNull { svc ->
                val sink = svc.message(3) ?: return@mapNotNull null
                val configs = sink.messages(4)
                if (configs.isEmpty()) return@mapNotNull null // an audio sink
                VideoService(
                    channel = svc.int(1),
                    codec = sink.int(1),
                    displayId = sink.int(6),
                    configs = configs.mapIndexed { i, c ->
                        VideoConfig(
                            index = i,
                            resolution = c.int(1),
                            frameRate = c.int(2),
                            marginWidth = c.int(3),
                            marginHeight = c.int(4),
                            density = c.int(5)
                        )
                    }
                )
            }
            val video = videoServices.firstOrNull { it.displayId == 0 } ?: videoServices.firstOrNull()

            // The touchscreen that belongs to that display, else any with a touchscreen, else the
            // first input service: LIVI lists an empty one for its second screen ahead of the main.
            val inputServices = services.mapNotNull { svc ->
                val inp = svc.message(4) ?: return@mapNotNull null
                val touch = inp.message(2)
                InputService(
                    channel = svc.int(1),
                    // A malformed key list costs the keys, not the whole discovery: everything
                    // else here already tolerates bad nested data.
                    keycodes = runCatching { inp.varints(1) }.getOrDefault(emptyList())
                        .map { it.toInt() }.toIntArray(),
                    touchWidth = touch?.int(1) ?: 0,
                    touchHeight = touch?.int(2) ?: 0,
                    displayId = inp.int(5)
                )
            }
            val input = inputServices.firstOrNull { it.displayId == (video?.displayId ?: 0) && it.touchWidth > 0 }
                ?: inputServices.firstOrNull { it.touchWidth > 0 }
                ?: inputServices.firstOrNull()

            val sensors = services.firstNotNullOfOrNull { svc ->
                val src = svc.message(2) ?: return@firstNotNullOfOrNull null
                val types = src.messages(1).map { it.int(1) }.toIntArray()
                if (types.isEmpty()) null else SensorService(svc.int(1), types)
            }

            return PhoneDiscovery(
                make = response.string(2).orEmpty(),
                model = response.string(3).orEmpty(),
                headUnitMake = response.string(7).orEmpty(),
                headUnitModel = response.string(8).orEmpty(),
                video = video,
                input = input,
                sensors = sensors
            )
        }
    }
}
