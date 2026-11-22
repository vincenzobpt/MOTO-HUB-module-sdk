// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 sur00t (https://github.com/sur00t), who wrote the WT9011 module.
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.github.sur00t.wt9011

import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Everything about the WitMotion WT9011DCL that is pure data: UUIDs, the `FF AA` command bytes,
 * the scale factors, and the empirical sign/axis constants.
 *
 * No Android types in here on purpose, so the whole decoding path can be unit tested on a plain
 * JVM. The BLE plumbing lives in `Wt9011Link.kt`; the frame layout is described in `decodeSample`.
 *
 * Sources: WitMotion's WT9011DCL documentation and SDKs, checked against a real unit.
 */
object Wt9011Protocol {
    /** Advertised name prefix. The track has other WitMotion devices; the service is checked later. */
    const val NAME_PREFIX = "WT"

    /** Documented service: `ffe5`, data notifications on `ffe4`, commands on `ffe9`. */
    val SERVICE: UUID = UUID.fromString("0000ffe5-0000-1000-8000-00805f9a34fb")
    val DATA: UUID = UUID.fromString("0000ffe4-0000-1000-8000-00805f9a34fb")
    val COMMAND: UUID = UUID.fromString("0000ffe9-0000-1000-8000-00805f9a34fb")

    /**
     * Second chance, for a unit that advertises the same data under a different service. Used only
     * when `ffe5` is missing on the discovered device - never for scanning, where a UUID filter
     * would hide a unit whose advertisement is minimal.
     */
    val FALLBACK_SERVICE: UUID = UUID.fromString("49535343-fe7d-4ae5-8fa9-9fafd205e455")
    val FALLBACK_DATA: UUID = UUID.fromString("49535343-1e4d-4bd9-ba61-23c647249616")
    val FALLBACK_COMMAND: UUID = UUID.fromString("49535343-8841-43f4-a8d4-ecbe34729bb3")

    /** The notification descriptor, the same on every Android BLE peripheral. */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /**
     * Matches a discovered UUID against one of the short WitMotion ones.
     *
     * WitMotion's own SDK declares the ffe5/ffe4/ffe9 trio on the base `…00805f9a34fb`, while some
     * of their units advertise the standard `…00805f9b34fb` instead. Both turn up in the field and
     * only the base differs, so a 16-bit UUID is compared by its short form alone.
     *
     * Only 16-bit UUIDs are compared this way. The `49535343-…` fallback pair intentionally shares
     * its first four bytes between the data and command characteristics, so those stay exact: a
     * short-form match there would hand the link the command characteristic as its data source.
     */
    fun matchesShort(discovered: UUID, wanted: UUID): Boolean {
        if (discovered == wanted) return true
        val short = wanted.toString().lowercase(Locale.ROOT).take(8)
        if (!short.startsWith("0000")) return false
        return discovered.toString().lowercase(Locale.ROOT).take(8) == short
    }

    /** `0x55 0x61` data frame and `0x55 0x71` register reply are both 20 bytes, little-endian. */
    const val FRAME_SIZE = 20
    const val HEADER: Int = 0x55
    const val FLAG_DATA: Int = 0x61
    const val FLAG_REGISTER: Int = 0x71

    /** A `0x71` reply carries 16 payload bytes: 8 registers starting at the echoed base. */
    const val REGISTER_PAYLOAD = 16

    /** Register numbers this module reads. */
    const val REG_ROLL = 0x3D
    const val REG_PITCH = 0x3E
    const val REG_YAW = 0x3F
    const val REG_HX = 0x3A
    const val REG_TEMP = 0x40
    const val REG_AGPVSEL = 0x96
    const val REG_BATTERY = 0x64
    const val REG_VERSION1 = 0x2E
    const val REG_VERSION2 = 0x2F

    /** Accel full scale is ±16 g, gyro ±2000 °/s, angles ±180°: `raw / 32768 * fullScale`. */
    const val ACCEL_SCALE: Float = 16f / 32768f
    const val GYRO_SCALE: Float = 2000f / 32768f
    const val ANGLE_SCALE: Float = 180f / 32768f

