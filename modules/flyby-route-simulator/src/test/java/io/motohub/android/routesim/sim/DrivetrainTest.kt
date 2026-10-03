// Tests for Drivetrain: gear vs speed, hysteresis, idle, rpm bounds, shift dip, braking bias, determinism.
package io.motohub.android.routesim.sim

import java.util.Random
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private val RATIOS = doubleArrayOf(558.0, 410.0, 320.0, 262.0, 222.0, 190.0)
private const val IDLE = 1300.0
private const val MAX_RPM = 9500.0

private fun driveProfile() = DrivingProfile(
    style = RideStyle.NORMAL,
    accelMs2 = 2.2,
    brakeMs2 = 3.0,
    lateralAccelMs2 = 3.2,
    overLimitFactor = 0.98,
    defaultCruiseKph = 80.0,
    maxLeanDeg = 38.0,
    idleRpm = IDLE,
    maxRpm = MAX_RPM,
    shiftUpRpm = 6200.0,
    shiftDownRpm = 2800.0,
    gearRatios = RATIOS,
)

class DrivetrainTest {

    private class Sample(val t: Double, val v: Double, val state: DriveState)

    /** Accelerate from standstill at [accel] m/s^2 up to [vMax], sampled every [dt]. */
    private fun ramp(seed: Long, accel: Double, vMax: Double, dt: Double, seconds: Double): List<Sample> {
        val dt0 = Drivetrain(driveProfile(), Random(seed))
        val out = ArrayList<Sample>()
        var v = 0.0
        var t = 0.0
        while (t < seconds) {
            val a = if (v < vMax) accel else 0.0
            out.add(Sample(t, v, dt0.step(v, a, dt)))
            v = minOf(vMax, v + a * dt)
            t += dt
        }
        return out
    }

    @Test
    fun gearIsMonotonicOnASpeedRampAndRpmStaysInBounds() {
        val samples = ramp(seed = 1, accel = 1.5, vMax = 33.0, dt = 0.05, seconds = 26.0)
        assertEquals(0, samples.first().state.gear)
        var prev = 0
        for (s in samples) {
            assertTrue("gear went down on a ramp at t=${s.t}: $prev -> ${s.state.gear}", s.state.gear >= prev)
            prev = s.state.gear
            assertTrue("rpm ${s.state.rpm}", s.state.rpm >= IDLE && s.state.rpm <= MAX_RPM)
        }
        assertEquals(6, samples.last().state.gear)
        val gears = samples.map { it.state.gear }.toSet()
        assertTrue("every gear visited: $gears", gears.containsAll(listOf(0, 1, 2, 3, 4, 5, 6)))
    }

    @Test
    fun noFlappingWhenHoveringAtAnUpshiftBoundary() {
        val model = Drivetrain(driveProfile(), Random(2))
        val boundary = 6200.0 / RATIOS[0] // ~11.1 m/s, where 1st gear reaches the shift-up rpm
        // Get moving first, below the boundary.
        var v = 0.0
        while (v < boundary - 0.3) {
            model.step(v, 1.5, 0.05)
            v += 1.5 * 0.05
        }
        var shifts = 0
        var prevGear = model.step(v, 0.0, 0.05).gear
        val dt = 0.05
        for (i in 0 until 1200) { // 60 s
            val tt = i * dt
            val vv = boundary + 0.3 * sin(2.0 * PI * tt / 4.0)
            val a = 0.3 * (2.0 * PI / 4.0) * kotlin.math.cos(2.0 * PI * tt / 4.0)
            val g = model.step(vv, a, dt).gear
            if (g != prevGear) shifts++
            prevGear = g
        }
        assertTrue("shifts while hovering: $shifts", shifts < 4)
    }

    @Test
    fun noFlappingWhenHoveringAtADownshiftBoundary() {
        val model = Drivetrain(driveProfile(), Random(4))
        // Reach 2nd gear, then settle onto the 2nd-gear shift-down speed.
        var v = 0.0
        while (v < 14.0) {
            model.step(v, 1.5, 0.05)
            v += 1.5 * 0.05
        }
        // Coast down in 2nd (accel reported as 0, so the plain 2800 rpm threshold applies).
        while (v > 8.5) {
            model.step(v, 0.0, 0.05)
            v -= 2.0 * 0.05
        }
        // Hover across the 2nd-gear shift-down speed (2800/410 = 6.8 m/s, a bit higher while braking).
        val centre = 7.2
        var shifts = 0
        var prevGear = model.step(v, 0.0, 0.05).gear
        assertEquals(2, prevGear)
        val dt = 0.05
        for (i in 0 until 1200) {
            val tt = i * dt
            val vv = centre + 0.6 * sin(2.0 * PI * tt / 4.0)
            val a = 0.6 * (2.0 * PI / 4.0) * kotlin.math.cos(2.0 * PI * tt / 4.0)
            val g = model.step(vv, a, dt).gear
            if (g != prevGear) shifts++
            prevGear = g
        }
        assertTrue("shifts while hovering: $shifts", shifts < 4)
    }

