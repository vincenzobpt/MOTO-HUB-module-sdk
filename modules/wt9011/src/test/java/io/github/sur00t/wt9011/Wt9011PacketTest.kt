// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 sur00t (https://github.com/sur00t), who wrote the WT9011 module.
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.github.sur00t.wt9011

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-format tests, run on the JVM: no device, no Android.
 *
 * The device is a hostile source of truth - it sends no checksum, no length and no counter - so
 * the decoder and the one-byte-at-a-time resync are exactly the code that cannot be
 * checked by reading it. The benchmark values are chosen so that a wrong byte offset, a missed sign
 * extension or a base UUID mismatch all fail here rather than on the bike.
 */
class Wt9011PacketTest {

    // ---- frame construction -------------------------------------------------------------------

    /** One 20-byte `55 61` frame, the same shape the device streams at 20 Hz. */
    private fun dataFrame(
        ax: Int = 0,
        ay: Int = 0,
        az: Int = 0,
        roll: Int = 0,
        pitch: Int = 0,
        yaw: Int = 0,
        wx: Int = 0,
        wy: Int = 0,
        wz: Int = 0
    ): ByteArray {
        val out = ByteArray(Wt9011Protocol.FRAME_SIZE)
        out[0] = Wt9011Protocol.HEADER.toByte()
        out[1] = Wt9011Protocol.FLAG_DATA.toByte()
        put(out, 2, ax)
        put(out, 4, ay)
        put(out, 6, az)
        put(out, 8, wx)
        put(out, 10, wy)
        put(out, 12, wz)
        put(out, 14, roll)
        put(out, 16, pitch)
        put(out, 18, yaw)
        return out
    }

    /** One 20-byte `55 71` register reply: base at 2-3, then eight little-endian registers. */
    private fun registerFrame(base: Int, vararg values: Int): ByteArray {
        val out = ByteArray(Wt9011Protocol.FRAME_SIZE)
        out[0] = Wt9011Protocol.HEADER.toByte()
        out[1] = Wt9011Protocol.FLAG_REGISTER.toByte()
        put(out, 2, base)
        values.forEachIndexed { index, value -> put(out, 4 + index * 2, value) }
        return out
    }

    private fun put(out: ByteArray, at: Int, value: Int) {
        out[at] = (value and 0xFF).toByte()
        out[at + 1] = ((value shr 8) and 0xFF).toByte()
    }

    /** `raw` counts to degrees, the scale the device uses for Roll/Pitch/Yaw. */
    private fun toRaw(degrees: Float): Int = Math.round(degrees / Wt9011Protocol.ANGLE_SCALE)

    /** `g` to raw acceleration counts, ±16 g over 32768. */
    private fun toRawG(g: Float): Int = Math.round(g / Wt9011Protocol.ACCEL_SCALE)

    private fun sampleOf(
        leanDeg: Float = 0f,
        pitchDeg: Float = 0f,
        yawDeg: Float = 0f,
        longG: Float = 0f,
        latG: Float = 0f,
        vertG: Float = 1f,
        seq: Int = 0,
        sensorMillis: Long = 0L
    ): Packet = Packet(
        seq = seq,
        sensorMillis = sensorMillis,
        leanDeg = leanDeg,
        pitchDeg = pitchDeg,
        yawDeg = yawDeg,
        longG = longG,
        latG = latG,
        vertG = vertG
    )

    // ---- decoding -----------------------------------------------------------------------------

    @Test
    fun decodesOneFrameWithTheDocumentedOffsets() {
        // Bike upright: gravity on Z. Bracket leaned 10 degrees and nose up 5. Yaw -30.
        val frame = dataFrame(
            ax = toRawG(0.10f),
            ay = toRawG(-0.20f),
            az = toRawG(0.97f),
            roll = toRaw(10f),
            pitch = toRaw(5f),
            yaw = toRaw(-30f)
        )

        val sample = decodeSample(frame, seq = 7, sensorMillis = 1_234L)
        assertNotNull(sample)
        val raw = sample!!.raw

        assertEquals(0.10f, raw.ax, 0.001f)
        assertEquals(-0.20f, raw.ay, 0.001f)
        assertEquals(0.97f, raw.az, 0.001f)
        assertEquals(10f, raw.rollDeg, 0.01f)
        assertEquals(5f, raw.pitchDeg, 0.01f)
        assertEquals(-30f, raw.yawDeg, 0.01f)

        val packet = sample.packet
        assertEquals(7, packet.seq)
        assertEquals(1_234L, packet.sensorMillis)
    }