    /**
     * The one-time configuration block, sent in this order, write-with-response, inside ten
     * seconds of the unlock. See doc §5.4: this is flash-backed on the device, so it runs once per
     * install, not on every connection.
     */
    /**
     * The settings block, in the order the device expects.
     *
     * [vertical] selects the installation direction. The sensor's own vertical mode requires the
     * printed +Y arrow to point up, so it is written only for an on-edge mount; every flat mount
     * keeps the default horizontal mode.
     */
    fun setup(vertical: Boolean = false): List<ByteArray> = listOf(
        unlock(),
        outputAccelGyroAngle(),
        rate100Hz(),
        bandwidth256Hz(),
        algorithm9Axis(),
        if (vertical) installVertical() else installHorizontal(),
        save()
    )

    /** `FF AA 69 88 B5` - mandatory before any settings write, valid for ten seconds. */
    fun unlock(): ByteArray = cmd(0xFF, 0xAA, 0x69, 0x88, 0xB5)

    /** `FF AA 96 00 00` - output content = acceleration + angular velocity + angle. */
    fun outputAccelGyroAngle(): ByteArray = cmd(0xFF, 0xAA, 0x96, 0x00, 0x00)

    /** `FF AA 03 07 00` - 20 Hz output rate. */
    fun rate20Hz(): ByteArray = cmd(0xFF, 0xAA, 0x03, 0x07, 0x00)

    /** `FF AA 03 09 00` - 100 Hz output rate, used by the setup block. */
    fun rate100Hz(): ByteArray = cmd(0xFF, 0xAA, 0x03, 0x09, 0x00)

    /** `FF AA 03 06 00` - 10 Hz output rate, the device's own default. */
    fun rate10Hz(): ByteArray = cmd(0xFF, 0xAA, 0x03, 0x06, 0x00)

    /** `FF AA 1F 03 00` - 42 Hz bandwidth, at least twice the output rate. */
    fun bandwidth42Hz(): ByteArray = cmd(0xFF, 0xAA, 0x1F, 0x03, 0x00)

    /** `FF AA 1F 01 00` - 188 Hz bandwidth (reference; below 2× the 100 Hz output rate). */
    fun bandwidth188Hz(): ByteArray = cmd(0xFF, 0xAA, 0x1F, 0x01, 0x00)

    /** `FF AA 1F 00 00` - 256 Hz bandwidth, at least twice the 100 Hz output rate. */
    fun bandwidth256Hz(): ByteArray = cmd(0xFF, 0xAA, 0x1F, 0x00, 0x00)

    /** `FF AA 24 00 00` - 9-axis fusion, the only mode that gives an absolute heading. */
    fun algorithm9Axis(): ByteArray = cmd(0xFF, 0xAA, 0x24, 0x00, 0x00)

    /** `FF AA 23 00 00` - horizontal install (Z up), the flat bracket. */
    fun installHorizontal(): ByteArray = cmd(0xFF, 0xAA, 0x23, 0x00, 0x00)

    /**
     * `FF AA 23 01 00` - vertical install. WitMotion requires the printed **+Y** arrow to point
     * up; the board then stands on its edge with the label face horizontal.
     */
    fun installVertical(): ByteArray = cmd(0xFF, 0xAA, 0x23, 0x01, 0x00)

    /** `FF AA 00 00 00` - save to flash. */
    fun save(): ByteArray = cmd(0xFF, 0xAA, 0x00, 0x00, 0x00)

    /**
     * `FF AA 27 <REG> 00` - read one register. The device answers with a `0x71` frame covering
     * eight registers starting at this one. Register number goes in low byte first.
     */
    fun readRegister(register: Int): ByteArray =
        cmd(0xFF, 0xAA, 0x27, register and 0xFF, (register shr 8) and 0xFF)

    private fun cmd(vararg bytes: Int): ByteArray = ByteArray(bytes.size) { bytes[it].toByte() }
}

