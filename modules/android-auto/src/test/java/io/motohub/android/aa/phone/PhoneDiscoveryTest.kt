// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.aa.phone

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneDiscoveryTest {

    // Field numbers as documented on PhoneDiscovery.

    private fun videoConfig(resolution: Int, frameRate: Int = 0, marginW: Int = 0, marginH: Int = 0, density: Int = 0) =
        ProtoWriter()
            .varint(1, resolution)
            .varint(2, frameRate)
            .varint(3, marginW)
            .varint(4, marginH)
            .varint(5, density)

    private fun videoService(channel: Int, display: Int?, vararg configs: ProtoWriter): ProtoWriter {
        val sink = ProtoWriter().varint(1, PhoneDiscovery.CODEC_VIDEO_H264_BP)
        for (c in configs) sink.message(4, c)
        if (display != null) sink.varint(6, display)
        return ProtoWriter().varint(1, channel).message(3, sink)
    }

    private fun audioService(channel: Int): ProtoWriter =
        ProtoWriter().varint(1, channel).message(3, ProtoWriter().varint(1, 1).varint(2, 1))

    private fun touch(width: Int, height: Int) = ProtoWriter().varint(1, width).varint(2, height)

    private fun inputService(
        channel: Int,
        display: Int,
        touch: ProtoWriter? = null,
        keycodes: IntArray = IntArray(0),
        packed: Boolean = true
    ): ProtoWriter {
        val inp = ProtoWriter()
        if (packed) {
            if (keycodes.isNotEmpty()) inp.packedVarints(1, keycodes)
        } else {
            for (k in keycodes) inp.varint(1, k)
        }
        if (touch != null) inp.message(2, touch)
        inp.varint(5, display)
        return ProtoWriter().varint(1, channel).message(4, inp)
    }

    private fun sensorService(channel: Int, vararg types: Int): ProtoWriter {
        val source = ProtoWriter()
        for (t in types) source.message(1, ProtoWriter().varint(1, t))
        return ProtoWriter().varint(1, channel).message(2, source)
    }

    private fun response(vararg services: ProtoWriter): ProtoWriter {
        val r = ProtoWriter()
        for (s in services) r.message(1, s)
        return r
    }

    private fun discover(r: ProtoWriter) = PhoneDiscovery.parse(r.toByteArray())

    @Test
    fun anEmptyResponseFindsNothing() {
        val d = PhoneDiscovery.parse(ByteArray(0))
        assertEquals("", d.make)
        assertEquals("", d.model)
        assertEquals("", d.headUnitMake)
        assertEquals("", d.headUnitModel)
        assertNull(d.video)
        assertNull(d.input)
        assertNull(d.sensors)
    }

    @Test
    fun theUnitsStringsComeFromFieldsTwoThreeSevenAndEight() {
        val d = discover(
            response()
                .bytes(2, "Make".toByteArray())
                .bytes(3, "Model".toByteArray())
                .bytes(4, "not this".toByteArray())
                .bytes(5, "nor this".toByteArray())
                .bytes(7, "HU make".toByteArray())
                .bytes(8, "HU model".toByteArray())
        )
        assertEquals("Make", d.make)
        assertEquals("Model", d.model)
        assertEquals("HU make", d.headUnitMake)
        assertEquals("HU model", d.headUnitModel)
    }

    @Test
    fun missingStringsAreEmptyNotNull() {
        val d = discover(response().bytes(2, "Make".toByteArray()))
        assertEquals("Make", d.make)
        assertEquals("", d.model)
        assertEquals("", d.headUnitMake)
        assertEquals("", d.headUnitModel)
    }

    @Test
    fun anAudioSinkListedBeforeTheVideoSinkIsNotTakenForVideo() {
        val d = discover(
            response(
                audioService(channel = 1),
                videoService(channel = 2, display = 0, videoConfig(resolution = 2, frameRate = 1))
            )
        )
        val video = d.video!!
        assertEquals(2, video.channel)
        assertEquals(PhoneDiscovery.CODEC_VIDEO_H264_BP, video.codec)
        assertEquals(1, video.configs.size)
    }

    @Test
    fun withTwoVideoSinksTheOneOnDisplayZeroIsChosen() {
        val d = discover(
            response(
                videoService(channel = 3, display = 1, videoConfig(resolution = 1)),
                videoService(channel = 4, display = 0, videoConfig(resolution = 3))
            )
        )
        val video = d.video!!
        assertEquals(4, video.channel)
        assertEquals(0, video.displayId)
        assertEquals(3, video.configs[0].resolution)
    }

    @Test
    fun aVideoSinkThatNamesNoDisplayCountsAsDisplayZero() {
        val d = discover(
            response(
                videoService(channel = 3, display = 1, videoConfig(resolution = 1)),
                videoService(channel = 4, display = null, videoConfig(resolution = 2))
            )
        )
        assertEquals(4, d.video!!.channel)
    }

    @Test
    fun withoutADisplayZeroSinkTheFirstVideoSinkIsUsed() {
        val d = discover(
            response(
                videoService(channel = 3, display = 2, videoConfig(resolution = 1)),
                videoService(channel = 4, display = 1, videoConfig(resolution = 2))
            )
        )
        assertEquals(3, d.video!!.channel)
    }

    @Test
    fun aResponseWithNoVideoSinkHasNoVideo() {
        val d = discover(
            response(
                audioService(channel = 1),
                inputService(channel = 5, display = 0, touch = touch(1280, 720)),
                sensorService(channel = 6, 1)
            )
        )
        assertNull(d.video)
        assertNotNull(d.input)
        assertNotNull(d.sensors)
    }

    @Test
    fun videoConfigsKeepTheirIndexAndFields() {
        val d = discover(
            response(
                videoService(
                    channel = 2, display = 0,
                    videoConfig(resolution = 1, frameRate = 0, marginW = 0, marginH = 0, density = 160),
                    videoConfig(resolution = 3, frameRate = 1, marginW = 12, marginH = 34, density = 240)
                )
            )
        )
        val configs = d.video!!.configs
        assertEquals(listOf(0, 1), configs.map { it.index })
        assertEquals(listOf(1, 3), configs.map { it.resolution })
        with(configs[1]) {
            assertEquals(1, frameRate)
            assertEquals(12, marginWidth)
            assertEquals(34, marginHeight)
            assertEquals(240, density)
        }
        assertEquals(160, configs[0].density)
    }

    @Test
    fun resolutionTwoIs1280By720() {
        val c = PhoneDiscovery.VideoConfig(0, resolution = 2, frameRate = 0, marginWidth = 0, marginHeight = 0, density = 0)
        assertEquals(1280 to 720, c.size)
    }

    @Test
    fun portraitResolutionsAreTallerThanWide() {
        val c = PhoneDiscovery.VideoConfig(0, resolution = 6, frameRate = 0, marginWidth = 0, marginHeight = 0, density = 0)
        assertEquals(720 to 1280, c.size)
    }

    @Test
    fun anUnknownResolutionHasNoSize() {
        for (resolution in listOf(0, 10, 99, -1)) {
            val c = PhoneDiscovery.VideoConfig(0, resolution, 0, 0, 0, 0)
            assertNull(c.size)
        }
    }

    @Test
    fun frameRateOneMeansSixtyAndAnythingElseMeansThirty() {
        fun fps(frameRate: Int) = PhoneDiscovery.VideoConfig(0, 2, frameRate, 0, 0, 0).fps
        assertEquals(60, fps(1))
        assertEquals(30, fps(0))
        assertEquals(30, fps(2))
        assertEquals(30, fps(-1))
    }

    @Test
    fun videoConfigDescribesItselfForTheLog() {
        val known = PhoneDiscovery.VideoConfig(0, 2, 1, 4, 6, 160)
        assertEquals("1280x720@60fps density=160 margin=4x6", known.toString())

        val unknown = PhoneDiscovery.VideoConfig(0, 99, 0, 0, 0, 0)
        assertEquals("resolution#99@30fps density=0 margin=0x0", unknown.toString())
    }

    @Test
    fun theTouchscreenOfTheMainDisplayBeatsAnEmptyInputListedFirst() {
        // LIVI lists an input service with no touchscreen for its second screen ahead of the main one.
        val d = discover(
            response(
                videoService(channel = 2, display = 0, videoConfig(resolution = 2)),
                inputService(channel = 7, display = 1),
                inputService(channel = 8, display = 0, touch = touch(1280, 720))
            )
        )
        val input = d.input!!
        assertEquals(8, input.channel)
        assertEquals(1280, input.touchWidth)
        assertEquals(720, input.touchHeight)
        assertEquals(0, input.displayId)
    }

    @Test
    fun theInputOfTheChosenVideoDisplayIsPreferredOverAnotherTouchscreen() {
        val d = discover(
            response(
                videoService(channel = 2, display = 1, videoConfig(resolution = 2)),
                inputService(channel = 7, display = 0, touch = touch(800, 480)),
                inputService(channel = 8, display = 1, touch = touch(1280, 720))
            )
        )
        assertEquals(8, d.input!!.channel)
    }

    @Test
    fun withoutATouchscreenOnTheVideoDisplayAnyTouchscreenIsUsed() {
        val d = discover(
            response(
                videoService(channel = 2, display = 0, videoConfig(resolution = 2)),
                inputService(channel = 7, display = 0),
                inputService(channel = 8, display = 1, touch = touch(800, 480))
            )
        )
        assertEquals(8, d.input!!.channel)
    }

    @Test
    fun withoutAnyTouchscreenTheFirstInputServiceIsUsed() {
        val d = discover(
            response(
                inputService(channel = 7, display = 1, keycodes = intArrayOf(3)),
                inputService(channel = 8, display = 0, keycodes = intArrayOf(4))
            )
        )
        val input = d.input!!
        assertEquals(7, input.channel)
        assertEquals(0, input.touchWidth)
        assertEquals(0, input.touchHeight)
    }

    @Test
    fun withoutVideoTheTouchscreenOnDisplayZeroIsPreferred() {
        val d = discover(
            response(
                inputService(channel = 7, display = 1, touch = touch(800, 480)),
                inputService(channel = 8, display = 0, touch = touch(1280, 720))
            )
        )
        assertEquals(8, d.input!!.channel)
    }

    @Test
    fun keycodesAreReadWhetherPackedOrOnePerTag() {
        val codes = intArrayOf(3, 4, 19, 20, 21, 22, 23, 84, 85, 87, 88, 126, 127, 65_536)
        for (packed in listOf(true, false)) {
            val d = discover(
                response(inputService(channel = 8, display = 0, touch = touch(1280, 720), keycodes = codes, packed = packed))
            )
            assertArrayEquals("packed=$packed", codes, d.input!!.keycodes)
        }
    }

    @Test
    fun anInputServiceWithoutKeycodesHasAnEmptyList() {
        val d = discover(response(inputService(channel = 8, display = 0, touch = touch(1280, 720))))
        assertEquals(0, d.input!!.keycodes.size)
    }

    @Test
    fun sensorTypesAreReadInOrderFromTheSensorService() {
        val d = discover(
            response(
                videoService(channel = 2, display = 0, videoConfig(resolution = 2)),
                sensorService(channel = 6, 1, 10, 13, 8)
            )
        )
        val sensors = d.sensors!!
        assertEquals(6, sensors.channel)
        assertArrayEquals(intArrayOf(1, 10, 13, 8), sensors.types)
    }

    @Test
    fun aSensorServiceWithNoSensorsIsSkippedForTheNextOne() {
        val d = discover(response(sensorService(channel = 5), sensorService(channel = 6, 2)))
        val sensors = d.sensors!!
        assertEquals(6, sensors.channel)
        assertArrayEquals(intArrayOf(2), sensors.types)
    }

    @Test
    fun withNoSensorServiceThereAreNoSensors() {
        val d = discover(response(videoService(channel = 2, display = 0, videoConfig(resolution = 2))))
        assertNull(d.sensors)
    }

    @Test
    fun aFullResponseIsReadAsOneUnit() {
        val d = discover(
            response(
                audioService(channel = 1),
                videoService(channel = 2, display = 0, videoConfig(resolution = 2, frameRate = 1, density = 160)),
                inputService(channel = 3, display = 0, touch = touch(1280, 720), keycodes = intArrayOf(4)),
                sensorService(channel = 4, 1)
            )
                .bytes(2, "Make".toByteArray())
                .bytes(3, "Model".toByteArray())
        )
        assertEquals("Make", d.make)
        assertEquals(2, d.video!!.channel)
        assertEquals("1280x720@60fps density=160 margin=0x0", d.video!!.configs[0].toString())
        assertEquals(3, d.input!!.channel)
        assertEquals(4, d.sensors!!.channel)
    }
}