    @Test
    fun defaultMountTakesLeanFromPitchAndNoseFromRoll() {
        // The documented default: long axis forward, printed +X to the bike's right. Lean is the
        // Pitch field (bytes 16-17) and nose is Roll (14-15); if these ever swap, T2/T3 catch it.
        val frame = dataFrame(roll = toRaw(20f), pitch = toRaw(-8f))
        val raw = decodeSample(frame, seq = 0, sensorMillis = 0L)!!.raw
        val packet = decodeSample(frame, seq = 0, sensorMillis = 0L)!!.packet

        assertEquals(raw.pitchDeg * Wt9011Axes.LEAN_SIGN, packet.leanDeg, 0.001f)
        assertEquals(raw.rollDeg * Wt9011Axes.PITCH_SIGN, packet.pitchDeg, 0.001f)
        assertTrue("lean and nose must not be the same channel", packet.leanDeg != packet.pitchDeg)
    }

    @Test
    fun mapsForwardAndRightGravityForTheDefaultMount() {
        val frame = dataFrame(ax = toRawG(0.5f), ay = toRawG(0.25f), az = toRawG(1f))
        val packet = decodeSample(frame, seq = 0, sensorMillis = 0L)!!.packet

        // Default mount: printed +Y (AY) is forward, printed +X (AX) is right.
        assertEquals(0.25f * Wt9011Axes.ACC_SIGN, packet.longG, 0.001f)
        assertEquals(0.5f * Wt9011Axes.ACC_SIGN, packet.latG, 0.001f)
        assertEquals(1f * Wt9011Axes.ACC_SIGN, packet.vertG, 0.001f)
    }

    @Test
    fun readsNegativeWordsAsSignedValues() {
        // A sign-extension bug shows up only on negative raw words: -1 g must not read +15.999 g.
        val frame = dataFrame(ax = toRawG(-1f), az = toRawG(-1f), roll = toRaw(-90f), yaw = toRaw(-180f))
        val raw = decodeSample(frame, seq = 0, sensorMillis = 0L)!!.raw

        assertEquals(-1f, raw.ax, 0.001f)
        assertEquals(-1f, raw.az, 0.001f)
        assertEquals(-90f, raw.rollDeg, 0.01f)
        assertEquals(-180f, raw.yawDeg, 0.01f)
    }

    @Test
    fun rejectsFramesThatAreNotDataFrames() {
        assertNull(decodeSample(ByteArray(19), 0, 0L))
        assertNull(decodeSample(ByteArray(21), 0, 0L))

        val wrongHeader = dataFrame(az = toRawG(1f))
        wrongHeader[0] = 0x54
        assertNull(decodeSample(wrongHeader, 0, 0L))

        val registerFrame = registerFrame(Wt9011Protocol.REG_BATTERY, 399)
        assertNull("a 0x71 reply is not a sample", decodeSample(registerFrame, 0, 0L))
    }

    // ---- parser and resync --------------------------------------------------------------------

    @Test
    fun parserCountsSequencesAcrossChunks() {
        val parser = Wt9011FrameParser()
        val first = parser.feed(dataFrame(pitch = toRaw(1f)), 1_000L)
        val second = parser.feed(dataFrame(pitch = toRaw(2f)), 1_050L)
        val third = parser.feed(dataFrame(pitch = toRaw(3f)), 1_100L)

        val samples = (first + second + third).map { (it as Wt9011Frame.Sample).sample }
        assertEquals(3, samples.size)
        assertEquals(listOf(0, 1, 2), samples.map { it.packet.seq })
        assertEquals(listOf(1_000L, 1_050L, 1_100L), samples.map { it.packet.sensorMillis })
    }

    @Test
    fun parserReassemblesAFrameSplitAcrossNotifications() {
        val parser = Wt9011FrameParser()
        val frame = dataFrame(pitch = toRaw(12.5f))
        val head = parser.feed(frame.copyOfRange(0, 7), 1_000L)
        val tail = parser.feed(frame.copyOfRange(7, frame.size), 1_010L)

        assertTrue(head.isEmpty())
        assertEquals(1, tail.size)
        val packet = (tail[0] as Wt9011Frame.Sample).sample.packet
        assertEquals(12.5f, packet.leanDeg, 0.01f)
    }

