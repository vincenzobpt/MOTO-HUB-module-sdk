// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.aa

import io.motohub.android.aa.proto.Control
import io.motohub.android.aa.proto.Media
import io.motohub.android.aaplugin.AaVideoConfig
import io.motohub.android.aaplugin.AaVideoPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * What the module puts on the wire for a given video configuration.
 *
 * Deliberately only that. Which configuration a dashboard should get - which preset a learned
 * geometry resolves to, when a smaller projection canvas must not become an AAP margin - is the
 * app's decision and is tested there, in AndroidAutoCapabilityProfileTest. The module is handed
 * the answer and has one job: describe it truthfully to Android Auto.
 */
class ServiceDiscoveryResponseTest {

    private fun config(
        preset: AaVideoPreset,
        densityDpi: Int,
        videoWidth: Int,
        videoHeight: Int,
        touchWidth: Int = videoWidth,
        touchHeight: Int = videoHeight,
        touchEnabled: Boolean = true
    ) = AaVideoConfig(
        preset = preset,
        densityDpi = densityDpi,
        videoWidth = videoWidth,
        videoHeight = videoHeight,
        touchWidth = touchWidth,
        touchHeight = touchHeight,
        touchEnabled = touchEnabled,
        sourceLabel = "TEST"
    )

    private fun AaVideoConfig.respond(audioSinks: Boolean = false): Control.ServiceDiscoveryResponse =
        ServiceDiscoveryResponse(this, audioSinks)
            .parse(Control.ServiceDiscoveryResponse.newBuilder())
            .build()

    private fun Control.ServiceDiscoveryResponse.audioSink(channel: Int) = servicesList
        .firstOrNull { it.id == channel }
        ?.takeIf { it.hasMediaSinkService() }
        ?.mediaSinkService

    private fun Control.ServiceDiscoveryResponse.video() = servicesList
        .first { it.id == Channel.ID_VID }
        .mediaSinkService
        .videoConfigsList
        .single()

    private fun Control.ServiceDiscoveryResponse.touchscreen() = servicesList
        .first { it.id == Channel.ID_INP }
        .inputSourceService
        .touchscreen

    @Test
    fun `claims no music or speech unless the app has somewhere to put them`() {
        val response = config(AaVideoPreset.LANDSCAPE_800X480, 160, 800, 480).respond()

        assertEquals(null, response.audioSink(Channel.ID_AUD))
        assertEquals(null, response.audioSink(Channel.ID_AU1))
        // The system-sounds sink is the one Android Auto insists on; always there.
        assertEquals(Media.AudioStreamType.SYSTEM, response.audioSink(Channel.ID_AU2)?.audioType)
    }

    @Test
    fun `claims music and speech as PCM when asked`() {
        val response = config(AaVideoPreset.LANDSCAPE_800X480, 160, 800, 480).respond(audioSinks = true)

        val media = response.audioSink(Channel.ID_AUD)!!
        assertEquals(Media.AudioStreamType.MEDIA, media.audioType)
        assertEquals(Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM, media.availableType)
        assertEquals(48_000, media.audioConfigsList.single().sampleRate)
        assertEquals(2, media.audioConfigsList.single().numberOfChannels)

        val speech = response.audioSink(Channel.ID_AU1)!!
        assertEquals(Media.AudioStreamType.SPEECH, speech.audioType)
        assertEquals(16_000, speech.audioConfigsList.single().sampleRate)
        assertEquals(1, speech.audioConfigsList.single().numberOfChannels)
    }

    @Test
    fun `announces the head unit identity Android Auto is known to accept`() {
        val response = config(AaVideoPreset.LANDSCAPE_800X480, 160, 800, 480).respond()

        assertEquals("MOTO-HUB", response.make)
        assertEquals("MotoPlay", response.model)
        assertEquals("2024", response.year)
        assertEquals("motohub", response.vehicleId)
        assertEquals("CFMoto", response.headUnitMake)
        assertEquals("CFDL16-6GUV", response.headUnitModel)
        assertEquals("0.1.0", response.headUnitSoftwareVersion)
    }

    @Test
    fun `landscape config becomes its protocol resolution, density and touch surface`() {
        val response = config(AaVideoPreset.LANDSCAPE_800X480, 160, 800, 480).respond()

        assertEquals(
            Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._800x480,
            response.video().codecResolution
        )
        assertEquals(160, response.video().density)
        assertEquals(0, response.video().marginWidth)
        assertEquals(0, response.video().marginHeight)
        assertEquals(800, response.touchscreen().width)
        assertEquals(480, response.touchscreen().height)
    }

    @Test
    fun `portrait config becomes its protocol resolution, density and touch surface`() {
        val response = config(AaVideoPreset.PORTRAIT_720X1280, 240, 720, 1280).respond()

        assertEquals(
            Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._720x1280,
            response.video().codecResolution
        )
        assertEquals(240, response.video().density)
        assertEquals(720, response.touchscreen().width)
        assertEquals(1280, response.touchscreen().height)
    }

    /**
     * The margins are the difference between the video and the part of it a rider can touch, and
     * they reach the wire as that difference - not as the two halves it was computed from, which
     * the module never sees.
     */
    @Test
    fun `a touch surface smaller than the video becomes the protocol margins`() {
        val response = config(
            AaVideoPreset.LANDSCAPE_800X480,
            densityDpi = 160,
            videoWidth = 800,
            videoHeight = 480,
            touchWidth = 680,
            touchHeight = 408
        ).respond()

        assertEquals(120, response.video().marginWidth)
        assertEquals(72, response.video().marginHeight)
        assertEquals(680, response.touchscreen().width)
        assertEquals(408, response.touchscreen().height)
    }

    @Test
    fun `omits the touchscreen for a handlebar-only dashboard`() {
        val response = config(
            AaVideoPreset.LANDSCAPE_800X480, 160, 800, 480, touchEnabled = false
        ).respond()

        assertFalse(response.servicesList.first { it.id == Channel.ID_INP }.inputSourceService.hasTouchscreen())
    }

    @Test
    fun `every preset maps to the protocol resolution of the same name`() {
        val expected = mapOf(
            AaVideoPreset.LANDSCAPE_1280X720 to
                Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1280x720,
            AaVideoPreset.LANDSCAPE_1920X1080 to
                Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1920x1080,
            AaVideoPreset.PORTRAIT_1080X1920 to
                Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1080x1920,
            AaVideoPreset.PORTRAIT_1440X2560 to
                Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1440x2560
        )
        expected.forEach { (preset, protocol) ->
            val response = config(preset, 240, 1280, 720).respond()
            assertEquals("$preset", protocol, response.video().codecResolution)
        }
    }
}