/**
 * The empirical signs for the default mount.
 *
 * The default mount is the one in the docs: the sensor lies flat, label up, its long axis pointing
 * forward, printed +X to the bike's right and printed +Y to the bike's front. That makes lean the
 * Pitch field (bytes 16-17) and nose the Roll field (bytes 14-15); forward g is AY and rightward g
 * is AX. [MountPosition] rotates this default frame to the rider's actual bracket.
 *
 * ⚠ Every sign here is EMPIRICAL. The vendor's axis diagram and its two apps disagree about which
 * way is forward, so the bench tests T1-T6 settle the signs; the mount picker settles orientation.
 */
object Wt9011Axes {
    /** Degrees, + = lean right. Flip if T2 shows a right lean reading negative. */
    const val LEAN_SIGN: Float = 1f

    /** Degrees, + = nose up. Flip if T3 shows a nose-up attitude reading negative. */
    const val PITCH_SIGN: Float = 1f

    /** Flip if T4 shows a right turn reading the bearing down. */
    const val YAW_SIGN: Float = 1f

    /**
     * Degrees added to the heading, for a fixed sensor heading bias. T4 measures it: point the
     * sensor's printed +Y at magnetic north and set the number that makes the screen read 0.
     * A bracket whose long axis points elsewhere is a [MountPosition] choice, not this constant.
     */
    const val YAW_OFFSET_DEG: Float = 0f

    /** Flip if T5 shows the vertical axis reading -1 g with the bike upright. */
    const val ACC_SIGN: Float = 1f

    /** Flip if T6 shows a left turn on the bench reading a negative yaw rate. */
    const val GYRO_SIGN: Float = 1f
}

/** One frame's raw channels, straight off the wire, with none of the sign constants applied. */
class Wt9011Raw(
    val ax: Float,
    val ay: Float,
    val az: Float,
    /** Degrees per second, as the sensor sends them. */
    val wx: Float = 0f,
    val wy: Float = 0f,
    val wz: Float = 0f,
    val rollDeg: Float,
    val pitchDeg: Float,
    val yawDeg: Float
)

/** A decoded data frame: the raw channels (for the bench tests) and the ride model. */
class Wt9011Sample(val raw: Wt9011Raw, val packet: Packet)

/** A `0x71` register reply: eight little-endian registers starting at [base]. */
class RegisterReply(val base: Int, private val payload: ByteArray) {
    init {
        require(payload.size % 2 == 0) { "register payload must be whole 16-bit words" }
    }

    /** How many registers this reply actually carries. The app-side reference decodes four. */
    val count: Int get() = payload.size / 2

    /** The value of [register], or null when this reply does not cover it. */
    fun short(register: Int): Int? {
        val offset = register - base
        if (offset < 0 || offset >= count) return null
        return readInt16(payload, offset * 2)
    }

    /** Degrees Celsius, or null when the reply is for another register range. */
    fun temperatureC(): Float? = short(Wt9011Protocol.REG_TEMP)?.let { it / 100f }

    /** Percent from the device's supply-voltage bucket, or null. See [batteryPercentOf]. */
    fun batteryPercent(): Int? = short(Wt9011Protocol.REG_BATTERY)?.let { batteryPercentOf(it) }
}

/**
 * Supply voltage, in hundredths of a volt, to a percentage the rider can read.
 *
 * The device reports a raw ADC value, and the vendor documentation gives buckets rather than a
 * formula (>413 → 100 %, 393-397 → 80 %, <342 → 0 %), while the vendor's Android app interpolates
 * 3.40 V → 0 % and 3.99 V → 100 %. Interpolation is used here because a Fact reading "78 %" is
 * more use on a bench than a bucket boundary, and the linear middle agrees with the documented
 * buckets to within a few percent. It is the sensor's own supply, not the bike's battery.
 */
fun batteryPercentOf(raw: Int): Int {
    val volts = raw / 100f
    val percent = (volts - 3.40f) / (3.99f - 3.40f) * 100f
    return percent.toInt().coerceIn(0, 100)
}

/** Which way the sensor's long axis (printed +Y, the logo end) points. */
enum class MountYaw(val label: String) {
    FRONT("Front"),
    RIGHT("Right"),
    BACK("Back"),
    LEFT("Left")
}

/** Which face of the sensor points away from the bike. */
enum class MountFace(val label: String) {
    UP("Label up"),
    DOWN("Label down")
}