    @Test
    fun parserDeliversTwoFramesFromOneNotification() {
        val parser = Wt9011FrameParser()
        val chunk = dataFrame(pitch = toRaw(1f)) + dataFrame(pitch = toRaw(2f))
        val frames = parser.feed(chunk, 1_000L)

        assertEquals(2, frames.size)
        assertEquals(1f, (frames[0] as Wt9011Frame.Sample).sample.packet.leanDeg, 0.01f)
        assertEquals(2f, (frames[1] as Wt9011Frame.Sample).sample.packet.leanDeg, 0.01f)
    }

    @Test
    fun parserResynchronisesAfterGarbage() {
        val parser = Wt9011FrameParser()
        val chunk = byteArrayOf(0x00, 0x42, 0x7F) + dataFrame(pitch = toRaw(9f))
        val frames = parser.feed(chunk, 1_000L)

        assertEquals(1, frames.size)
        assertEquals(9f, (frames[0] as Wt9011Frame.Sample).sample.packet.leanDeg, 0.01f)
    }

    @Test
    fun parserDropsOnlyOneByteForADoubleHeader() {
        // A truncated frame followed by a whole one: 55 55 61 ... The vendor's own SDK drops two
        // bytes here (a `&&` where `||` was meant) and swallows the next header; this must not.
        val parser = Wt9011FrameParser()
        val chunk = byteArrayOf(Wt9011Protocol.HEADER.toByte()) + dataFrame(pitch = toRaw(45f))
        val frames = parser.feed(chunk, 1_000L)

        assertEquals(1, frames.size)
        assertEquals(45f, (frames[0] as Wt9011Frame.Sample).sample.packet.leanDeg, 0.01f)
    }

    @Test
    fun parserHandsRegisterRepliesOutAsTheyArrive() {
        val parser = Wt9011FrameParser()
        val reply = registerFrame(Wt9011Protocol.REG_BATTERY, 399, 0, 0, 0, 0, 0, 0, 0)
        val chunk = dataFrame(pitch = toRaw(3f)) + reply + dataFrame(pitch = toRaw(4f))
        val frames = parser.feed(chunk, 1_000L)

        assertEquals(3, frames.size)
        assertTrue(frames[0] is Wt9011Frame.Sample)
        val registers = (frames[1] as Wt9011Frame.Registers).reply
        assertEquals(Wt9011Protocol.REG_BATTERY, registers.base)
        assertEquals(100, registers.batteryPercent())
        assertTrue(frames[2] is Wt9011Frame.Sample)
    }

    // ---- register replies ---------------------------------------------------------------------

    @Test
    fun readsEightRegistersFromTheEchoedBase() {
        // The 0x71 reply covers eight registers starting at the base the request named.
        val reply = RegisterReply(
            base = Wt9011Protocol.REG_TEMP,
            payload = byteArrayOf(
                0x92.toByte(), 0x09, // 0x40 temperature, 24.50 C
                0x00, 0x00, // 0x41
                0x00, 0x00, // 0x42
                0x00, 0x00, // 0x43
                0x00, 0x00, // 0x44
                0x00, 0x00, // 0x45
                0x00, 0x00, // 0x46
                0x00, 0x00 // 0x47
            )
        )

        assertEquals(8, reply.count)
        assertEquals(24.50f, reply.temperatureC()!!, 0.001f)
        assertEquals("0x41 is inside the eight registers", 0, reply.short(0x41)!!)
        assertNull("0x48 is outside the eight registers of a reply based at 0x40", reply.short(0x48))
        assertNull("a reply based at 0x40 cannot answer for the battery", reply.batteryPercent())
    }

