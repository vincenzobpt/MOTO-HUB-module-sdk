// Road events for the route simulator: seeded placement along the route, and the state machine
// that turns them into speed ceilings, stops and bursts while the ride is simulated.
package io.motohub.android.routesim.sim

import java.util.Random
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

enum class TrafficEventType {
    /** Brake to a stop on [TrafficEvent.startMeters], wait, accelerate away. */
    TRAFFIC_LIGHT,

    /** Slow zone of [TrafficEvent.lengthMeters] at [TrafficEvent.speedKph]. */
    JUNCTION,

    /** Crawl at [TrafficEvent.speedKph] for [TrafficEvent.lengthMeters]. */
    QUEUE,

    /** Brief burst of [TrafficEvent.speedKph] over the target, for [TrafficEvent.durationMs]. */
    OVERTAKE,
}

/**
 * One event on the road. The meaning of the numbers depends on the type:
 * - TRAFFIC_LIGHT: [startMeters] is the stop line, [durationMs] the wait.
 * - JUNCTION / QUEUE: the zone [startMeters] .. [endMeters], [speedKph] the speed ceiling in it.
 * - OVERTAKE: eligible from [startMeters] for [lengthMeters] metres (it waits for a stretch where
 *   overtaking makes sense), then lasts [durationMs] at [speedKph] above the target.
 */
class TrafficEvent(
    val type: TrafficEventType,
    val startMeters: Double,
    val lengthMeters: Double,
    val speedKph: Double,
    val durationMs: Long,
) {
    val endMeters: Double get() = startMeters + lengthMeters
}

object TrafficPlanner {

    /** Events per km: LIGHT ~0.15, HEAVY ~0.5, NONE 0. */
    fun eventsPerKm(level: TrafficLevel): Double = when (level) {
        TrafficLevel.NONE -> 0.0
        TrafficLevel.LIGHT -> 0.15
        TrafficLevel.HEAVY -> 0.5
    }

    /** Nothing happens in the first or last stretch: the rider is pulling away or arriving. */
    const val EDGE_MARGIN_M = 200.0

    /** Least gap between the end of an event and the start of the next one. */
    const val MIN_GAP_M = 150.0

    const val JUNCTION_LENGTH_M = 30.0
    const val OVERTAKE_WINDOW_M = 300.0

    /**
     * Places events along a route of [totalMeters] with a Poisson-like frequency per km, from
     * [rng] only (same rng state in, same events out). Events never overlap and are sorted by
     * distance. [overtakeAllowedAt] tells whether overtaking is sensible at a distance (segments
     * with a limit of at least 70 km/h, or with no known limit).
     *
     * A HEAVY plan on a route of at least 2 km always contains at least one traffic light, so
     * "heavy traffic" is never indistinguishable from none by bad luck.
     */
    fun plan(
        totalMeters: Double,
        level: TrafficLevel,
        rng: Random,
        overtakeAllowedAt: (Double) -> Boolean,
    ): List<TrafficEvent> {
        val rate = eventsPerKm(level)
        if (rate <= 0.0 || totalMeters < 2.0 * EDGE_MARGIN_M + 100.0) return emptyList()

        val meanGapM = 1000.0 / rate
        val events = ArrayList<TrafficEvent>()
        var cursor = EDGE_MARGIN_M
        while (true) {
            val gap = max(MIN_GAP_M, -ln(1.0 - rng.nextDouble()) * meanGapM)
            val start = cursor + gap
            val event = draw(start, level, rng, overtakeAllowedAt)
            if (event.endMeters > totalMeters - EDGE_MARGIN_M) break
            events.add(event)
            cursor = event.endMeters
        }

        if (level == TrafficLevel.HEAVY && totalMeters >= 2000.0 && events.none { it.type == TrafficEventType.TRAFFIC_LIGHT }) {
            if (events.isEmpty()) {
                events.add(light(totalMeters / 2.0, rng))
            } else {
                events[0] = light(events[0].startMeters, rng)
            }
        }
        return events
    }

    private fun draw(start: Double, level: TrafficLevel, rng: Random, overtakeAllowedAt: (Double) -> Boolean): TrafficEvent {
        val overtakeOk = overtakeAllowedAt(start)
        // Weights: light, junction, queue, overtake.
        val wLight = 0.30
        val wJunction = if (level == TrafficLevel.HEAVY) 0.25 else 0.30
        val wQueue = if (level == TrafficLevel.HEAVY) 0.30 else 0.15
        val wOvertake = if (!overtakeOk) 0.0 else if (level == TrafficLevel.HEAVY) 0.15 else 0.25
        val total = wLight + wJunction + wQueue + wOvertake
        val r = rng.nextDouble() * total
        return when {
            r < wLight -> light(start, rng)
            r < wLight + wJunction -> TrafficEvent(
                TrafficEventType.JUNCTION, start, JUNCTION_LENGTH_M,
                15.0 + 15.0 * rng.nextDouble(), 0L,
            )
            r < wLight + wJunction + wQueue -> TrafficEvent(
                TrafficEventType.QUEUE, start, 100.0 + 300.0 * rng.nextDouble(),
                5.0 + 20.0 * rng.nextDouble(), 0L,
            )
            else -> TrafficEvent(
                TrafficEventType.OVERTAKE, start, OVERTAKE_WINDOW_M,
                15.0 + 15.0 * rng.nextDouble(), 8000L + (rng.nextDouble() * 7000.0).toLong(),
            )
        }
    }