/** The plane the sensor is fitted in. */
enum class MountPlane(val label: String) {
    /** Lying on its large face: the sensor's own +Z is vertical (up or down). */
    FLAT("Flat"),
    /** Standing on its edge: the printed +Y arrow points up, the label face is horizontal. */
    VERTICAL("On edge")
}

/**
 * Where the rider bolted the sensor.
 *
 * [MountPlane.FLAT] is the original model: [yaw] is the direction of the printed +Y (the long
 * axis), and [face] says whether the label looks up or down. All eight quarter turns keep the
 * sensor's +Z vertical.
 *
 * [MountPlane.VERTICAL] is WitMotion's documented vertical installation: the board stands on its
 * edge with the printed +Y arrow up, so the label face points horizontally at [yaw]. [face] is
 * unused there and must stay [MountFace.UP]. The device must also be in its vertical installation
 * mode; [Wt9011Protocol.setup] writes that when this mount is selected.
 *
 * The module always decodes in [DEFAULT] - label up, long axis forward, printed +X to the bike's
 * right - and rotates the result here. The zero is captured after this rotation and tagged with
 * the mount, because the frame changes with it; changing the mount therefore clears the zero.
 */
data class MountPosition(
    val yaw: MountYaw = MountYaw.FRONT,
    val face: MountFace = MountFace.UP,
    val plane: MountPlane = MountPlane.FLAT
) {
    init {
        require(plane == MountPlane.FLAT || face == MountFace.UP) {
            "an on-edge mount has no label-up/label-down choice"
        }
    }

    fun apply(packet: Packet): Packet = when (plane) {
        MountPlane.FLAT -> applyFlat(packet)
        MountPlane.VERTICAL -> applyVertical(packet)
    }

    private fun applyFlat(packet: Packet): Packet {
        val down = face == MountFace.DOWN
        val rotated = when {
            !down -> when (yaw) {
                MountYaw.FRONT -> packet
                MountYaw.RIGHT -> packet.copy(
                    leanDeg = -packet.pitchDeg,
                    pitchDeg = packet.leanDeg,
                    longG = -packet.latG,
                    latG = packet.longG,
                    rollRate = packet.pitchRate,
                    pitchRate = -packet.rollRate
                )
                MountYaw.BACK -> packet.copy(
                    leanDeg = -packet.leanDeg,
                    pitchDeg = -packet.pitchDeg,
                    longG = -packet.longG,
                    latG = -packet.latG,
                    rollRate = -packet.rollRate,
                    pitchRate = -packet.pitchRate
                )
                MountYaw.LEFT -> packet.copy(
                    leanDeg = packet.pitchDeg,
                    pitchDeg = -packet.leanDeg,
                    longG = packet.latG,
                    latG = -packet.longG,
                    rollRate = -packet.pitchRate,
                    pitchRate = packet.rollRate
                )
            }
            else -> when (yaw) {
                MountYaw.FRONT -> packet.copy(
                    pitchDeg = -packet.pitchDeg,
                    latG = -packet.latG,
                    pitchRate = -packet.pitchRate
                )
                MountYaw.RIGHT -> packet.copy(
                    leanDeg = packet.pitchDeg,
                    pitchDeg = packet.leanDeg,
                    longG = packet.latG,
                    latG = packet.longG,
                    rollRate = -packet.pitchRate,
                    pitchRate = -packet.rollRate
                )
                MountYaw.BACK -> packet.copy(
                    leanDeg = -packet.leanDeg,
                    longG = -packet.longG,
                    rollRate = -packet.rollRate,
                    pitchRate = packet.pitchRate
                )
                MountYaw.LEFT -> packet.copy(
                    leanDeg = -packet.pitchDeg,
                    pitchDeg = -packet.leanDeg,
                    longG = -packet.latG,
                    latG = -packet.longG,
                    rollRate = packet.pitchRate,
                    pitchRate = packet.rollRate
                )
            }
        }
        val mountOffset = if (down) 180f - 90f * yaw.ordinal else -90f * yaw.ordinal
        return rotated.copy(
            // The accelerometer's up axis points at the road when the label faces down.
            vertG = if (down) -rotated.vertG else rotated.vertG,
            yawRate = if (down) -rotated.yawRate else rotated.yawRate,
            yawDeg = wrap180(
                rotated.yawDeg * (if (down) -1f else 1f) +
                    Wt9011Axes.YAW_OFFSET_DEG + mountOffset
            )
        )
    }

    /**
     * The on-edge frame.
     *
     * WitMotion's vertical installation tells the firmware that the printed +Y is the up axis.
     * The whole packet - angles, accelerometer and gyro - then arrives in a virtual flat frame:
     * its +Z is the sensor's +Y, and its +Y points opposite the label face. The four label
     * directions therefore map to the four flat yaw values turned through 180 degrees, and the
     * same flat quarter-turn mapping applies to every channel.
     *
     * The device must actually be in its vertical installation mode; the module writes that when
     * this mount is selected. The printed +Y arrow, not the USB connector, is the one that must
     * point up.
     */
    private fun applyVertical(packet: Packet): Packet {
        val virtual = when (yaw) {
            MountYaw.FRONT -> MountYaw.BACK
            MountYaw.RIGHT -> MountYaw.LEFT
            MountYaw.BACK -> MountYaw.FRONT
            MountYaw.LEFT -> MountYaw.RIGHT
        }
        // Re-use the flat mapping: the vertical-mode packet is already in that virtual flat frame.
        return MountPosition(yaw = virtual, face = MountFace.UP, plane = MountPlane.FLAT).apply(packet)
    }

    /** Rider-facing one-liner: "Front · Label up" or "Right · On edge". */
    val label: String
        get() = if (plane == MountPlane.VERTICAL) {
            "${yaw.label} · ${plane.label.lowercase(Locale.ROOT)}"
        } else {
            "${yaw.label} · ${face.label}"
        }

    val isDefault: Boolean
        get() = plane == MountPlane.FLAT && yaw == MountYaw.FRONT && face == MountFace.UP

    companion object {
        /** Label up, long axis forward: the mount the docs and the wire mapping assume. */
        val DEFAULT = MountPosition()
    }
}