    @Test
    fun readsBatteryFromItsOwnReplyRange() {
        val reply = RegisterReply(
            base = Wt9011Protocol.REG_BATTERY,
            payload = byteArrayOf(0x8F.toByte(), 0x01, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        )
        assertEquals(399, reply.short(Wt9011Protocol.REG_BATTERY))
        assertEquals(100, reply.batteryPercent())
    }

    @Test
    fun batteryPercentFollowsTheVoltageBucket() {
        assertEquals(0, batteryPercentOf(340))
        assertEquals(0, batteryPercentOf(300))
        assertEquals(100, batteryPercentOf(399))
        assertEquals(100, batteryPercentOf(450))
        // Linear middle: 3.69 V is 49 % of the way from 3.40 to 3.99. Truncated, not rounded: the
        // device's own bucket for this voltage is "80 % or less", so a slightly low reading is
        // the honest direction.
        assertEquals(49, batteryPercentOf(369))
    }

    // ---- zero offset --------------------------------------------------------------------------

    @Test
    fun zeroAtRestKeepsAnUprightSampleUntouched() {
        val sample = sampleOf(vertG = 1f)
        val zero = Zero.of(sample, takenAt = 5_000L)
        assertNotNull(zero)

        val applied = zero!!.apply(sample)
        assertEquals(0f, applied.leanDeg, 0.01f)
        assertEquals(0f, applied.pitchDeg, 0.01f)
        assertEquals(0f, applied.longG, 0.01f)
        assertEquals(0f, applied.latG, 0.01f)
        assertEquals(1f, applied.vertG, 0.01f)
    }

    @Test
    fun zeroSubtractsTheBracketTiltAsAPlainOffset() {
        // The bike sits on a side stand: the bracket reports 4 degrees of lean at rest. Every later
        // reading is 4 degrees lighter. (A rotation would cancel the real 15 degree lean instead.)
        val parked = sampleOf(leanDeg = 4f, pitchDeg = -1f, vertG = 0.99f)
        val zero = Zero.of(parked, takenAt = 5_000L)!!
        assertEquals(4f, zero.leanDeg, 0.001f)

        val riding = sampleOf(leanDeg = 19f, pitchDeg = 2f, vertG = 0.99f)
        val applied = zero.apply(riding)
        assertEquals(15f, applied.leanDeg, 0.05f)
        assertEquals(3f, applied.pitchDeg, 0.05f)
    }

    @Test
    fun zeroRefusesSamplesThatCannotBeZeroes() {
        assertNull("a moving bike is not a zero", Zero.of(sampleOf(vertG = 0.3f), 0L))
        assertNull("a bracket that is not Z-up is not a zero", Zero.of(sampleOf(vertG = 0.2f), 0L))
        assertNotNull(Zero.of(sampleOf(vertG = 0.9f), 0L))

        assertNotNull(zeroRefusal(sampleOf(vertG = 0.2f)))
        assertNull(zeroRefusal(sampleOf(vertG = 1f)))
    }

    // ---- directional peaks ----------------------------------------------------------------

    @Test
    fun peaksTrackDirectionalMaximaAtEverySample() {
        val peaks = Peaks()
        peaks.add(sampleOf(leanDeg = -10f, latG = -0.3f, longG = 0.2f))
        peaks.add(sampleOf(leanDeg = 5f, latG = 0.4f, longG = -0.5f))
        peaks.add(sampleOf(leanDeg = 20f, latG = 0.1f, longG = 0.1f))

        assertEquals(10f, peaks.maxLeftLean, 1e-4f)
        assertEquals(20f, peaks.maxRightLean, 1e-4f)
        assertEquals(0.3f, peaks.maxLeftG, 1e-4f)
        assertEquals(0.4f, peaks.maxRightG, 1e-4f)
        assertEquals(0.2f, peaks.maxAccel, 1e-4f)
        assertEquals(0.5f, peaks.maxBrake, 1e-4f)

        // A snapshot is independent of the live tracker.
        val copy = peaks.copyOf()
        peaks.reset()
        assertEquals(10f, copy.maxLeftLean, 1e-4f)
        assertEquals(0.4f, copy.maxRightG, 1e-4f)

        assertEquals(0f, peaks.maxLeftLean, 0f)
        assertEquals(0f, peaks.maxRightLean, 0f)
        assertEquals(0f, peaks.maxLeftG, 0f)
        assertEquals(0f, peaks.maxRightG, 0f)
        assertEquals(0f, peaks.maxAccel, 0f)
        assertEquals(0f, peaks.maxBrake, 0f)
    }

    // ---- eight flat mounts --------------------------------------------------------------------

    @Test
    fun mountRotationsMapAllEightFlatOrientations() {
        val packet = sampleOf(
            leanDeg = 10f,
            pitchDeg = 5f,
            yawDeg = 30f,
            longG = 0.1f,
            latG = 0.2f,
            vertG = 0.9f
        ).copy(rollRate = 0.11f, pitchRate = 0.22f, yawRate = 0.33f)

        val expectations = listOf(
            MountExpect(MountPosition(MountYaw.FRONT, MountFace.UP), 10f, 5f, 0.1f, 0.2f, 0.11f, 0.22f, 30f, 0.33f),
            MountExpect(MountPosition(MountYaw.RIGHT, MountFace.UP), -5f, 10f, -0.2f, 0.1f, 0.22f, -0.11f, -60f, 0.33f),
            MountExpect(MountPosition(MountYaw.BACK, MountFace.UP), -10f, -5f, -0.1f, -0.2f, -0.11f, -0.22f, -150f, 0.33f),
            MountExpect(MountPosition(MountYaw.LEFT, MountFace.UP), 5f, -10f, 0.2f, -0.1f, -0.22f, 0.11f, 120f, 0.33f),
            MountExpect(MountPosition(MountYaw.FRONT, MountFace.DOWN), 10f, -5f, 0.1f, -0.2f, 0.11f, -0.22f, 150f, -0.33f),
            MountExpect(MountPosition(MountYaw.RIGHT, MountFace.DOWN), 5f, 10f, 0.2f, 0.1f, -0.22f, -0.11f, 60f, -0.33f),
            MountExpect(MountPosition(MountYaw.BACK, MountFace.DOWN), -10f, 5f, -0.1f, 0.2f, -0.11f, 0.22f, -30f, -0.33f),
            MountExpect(MountPosition(MountYaw.LEFT, MountFace.DOWN), -5f, -10f, -0.2f, -0.1f, 0.22f, 0.11f, -120f, -0.33f)
        )

        for (expected in expectations) {
            val actual = expected.position.apply(packet)
            val tag = expected.position.toString()
            assertEquals("$tag lean", expected.leanDeg, actual.leanDeg, 1e-4f)
            assertEquals("$tag nose", expected.pitchDeg, actual.pitchDeg, 1e-4f)
            assertEquals("$tag forward g", expected.longG, actual.longG, 1e-4f)
            assertEquals("$tag lateral g", expected.latG, actual.latG, 1e-4f)
            assertEquals(
                "$tag vertical g",
                if (expected.position.face == MountFace.DOWN) -0.9f else 0.9f,
                actual.vertG,
                1e-4f
            )
            assertEquals("$tag roll rate", expected.rollRate, actual.rollRate, 1e-4f)
            assertEquals("$tag pitch rate", expected.pitchRate, actual.pitchRate, 1e-4f)
            assertEquals("$tag yaw rate", expected.yawRate, actual.yawRate, 1e-4f)
            assertEquals(
                "$tag heading",
                wrap180(expected.yawDeg + Wt9011Axes.YAW_OFFSET_DEG),
                actual.yawDeg,
                1e-4f
            )
        }
    }

    @Test
    fun defaultMountIsLabelUpLongAxisForward() {
        assertEquals(MountPosition(MountYaw.FRONT, MountFace.UP), MountPosition.DEFAULT)
        assertTrue(MountPosition.DEFAULT.isDefault)
        assertTrue(!MountPosition(MountYaw.RIGHT, MountFace.UP).isDefault)
        assertTrue(!MountPosition(MountYaw.FRONT, MountFace.DOWN).isDefault)
    }

    @Test
    fun zeroCapturedAfterTheMountLevelsEveryOrientation() {
        // The zero belongs to the frame the mount defines: the screen zeros the already-mounted
        // packet, and the same mount keeps applying afterwards. A face-down mount must still end
        // with +1 g up, which is what the vertical-g negation in MountPosition is for.
        val atRest = sampleOf(leanDeg = 3f, pitchDeg = -2f, longG = 0.02f, latG = -0.01f, vertG = 1f)

        for (yaw in MountYaw.values()) {
            for (face in MountFace.values()) {
                val position = MountPosition(yaw, face)
                val mounted = position.apply(atRest)
                val zero = Zero.of(mounted, takenAt = 0L)!!
                val zeroed = zero.apply(mounted)
                assertEquals("$yaw/$face lean", 0f, zeroed.leanDeg, 0.02f)
                assertEquals("$yaw/$face nose", 0f, zeroed.pitchDeg, 0.02f)
                assertEquals("$yaw/$face forward g", 0f, zeroed.longG, 0.02f)
                assertEquals("$yaw/$face lateral g", 0f, zeroed.latG, 0.02f)
                assertEquals("$yaw/$face vertical g", 1f, zeroed.vertG, 0.02f)
            }
        }
    }

    /** One row of the eight-orientation table. */
    private data class MountExpect(
        val position: MountPosition,
        val leanDeg: Float,
        val pitchDeg: Float,
        val longG: Float,
        val latG: Float,
        val rollRate: Float,
        val pitchRate: Float,
        val yawDeg: Float,
        val yawRate: Float
    )

    // ---- four on-edge mounts ------------------------------------------------------------------

    @Test
    fun verticalMountsMapTheFourLabelDirections() {
        // The packet is what the firmware sends in vertical mode: angles, accelerometer and gyro
        // are all in the same virtual flat frame (the firmware has already put +Y on its +Z).
        val packet = sampleOf(
            leanDeg = 10f,
            pitchDeg = 5f,
            yawDeg = 30f,
            longG = 0.1f,
            latG = 0.2f,
            vertG = 0.9f
        ).copy(rollRate = 0.11f, pitchRate = 0.22f, yawRate = 0.33f)

        val expectations = listOf(
            VerticalExpect(MountYaw.FRONT, -10f, -5f, -0.1f, -0.2f, -0.11f, -0.22f, 0.33f, -150f),
            VerticalExpect(MountYaw.RIGHT, 5f, -10f, 0.2f, -0.1f, -0.22f, 0.11f, 0.33f, 120f),
            VerticalExpect(MountYaw.BACK, 10f, 5f, 0.1f, 0.2f, 0.11f, 0.22f, 0.33f, 30f),
            VerticalExpect(MountYaw.LEFT, -5f, 10f, -0.2f, 0.1f, 0.22f, -0.11f, 0.33f, -60f)
        )

        for (expected in expectations) {
            val position = MountPosition(expected.yaw, MountFace.UP, MountPlane.VERTICAL)
            val actual = position.apply(packet)
            val tag = "vertical ${expected.yaw}"
            assertEquals("$tag lean", expected.leanDeg, actual.leanDeg, 1e-4f)
            assertEquals("$tag nose", expected.pitchDeg, actual.pitchDeg, 1e-4f)
            assertEquals("$tag forward g", expected.longG, actual.longG, 1e-4f)
            assertEquals("$tag lateral g", expected.latG, actual.latG, 1e-4f)
            assertEquals("$tag vertical g", 0.9f, actual.vertG, 1e-4f)
            assertEquals("$tag roll rate", expected.rollRate, actual.rollRate, 1e-4f)
            assertEquals("$tag pitch rate", expected.pitchRate, actual.pitchRate, 1e-4f)
            assertEquals("$tag yaw rate", expected.yawRate, actual.yawRate, 1e-4f)
            assertEquals("$tag heading", expected.yawDeg, actual.yawDeg, 1e-4f)
        }
    }

    @Test
    fun zeroCapturedAfterAMountLevelsEveryOnEdgeOrientation() {
        // In vertical mode the firmware has already put the sensor's +Y on its virtual +Z, so an
        // upright bike arrives as vertG = 1, not as longG = 1. Every label direction must preserve
        // that vertical axis; the old physical assumption is what made ZERO refuse with
        // "vertical axis reads -0.01 g".
        val atRest = sampleOf(leanDeg = 0f, pitchDeg = 0f, longG = 0f, latG = 0f, vertG = 1f)

        for (yaw in MountYaw.values()) {
            val position = MountPosition(yaw, MountFace.UP, MountPlane.VERTICAL)
            val mounted = position.apply(atRest)
            assertEquals("$yaw vertical g", 1f, mounted.vertG, 0.001f)
            assertEquals("$yaw lean", 0f, mounted.leanDeg, 0.001f)
            assertEquals("$yaw nose", 0f, mounted.pitchDeg, 0.001f)
            assertNull("$yaw refusal", zeroRefusal(mounted))
            val zero = Zero.of(mounted, takenAt = 0L)!!
            val zeroed = zero.apply(mounted)
            assertEquals("$yaw zeroed lean", 0f, zeroed.leanDeg, 0.01f)
            assertEquals("$yaw zeroed nose", 0f, zeroed.pitchDeg, 0.01f)
            assertEquals("$yaw zeroed vertical g", 1f, zeroed.vertG, 0.01f)
        }
    }

    @Test
    fun onEdgeMountsHaveNoLabelUpOrDownChoice() {
        assertTrue(
            "an on-edge mount cannot also be label-down",
            runCatching {
                MountPosition(MountYaw.FRONT, MountFace.DOWN, MountPlane.VERTICAL)
            }.isFailure
        )
    }

    /** One row of the four on-edge directions. */
    private data class VerticalExpect(
        val yaw: MountYaw,
        val leanDeg: Float,
        val pitchDeg: Float,
        val longG: Float,
        val latG: Float,
        val rollRate: Float,
        val pitchRate: Float,
        val yawRate: Float,
        val yawDeg: Float
    )

    // ---- angles -------------------------------------------------------------------------------

    @Test
    fun wrapsAnglesIntoTheSignedRange() {
        assertEquals(0f, wrap180(0f), 0.001f)
        assertEquals(90f, wrap180(90f), 0.001f)
        assertEquals(-90f, wrap180(-90f), 0.001f)
        // The range is [-180, 180): half a turn the other way is the same heading, spelled -180.
        assertEquals(-180f, wrap180(180f), 0.001f)
        assertEquals(-170f, wrap180(190f), 0.001f)
        assertEquals(170f, wrap180(-190f), 0.001f)
        assertEquals(0f, wrap180(360f), 0.001f)
        assertEquals(0f, wrap180(Float.NaN), 0.001f)
    }

    @Test
    fun bearingIsTheCompassReadingOfTheYaw() {
        assertEquals(0f, bearingDeg(0f), 0.001f)
        assertEquals(90f, bearingDeg(90f), 0.001f)
        assertEquals(270f, bearingDeg(-90f), 0.001f)
        assertEquals(180f, bearingDeg(180f), 0.001f)
        assertEquals(0f, bearingDeg(360f), 0.001f)
        assertEquals(190f, bearingDeg(-170f), 0.001f)
    }

    // ---- configuration commands ---------------------------------------------------------------

    @Test
    fun setupBlockUnlocksConfiguresAndSavesInThatOrder() {
        val setup = Wt9011Protocol.setup()
        assertEquals(7, setup.size)
        assertEquals(Wt9011Protocol.unlock().toList(), setup[0].toList())
        assertEquals(Wt9011Protocol.outputAccelGyroAngle().toList(), setup[1].toList())
        assertEquals(Wt9011Protocol.rate100Hz().toList(), setup[2].toList())
        assertEquals(Wt9011Protocol.bandwidth256Hz().toList(), setup[3].toList())
        assertEquals(Wt9011Protocol.algorithm9Axis().toList(), setup[4].toList())
        assertEquals(Wt9011Protocol.installHorizontal().toList(), setup[5].toList())
        assertEquals(Wt9011Protocol.save().toList(), setup[6].toList())
        assertTrue("the save must come last", setup.all { it.size <= 20 })

        // An on-edge mount writes the vertical installation direction instead.
        val onEdge = Wt9011Protocol.setup(vertical = true)
        assertEquals(7, onEdge.size)
        assertEquals(Wt9011Protocol.installVertical().toList(), onEdge[5].toList())
        assertEquals(Wt9011Protocol.save().toList(), onEdge[6].toList())
    }

    @Test
    fun commandsAreTheDocumentedBytes() {
        assertEquals(listOf(0xFF, 0xAA, 0x69, 0x88, 0xB5), bytes(Wt9011Protocol.unlock()))
        assertEquals(listOf(0xFF, 0xAA, 0x96, 0x00, 0x00), bytes(Wt9011Protocol.outputAccelGyroAngle()))
        assertEquals(listOf(0xFF, 0xAA, 0x03, 0x07, 0x00), bytes(Wt9011Protocol.rate20Hz()))
        assertEquals(listOf(0xFF, 0xAA, 0x03, 0x09, 0x00), bytes(Wt9011Protocol.rate100Hz()))
        assertEquals(listOf(0xFF, 0xAA, 0x03, 0x06, 0x00), bytes(Wt9011Protocol.rate10Hz()))
        assertEquals(listOf(0xFF, 0xAA, 0x1F, 0x03, 0x00), bytes(Wt9011Protocol.bandwidth42Hz()))
        assertEquals(listOf(0xFF, 0xAA, 0x1F, 0x01, 0x00), bytes(Wt9011Protocol.bandwidth188Hz()))
        assertEquals(listOf(0xFF, 0xAA, 0x1F, 0x00, 0x00), bytes(Wt9011Protocol.bandwidth256Hz()))
        assertEquals(listOf(0xFF, 0xAA, 0x24, 0x00, 0x00), bytes(Wt9011Protocol.algorithm9Axis()))
        assertEquals(listOf(0xFF, 0xAA, 0x23, 0x00, 0x00), bytes(Wt9011Protocol.installHorizontal()))
        assertEquals(listOf(0xFF, 0xAA, 0x23, 0x01, 0x00), bytes(Wt9011Protocol.installVertical()))
        assertEquals(listOf(0xFF, 0xAA, 0x00, 0x00, 0x00), bytes(Wt9011Protocol.save()))
        // A register read names the register low byte first, and 0x64 is one byte.
        assertEquals(listOf(0xFF, 0xAA, 0x27, 0x64, 0x00), bytes(Wt9011Protocol.readRegister(0x64)))
    }

    private fun bytes(value: ByteArray): List<Int> = value.map { it.toInt() and 0xFF }

    // ---- UUID tolerance -----------------------------------------------------------------------

    @Test
    fun acceptsBothWitMotionBases() {
        // WitMotion's own SDK ships …00805f9a34fb; some units advertise the standard …00805f9b34fb.
        val sdkBase = UUID.fromString("0000ffe5-0000-1000-8000-00805f9a34fb")
        val standardBase = UUID.fromString("0000ffe5-0000-1000-8000-00805f9b34fb")
        assertTrue(Wt9011Protocol.matchesShort(sdkBase, Wt9011Protocol.SERVICE))
        assertTrue(Wt9011Protocol.matchesShort(standardBase, Wt9011Protocol.SERVICE))

        assertEquals(
            "a different short id must not match",
            false,
            Wt9011Protocol.matchesShort(
                UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb"),
                Wt9011Protocol.SERVICE
            )
        )
    }

    @Test
    fun keepsTheFallbackServiceExact() {
        // 49535343-… shares its first four bytes between the data and command characteristics, so
        // short matching must not be applied to it.
        assertTrue(
            Wt9011Protocol.matchesShort(
                UUID.fromString("49535343-fe7d-4ae5-8fa9-9fafd205e455"),
                Wt9011Protocol.FALLBACK_SERVICE
            )
        )
        assertTrue(
            Wt9011Protocol.matchesShort(
                UUID.fromString("49535343-1e4d-4bd9-ba61-23c647249616"),
                Wt9011Protocol.FALLBACK_DATA
            )
        )
        assertEquals(
            false,
            Wt9011Protocol.matchesShort(
                UUID.fromString("49535343-8841-43f4-a8d4-ecbe34729bb3"),
                Wt9011Protocol.FALLBACK_DATA
            )
        )
    }

    // ---- angular rate -------------------------------------------------------------------------

    /** Degrees per second to raw gyro counts, ±2000 °/s over 32768. */
    private fun toRawDps(dps: Float): Int = Math.round(dps / Wt9011Protocol.GYRO_SCALE)

    @Test
    fun decodesTheGyroIntoRadiansAboutForwardLeftAndUp() {
        val frame = dataFrame(az = toRawG(1f), wx = toRawDps(10f), wy = toRawDps(-20f), wz = toRawDps(30f))
        val sample = decodeSample(frame, seq = 0, sensorMillis = 0L)!!
        assertEquals(10f, sample.raw.wx, 0.1f)
        assertEquals(-20f, sample.raw.wy, 0.1f)
        assertEquals(30f, sample.raw.wz, 0.1f)

        val toRad = (Math.PI / 180.0).toFloat()
        // Default mount: forward is printed +Y (WY). The left axis is -printed +X, so the
        // left-axis rate is -WX; pitchRate is positive nose-down, which is exactly that.
        assertEquals(sample.raw.wy * toRad * Wt9011Axes.GYRO_SIGN, sample.packet.rollRate, 1e-4f)
        assertEquals(-sample.raw.wx * toRad * Wt9011Axes.GYRO_SIGN, sample.packet.pitchRate, 1e-4f)
        assertEquals(sample.raw.wz * toRad * Wt9011Axes.GYRO_SIGN, sample.packet.yawRate, 1e-4f)
    }

    @Test
    fun theZeroLevelsTheRatesWithTheSameRotationAsTheGForces() {
        // Bracket tilted: at rest it read a little forward and sideways g. A pure turn about the
        // tilted up axis must come out as a pure turn about true up.
        val rest = sampleOf(longG = 0.1f, latG = 0.05f, vertG = 0.994f)
        val zero = Zero.of(rest, takenAt = 0L)!!
        val norm = kotlin.math.sqrt(0.1f * 0.1f + 0.05f * 0.05f + 0.994f * 0.994f)
        // Up in the sensor's frame (x forward, y left, z up); latG is right-positive.
        val upX = 0.1f / norm
        val upY = -0.05f / norm
        val upZ = 0.994f / norm
        val turning = rest.copy(rollRate = 0.5f * upX, pitchRate = 0.5f * upY, yawRate = 0.5f * upZ)
        val levelled = zero.apply(turning)
        assertEquals(0f, levelled.rollRate, 1e-4f)
        assertEquals(0f, levelled.pitchRate, 1e-4f)
        assertEquals(0.5f, levelled.yawRate, 1e-4f)
        assertEquals(1f, levelled.vertG, 1e-3f)
    }
}