    private fun light(start: Double, rng: Random): TrafficEvent =
        TrafficEvent(TrafficEventType.TRAFFIC_LIGHT, start, 0.0, 0.0, 8000L + (rng.nextDouble() * 37000.0).toLong())
}

/**
 * The event state machine, driven by the simulation loop once per tick.
 *
 * Events are met in order. A traffic light is a hard stop point: it caps the speed so the
 * rider can brake to a halt on the line, then holds the bike there for the wait and releases it.
 * Junctions and queues are zones with a speed ceiling. An overtake is time-based: once its
 * stretch is reached and overtaking makes sense it raises the target by a boost for a few
 * seconds. Not thread-safe.
 */
class TrafficRuntime(private val events: List<TrafficEvent>) {

    private var idx = 0
    private var waiting = false
    private var waitLeftMs = 0L
    private var overtaking = false
    private var overtakeLeftMs = 0L

    /** True while the bike is held at a red light. */
    val holding: Boolean get() = waiting

    /** Current overtake boost over the target, in m/s (0 when not overtaking). */
    var boostMs: Double = 0.0
        private set

    /** Number of traffic lights the bike has stopped at so far. */
    var lightStops: Int = 0
        private set

    /**
     * Advances the machine by one tick of [dtMs]. Returns the distance the bike must be placed
     * at when it has just come to a halt on a stop line (the caller sets its distance and speed
     * to 0 and then keeps it there while [holding]), or NaN.
     */
    fun update(distanceM: Double, speedMs: Double, dtMs: Long, overtakeOk: Boolean): Double {
        if (waiting) {
            waitLeftMs -= dtMs
            if (waitLeftMs <= 0L) {
                waiting = false
                idx++
            }
            return Double.NaN
        }
        if (overtaking) {
            overtakeLeftMs -= dtMs
            if (overtakeLeftMs <= 0L) {
                overtaking = false
                boostMs = 0.0
                idx++
            }
            return Double.NaN
        }

        // Retire events already behind.
        while (idx < events.size) {
            val ev = events[idx]
            val gone = when (ev.type) {
                TrafficEventType.TRAFFIC_LIGHT -> distanceM > ev.startMeters + STOP_OVERSHOOT_M
                else -> distanceM > ev.endMeters
            }
            if (!gone) break
            idx++
        }
        if (idx >= events.size) return Double.NaN

        val ev = events[idx]
        when (ev.type) {
            TrafficEventType.TRAFFIC_LIGHT -> {
                if (ev.startMeters - distanceM <= STOP_CAPTURE_M && speedMs <= STOP_CAPTURE_SPEED_MS) {
                    waiting = true
                    waitLeftMs = ev.durationMs
                    lightStops++
                    return ev.startMeters
                }
            }
            TrafficEventType.OVERTAKE -> {
                if (distanceM >= ev.startMeters && overtakeOk) {
                    overtaking = true
                    overtakeLeftMs = ev.durationMs
                    boostMs = ev.speedKph / 3.6
                }
            }
            else -> Unit
        }
        return Double.NaN
    }

    /**
     * Speed ceiling in m/s at distance [s] imposed by the events ahead, such that braking at
     * [planBrakeMs2] still meets each of them; Double.MAX_VALUE when none applies.
     */
    fun allowedMs(s: Double, planBrakeMs2: Double): Double {
        if (waiting) return 0.0
        var allowed = Double.MAX_VALUE
        var j = idx
        while (j < events.size) {
            val ev = events[j]
            if (ev.startMeters - s > LOOKAHEAD_M) break
            when (ev.type) {
                TrafficEventType.TRAFFIC_LIGHT -> {
                    val ds = ev.startMeters - s
                    if (ds >= -STOP_OVERSHOOT_M) allowed = minOf(allowed, stopCeilingMs(ds, planBrakeMs2))
                }
                TrafficEventType.JUNCTION, TrafficEventType.QUEUE -> {
                    val c = ev.speedKph / 3.6
                    if (s < ev.startMeters) {
                        allowed = minOf(allowed, sqrt(c * c + 2.0 * planBrakeMs2 * (ev.startMeters - s)))
                    } else if (s <= ev.endMeters) {
                        allowed = minOf(allowed, c)
                    }
                }
                TrafficEventType.OVERTAKE -> Unit
            }
            j++
        }
        return allowed
    }

    companion object {
        /** How far ahead events are considered. Longer than the longest braking distance. */
        const val LOOKAHEAD_M = 600.0

        /** Within this distance of a stop point and slow enough, the bike counts as stopped. */
        const val STOP_CAPTURE_M = 0.15
        const val STOP_CAPTURE_SPEED_MS = 0.35
        const val STOP_OVERSHOOT_M = 1.0

        /** Below this distance to a stop point the ceiling fades out linearly instead of ending abruptly. */
        private const val STOP_FADE_M = 1.0

        /**
         * Highest speed in m/s with which a stop point [ds] metres ahead can still be met, braking at
         * [planBrakeMs2]: the constant-deceleration curve sqrt(2 * b * ds), turning into a straight
         * line to zero over the last metre so the bike eases to a halt instead of dropping to 0.
         */
        fun stopCeilingMs(ds: Double, planBrakeMs2: Double): Double {
            if (ds <= 0.0) return 0.0
            val constantDecel = sqrt(2.0 * planBrakeMs2 * ds)
            val fade = sqrt(2.0 * planBrakeMs2 * STOP_FADE_M) * (ds / STOP_FADE_M)
            return if (fade < constantDecel) fade else constantDecel
        }
    }
}