/**
 * The bracket's own tilt, captured with the bike standing still and upright, then subtracted.
 *
 * The angles are a plain offset, unlike the motion-sensor module's `atan2` calibration: this
 * sensor fuses its own angles, so "zero it now" simply means the screen reads zero now.
 *
 * The g-vector part is a genuine rotation, though: the bracket can be a couple of degrees off
 * level on its base as well as being slightly rolled, and a rotation about the cross product with
 * "up" removes both without touching gravity's magnitude.
 */
class Zero(
    /** Degrees, the lean reading with the bike upright. */
    val leanDeg: Float,
    /** Degrees, the pitch reading with the bike upright. */
    val pitchDeg: Float,
    val forwardG: Float,
    val lateralG: Float,
    val upG: Float,
    val takenAt: Long
) {
    /** Subtracts the zeroed attitude and rotates the g vector so the bike upright reads 0/0/1. */
    fun apply(packet: Packet): Packet {
        val leaned = packet.copy(
            leanDeg = packet.leanDeg - leanDeg,
            pitchDeg = packet.pitchDeg - pitchDeg
        )
        // Right-handed base for the rotation: x forward, y left, z up. The packet's lateral g is
        // right-positive, so it is negated going in and negated coming back out.
        val vx = packet.longG
        val vy = -packet.latG
        val vz = packet.vertG
        val norm = sqrt(forwardG * forwardG + lateralG * lateralG + upG * upG)
        if (norm < 1e-3f) return leaned
        val ux = forwardG / norm
        val uy = -lateralG / norm
        val uz = upG / norm
        // Rotation taking the zeroed up direction to +Z, minimum angle: axis = u x z.
        val kx = uy
        val ky = -ux
        val sin = sqrt(kx * kx + ky * ky)
        val cos = uz
        if (sin < 1e-6f) return leaned
        val px = kx / sin
        val py = ky / sin
        val dot = px * vx + py * vy
        val cx = py * vz
        val cy = -px * vz
        val cz = px * vy - py * vx
        val oneMinusCos = 1f - cos
        // The angular rate lives in the same tilted frame, so the same rotation levels it. Its
        // vector is already x forward, y left, z up.
        val wx = packet.rollRate
        val wy = packet.pitchRate
        val wz = packet.yawRate
        val wDot = px * wx + py * wy
        return leaned.copy(
            longG = vx * cos + cx * sin + px * dot * oneMinusCos,
            latG = -(vy * cos + cy * sin + py * dot * oneMinusCos),
            vertG = vz * cos + cz * sin,
            rollRate = wx * cos + py * wz * sin + px * wDot * oneMinusCos,
            pitchRate = wy * cos - px * wz * sin + py * wDot * oneMinusCos,
            yawRate = wz * cos + (px * wy - py * wx) * sin
        )
    }

    companion object {
        /** Total gravity must be near 1 g, or the bike is moving and the sample is not a zero. */
        const val MIN_TOTAL_G = 0.7f
        const val MAX_TOTAL_G = 1.3f

        /** Upright means gravity is on the bike's vertical axis; below this the mounted bracket is off. */
        const val MIN_UPRIGHT_G = 0.5f

        fun totalG(packet: Packet): Float = sqrt(
            packet.longG * packet.longG +
                packet.latG * packet.latG +
                packet.vertG * packet.vertG
        )

        /** Null when this sample cannot be a zero: use [totalG] and the packet to explain why. */
        fun of(packet: Packet, takenAt: Long): Zero? {
            val total = totalG(packet)
            if (total < MIN_TOTAL_G || total > MAX_TOTAL_G) return null
            if (abs(packet.vertG) < MIN_UPRIGHT_G) return null
            return Zero(
                leanDeg = packet.leanDeg,
                pitchDeg = packet.pitchDeg,
                forwardG = packet.longG,
                lateralG = packet.latG,
                upG = packet.vertG,
                takenAt = takenAt
            )
        }
    }
}