    @Test
    fun idleAndNeutralWhenStopped() {
        val model = Drivetrain(driveProfile(), Random(5))
        var last = model.step(0.0, 0.0, 0.1)
        for (i in 0 until 100) {
            last = model.step(0.0, 0.0, 0.1)
            assertEquals(0, last.gear)
            assertTrue("rpm ${last.rpm}", last.rpm >= IDLE && last.rpm <= IDLE * 1.03)
        }
        assertEquals(0, last.gear)

        // After a real ride and a stop it returns to neutral and idle.
        val m2 = Drivetrain(driveProfile(), Random(5))
        var v = 0.0
        while (v < 20.0) {
            m2.step(v, 2.0, 0.05)
            v += 2.0 * 0.05
        }
        while (v > 0.0) {
            m2.step(v, -3.0, 0.05)
            v = maxOf(0.0, v - 3.0 * 0.05)
        }
        var s = m2.step(0.0, 0.0, 0.05)
        for (i in 0 until 60) s = m2.step(0.0, 0.0, 0.05) // 3 s
        assertEquals(0, s.gear)
        assertTrue("rpm after stop ${s.rpm}", s.rpm >= IDLE && s.rpm <= IDLE * 1.03)
    }

    @Test
    fun clutchSlipRaisesRpmSmoothlyAndEngagesFirstAboveTwoKph() {
        val model = Drivetrain(driveProfile(), Random(6))
        // A profile where 1st gear gives well above idle at 2 km/h would rise; ours reaches idle floor.
        val below = model.step(1.0 / 3.6, 0.5, 0.05)
        assertEquals(0, below.gear)
        val above = model.step(3.0 / 3.6, 0.5, 0.05)
        assertEquals(1, above.gear)
        assertTrue(above.rpm >= IDLE)
    }

    @Test
    fun limiterCapsRpm() {
        val model = Drivetrain(driveProfile(), Random(8))
        var maxSeen = 0.0
        var last = 0.0
        for (i in 0 until 200) {
            val s = model.step(60.0, 0.0, 0.05) // 6th at 60 m/s asks for 11400 rpm
            maxSeen = maxOf(maxSeen, s.rpm)
            last = s.rpm
        }
        assertTrue("limiter exceeded: $maxSeen", maxSeen <= MAX_RPM)
        assertTrue("sits near the limiter: $last", last >= 9000.0)
    }

    @Test
    fun rpmDipsAfterUpshiftAndRecovers() {
        val samples = ramp(seed = 11, accel = 1.5, vMax = 33.0, dt = 0.05, seconds = 24.0)
        var checked = 0
        for (i in 1 until samples.size - 20) {
            val before = samples[i - 1].state
            val now = samples[i].state
            if (before.gear >= 1 && now.gear == before.gear + 1) {
                var minAfter = Double.MAX_VALUE
                for (j in i until i + 6) minAfter = minOf(minAfter, samples[j].state.rpm) // 0.3 s
                assertTrue(
                    "dip $minAfter not below pre-shift ${before.rpm} (gear ${before.gear}->${now.gear})",
                    minAfter < before.rpm * 0.85,
                )
                val recovered = samples[i + 16].state.rpm // 0.8 s later
                assertTrue("no recovery: $recovered vs dip $minAfter", recovered > minAfter * 1.05)
                checked++
            }
        }
        assertTrue("upshifts checked: $checked", checked >= 4)
    }

    @Test
    fun rpmNeverJumpsOnAnUpshift() {
        val samples = ramp(seed = 12, accel = 1.5, vMax = 33.0, dt = 0.05, seconds = 24.0)
        for (i in 1 until samples.size) {
            val d = kotlin.math.abs(samples[i].state.rpm - samples[i - 1].state.rpm)
            assertTrue("rpm jumped by $d at t=${samples[i].t}", d < 1500.0)
        }
    }

    @Test
    fun downshiftRpmRisesToNewGearValue() {
        val model = Drivetrain(driveProfile(), Random(13))
        var v = 25.0
        var prev = model.step(v, -2.0, 0.05)
        var checked = 0
        var i = 0
        while (v > 5.0 && i < 1000) {
            i++
            v -= 2.0 * 0.05
            val s = model.step(v, -2.0, 0.05)
            if (s.gear in 1 until prev.gear) {
                val base = v * RATIOS[s.gear - 1]
                if (base > 2000.0) {
                    var peak = 0.0
                    var vv = v
                    for (j in 0 until 12) { // 0.6 s
                        vv -= 2.0 * 0.05
                        peak = maxOf(peak, model.step(vv, -2.0, 0.05).rpm)
                    }
                    v = vv
                    assertTrue("peak $peak vs new-gear rpm $base", peak >= base * 0.9)
                    checked++
                    prev = model.step(v, -2.0, 0.05)
                    continue
                }
            }
            prev = s
        }
        assertTrue("downshifts checked: $checked", checked >= 1)
    }

    @Test
    fun brakingDownshiftsEarlierThanCoasting() {
        fun firstDownshiftSpeed(accelArg: Double): Double {
            val model = Drivetrain(driveProfile(), Random(14))
            var v = 20.0
            var prev = model.step(v, accelArg, 0.05).gear
            while (v > 4.0) {
                v -= 1.5 * 0.05
                val g = model.step(v, accelArg, 0.05).gear
                if (g in 1 until prev) return v
                prev = g
            }
            return 0.0
        }

        val braking = firstDownshiftSpeed(-3.0)
        val coasting = firstDownshiftSpeed(0.0)
        assertTrue("both downshift: $braking $coasting", braking > 0.0 && coasting > 0.0)
        assertTrue("braking $braking should downshift at higher speed than coasting $coasting", braking > coasting)
    }

    @Test
    fun deterministicForSameSeed() {
        val a = ramp(seed = 99, accel = 1.5, vMax = 30.0, dt = 0.1, seconds = 30.0)
        val b = ramp(seed = 99, accel = 1.5, vMax = 30.0, dt = 0.1, seconds = 30.0)
        assertEquals(a.size, b.size)
        for (i in a.indices) {
            assertEquals(a[i].state.gear, b[i].state.gear)
            assertEquals(a[i].state.rpm, b[i].state.rpm, 0.0)
        }
    }
}