/** Why [Zero.of] refused a sample, in words a rider on a bench can act on. */
fun zeroRefusal(packet: Packet): String? {
    val total = Zero.totalG(packet)
    if (total < Zero.MIN_TOTAL_G || total > Zero.MAX_TOTAL_G) {
        return String.format(
            Locale.ROOT,
            "Hold the bike still: gravity reads %.2f g, expected 1.00 g",
            total
        )
    }
    if (abs(packet.vertG) < Zero.MIN_UPRIGHT_G) {
        return String.format(
            Locale.ROOT,
            "Sensor is not upright: vertical axis reads %+.2f g (bench test T1)",
            packet.vertG
        )
    }
    return null
}

/**
 * Directional maxima since the last reset, in the mounted and zeroed bike frame.
 *
 * The screen refreshes at 10 Hz but the sensor streams at 100 Hz. Tracking here, on every decoded
 * sample, means a pothole or a hard brake between two screen refreshes still counts toward MAX L,
 * MAX R, ACCEL and BRAKE.
 */
class Peaks {
    @Volatile var maxLeftLean: Float = 0f
        private set
    @Volatile var maxRightLean: Float = 0f
        private set
    @Volatile var maxLeftG: Float = 0f
        private set
    @Volatile var maxRightG: Float = 0f
        private set
    @Volatile var maxAccel: Float = 0f
        private set
    @Volatile var maxBrake: Float = 0f
        private set

    /** Adds one already-mounted, already-zeroed packet. */
    fun add(packet: Packet) {
        if (packet.leanDeg >= 0f) {
            maxRightLean = maxOf(maxRightLean, packet.leanDeg)
        } else {
            maxLeftLean = maxOf(maxLeftLean, -packet.leanDeg)
        }
        if (packet.latG >= 0f) {
            maxRightG = maxOf(maxRightG, packet.latG)
        } else {
            maxLeftG = maxOf(maxLeftG, -packet.latG)
        }
        maxAccel = maxOf(maxAccel, packet.longG)
        maxBrake = maxOf(maxBrake, -packet.longG)
    }

    fun reset() {
        maxLeftLean = 0f
        maxRightLean = 0f
        maxLeftG = 0f
        maxRightG = 0f
        maxAccel = 0f
        maxBrake = 0f
    }

    /** An immutable copy for a snapshot, so the screen cannot see a half-updated set. */
    fun copyOf(): Peaks = Peaks().also {
        it.maxLeftLean = maxLeftLean
        it.maxRightLean = maxRightLean
        it.maxLeftG = maxLeftG
        it.maxRightG = maxRightG
        it.maxAccel = maxAccel
        it.maxBrake = maxBrake
    }
}

/**
 * One frame off the wire: either a data sample or the device's answer to a register read.
 *
 * Both travel on the same notification characteristic, and their order is whatever the device
 * felt like: the parser hands them out in arrival order and the link sorts them out.
 */
sealed class Wt9011Frame {
    class Sample(val sample: Wt9011Sample) : Wt9011Frame()
    class Registers(val reply: RegisterReply) : Wt9011Frame()
}

/**
 * Decodes one 20-byte `55 61` frame.
 *
 * Layout, all little-endian 16-bit words, header at 0-1:
 * `2-3 AX, 4-5 AY, 6-7 AZ, 8-9 WX, 10-11 WY, 12-13 WZ, 14-15 Roll, 16-17 Pitch, 18-19 Yaw`.
 * Accel ±16 g, gyro ±2000 °/s, angles ±180° over 32768 counts.
 *
 * Returns null for anything that is not exactly one data frame: the parser always hands over whole
 * frames, so a mismatch here is a bug worth failing loudly in a unit test rather than masking.
 *
 * [sensorMillis] is the arrival time on this phone. The device sends no counter and no timestamp,
 * so the only honest clock is the one that saw the bytes; [seq] is counted by the parser.
 */
fun decodeSample(frame: ByteArray, seq: Int, sensorMillis: Long): Wt9011Sample? {
    if (frame.size != Wt9011Protocol.FRAME_SIZE) return null
    if ((frame[0].toInt() and 0xFF) != Wt9011Protocol.HEADER) return null
    if ((frame[1].toInt() and 0xFF) != Wt9011Protocol.FLAG_DATA) return null

    val raw = Wt9011Raw(
        ax = readInt16(frame, 2) * Wt9011Protocol.ACCEL_SCALE,
        ay = readInt16(frame, 4) * Wt9011Protocol.ACCEL_SCALE,
        az = readInt16(frame, 6) * Wt9011Protocol.ACCEL_SCALE,
        wx = readInt16(frame, 8) * Wt9011Protocol.GYRO_SCALE,
        wy = readInt16(frame, 10) * Wt9011Protocol.GYRO_SCALE,
        wz = readInt16(frame, 12) * Wt9011Protocol.GYRO_SCALE,
        rollDeg = readInt16(frame, 14) * Wt9011Protocol.ANGLE_SCALE,
        pitchDeg = readInt16(frame, 16) * Wt9011Protocol.ANGLE_SCALE,
        yawDeg = readInt16(frame, 18) * Wt9011Protocol.ANGLE_SCALE
    )

    // The default mount: printed +Y is the bike's nose, +X is its right, +Z is up. MountPosition
    // rotates this result to the rider's actual bracket.
    val gyro = Wt9011Axes.GYRO_SIGN * DEGREES_TO_RADIANS

    return Wt9011Sample(
        raw = raw,
        packet = Packet(
            seq = seq,
            sensorMillis = sensorMillis,
            leanDeg = raw.pitchDeg * Wt9011Axes.LEAN_SIGN,
            pitchDeg = raw.rollDeg * Wt9011Axes.PITCH_SIGN,
            yawDeg = wrap180(raw.yawDeg * Wt9011Axes.YAW_SIGN),
            longG = raw.ay * Wt9011Axes.ACC_SIGN,
            latG = raw.ax * Wt9011Axes.ACC_SIGN,
            vertG = raw.az * Wt9011Axes.ACC_SIGN,
            rollRate = raw.wy * gyro,
            pitchRate = -raw.wx * gyro,
            yawRate = raw.wz * gyro
        )
    )
}

private const val DEGREES_TO_RADIANS = (Math.PI / 180.0).toFloat()

/** The ride model of one frame, for callers that do not need the raw channels. */
fun decodePacket(frame: ByteArray, seq: Int, sensorMillis: Long): Packet? =
    decodeSample(frame, seq, sensorMillis)?.packet

/** Magnetic bearing for display: the packet keeps -180…180, screens read 0…360. */
fun bearingDeg(yawDeg: Float): Float {
    val wrapped = wrap180(yawDeg)
    return if (wrapped < 0f) wrapped + 360f else wrapped
}

/** Wraps an angle in degrees into -180…180. */
fun wrap180(deg: Float): Float {
    if (!deg.isFinite()) return 0f
    var d = (deg + 180f) % 360f
    if (d < 0f) d += 360f
    return d - 180f
}

/**
 * Reassembles the device's byte stream into whole frames.
 *
 * The protocol has no length byte, no checksum and no sequence number, so the only
 * synchronisation available is the `55` header. Two things follow, and both are where the vendor's
 * own Android SDK goes wrong:
 *
 * - Resynchronising must drop **one** byte at a time and re-test. The vendor resolver's loop tests
 *   two conditions with `&&` where it needs `||`, so it can leave a buffer that never syncs again.
 * - The dispatch must happen on the flag *after* the header, not on the header alone, or the
 *   `0x71` register replies thrown into the same stream are mistaken for noise and dropped.
 *
 * This is the Python SDK's model (accumulate, test, drop one, retry), which is the correct one.
 * Unknown flags are treated as noise: drop the `55` and resync - that also handles the `55 5F`
 * 11-byte frames of the other protocol generation without guessing their length.
 *
 * Not thread safe: it is fed from one BLE callback thread only.
 */
class Wt9011FrameParser {
    private val buffer = ArrayList<Byte>(64)
    private var seq = 0

    /** Drops everything buffered, for a reconnect where the next byte may be mid-frame. */
    fun reset() {
        buffer.clear()
    }

    /**
     * Feeds one notification's bytes and returns whatever whole frames they completed.
     *
     * [now] is stamped on every sample produced: a chunk can complete two frames, and the phone
     * has no better timestamp for either of them than the moment the notification arrived.
     */
    fun feed(chunk: ByteArray, now: Long): List<Wt9011Frame> {
        for (byte in chunk) buffer.add(byte)
        val frames = ArrayList<Wt9011Frame>(2)
        while (true) {
            while (buffer.isNotEmpty() && (buffer[0].toInt() and 0xFF) != Wt9011Protocol.HEADER) {
                buffer.removeAt(0)
            }
            if (buffer.size < 2) break
            val flag = buffer[1].toInt() and 0xFF
            val length = when (flag) {
                Wt9011Protocol.FLAG_DATA -> Wt9011Protocol.FRAME_SIZE
                Wt9011Protocol.FLAG_REGISTER -> Wt9011Protocol.FRAME_SIZE
                else -> 0
            }
            if (length == 0) {
                // Not a frame this generation: the header byte was part of something else.
                buffer.removeAt(0)
                continue
            }
            if (buffer.size < length) break
            val frame = ByteArray(length) { buffer[it] }
            repeat(length) { buffer.removeAt(0) }
            when (flag) {
                Wt9011Protocol.FLAG_DATA -> {
                    val sample = decodeSample(frame, seq, now) ?: continue
                    seq = (seq + 1) and 0x7FFFFFFF
                    frames.add(Wt9011Frame.Sample(sample))
                }
                else -> {
                    val base = (frame[2].toInt() and 0xFF) or ((frame[3].toInt() and 0xFF) shl 8)
                    val payload = frame.copyOfRange(4, 4 + Wt9011Protocol.REGISTER_PAYLOAD)
                    frames.add(Wt9011Frame.Registers(RegisterReply(base, payload)))
                }
            }
        }
        return frames
    }
}

/** Little-endian signed 16-bit, both bytes masked: a bare `or` on signed bytes sign-extends. */
private fun readInt16(bytes: ByteArray, index: Int): Int {
    val low = bytes[index].toInt() and 0xFF
    val high = bytes[index + 1].toInt() and 0xFF
    val value = low or (high shl 8)
    return if (value >= 0x8000) value - 0x10000 else value
}
