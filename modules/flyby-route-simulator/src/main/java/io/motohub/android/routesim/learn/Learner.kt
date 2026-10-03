// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// "Learn from my rides": reads the rider's recorded rides through the host, hands them to
// ProfileLearner and reports what came out. On demand only, blocking (call it off the main thread).
package io.motohub.android.routesim.learn

import io.motohub.android.module.ModuleRideEntry
import io.motohub.android.module.ModuleRideTrack
import io.motohub.android.module.MotoHubModuleHost

/** What came out of one run. [ok] is false when it stayed generic; [message] then says why. */
class LearnReport(
    val ok: Boolean,
    val eligibleRides: Int,
    val needed: Int,
    val message: String,
    val facts: List<Pair<String, String>>,
    val profile: LearnedProfile?,
)

/** The slice of the host the learner reads, so a test can stand in for the whole host. */
internal interface Sources {
    fun recordedRides(limit: Int): List<ModuleRideEntry>
    fun isSimulated(entry: ModuleRideEntry): Boolean
    fun track(entry: ModuleRideEntry, maxPoints: Int): ModuleRideTrack?
    fun engineRpm(track: ModuleRideTrack): FloatArray?

    /** Per segment (points - 1) limits for a route, or null on failure. */
    fun speedLimits(latitudes: DoubleArray, longitudes: DoubleArray): FloatArray?

    companion object {
        fun from(host: MotoHubModuleHost): Sources = object : Sources {
            override fun recordedRides(limit: Int) = host.rides.recordedRides(limit)
            override fun isSimulated(entry: ModuleRideEntry) = host.rides.isSimulated(entry)
            override fun track(entry: ModuleRideEntry, maxPoints: Int) = host.rides.track(entry, maxPoints)
            override fun engineRpm(track: ModuleRideTrack) = host.rides.engineRpm(track)
            override fun speedLimits(latitudes: DoubleArray, longitudes: DoubleArray) =
                host.routing.speedLimits(latitudes, longitudes)
        }
    }
}

class Learner internal constructor(private val sources: Sources) {

    constructor(host: MotoHubModuleHost) : this(Sources.from(host))

    /**
     * Blocking: reads rides and, for the most recent few, asks the network for speed limits.
     * Never throws; a ride that cannot be read is skipped.
     */
    fun learn(onProgress: (String) -> Unit, nowMillis: Long): LearnReport {
        onProgress(LearnStrings.LISTING)
        val recorded: List<ModuleRideEntry> = try { sources.recordedRides(LIST_LIMIT) } catch (e: Exception) { ArrayList() }
        val eligible = ArrayList<ModuleRideEntry>()
        for (entry in recorded) {
            if (entry.distanceMeters < MIN_RIDE_METERS) continue
            val simulated = try { sources.isSimulated(entry) } catch (e: Exception) { true } // when in doubt, leave it out
            if (!simulated) eligible.add(entry)
        }
        if (eligible.size < NEEDED) {
            return notEnough(eligible.size)
        }

        // Newest first, whatever order the host listed them in.
        java.util.Collections.sort(eligible, java.util.Comparator<ModuleRideEntry> { a, b -> b.dateMillis.compareTo(a.dateMillis) })
        val chosenCount = Math.min(eligible.size, MAX_RIDES)
        val samples = ArrayList<RideSample>()
        var km = 0.0
        for (position in 0 until chosenCount) {
            val entry = eligible[position]
            onProgress(LearnStrings.readingRide(position + 1, chosenCount))
            val sample = try { read(entry, position < LIMIT_RIDES, onProgress, position + 1) } catch (e: Exception) { null }
            if (sample != null) {
                samples.add(sample)
                km += entry.distanceMeters / 1000.0
            }
        }
        if (samples.size < NEEDED) {
            return LearnReport(
                ok = false,
                eligibleRides = eligible.size,
                needed = NEEDED,
                message = LearnStrings.unreadableRides(NEEDED, samples.size),
                facts = ArrayList(),
                profile = null,
            )
        }

        onProgress(LearnStrings.ANALYSING)
        val profile = ProfileLearner.learn(samples, km, nowMillis)
        if (!profile.hasAnything) {
            return LearnReport(false, eligible.size, NEEDED, LearnStrings.NOTHING_LEARNED, ArrayList(), null)
        }
        return LearnReport(true, eligible.size, NEEDED, "", facts(profile), profile)
    }

    private fun notEnough(have: Int): LearnReport = LearnReport(
        ok = false,
        eligibleRides = have,
        needed = NEEDED,
        message = LearnStrings.needMoreRides(NEEDED, MIN_RIDE_KM, have),
        facts = ArrayList(),
        profile = null,
    )

    private fun read(entry: ModuleRideEntry, withLimits: Boolean, onProgress: (String) -> Unit, position: Int): RideSample? {
        val track = sources.track(entry, TRACK_POINTS) ?: return null
        val n = minOf(track.timesMillis.size, track.speedsKph.size)
        if (n < MIN_TRACK_POINTS) return null
        val rpm = try { sources.engineRpm(track) } catch (e: Exception) { null }
        var limits: FloatArray? = null
        if (withLimits) {
            onProgress(LearnStrings.lookingUpLimits(position, LIMIT_RIDES))
            limits = try { limitsPerPoint(track) } catch (e: Exception) { null }
        }
        return RideSample(track.timesMillis, track.speedsKph, track.leanDegrees, rpm, limits)
    }

    /**
     * The speed limit at every point of the track, or null when it could not be fetched. The host
     * is asked about a thinned copy of the track (a routing server will not map-match 20,000
     * points), and each segment's limit is spread over the original points it covers.
     */
    private fun limitsPerPoint(track: ModuleRideTrack): FloatArray? {
        val n = minOf(track.latitudes.size, track.longitudes.size)
        if (n < 2) return null
        val picked = thinnedIndices(n, LIMIT_QUERY_POINTS)
        val lats = DoubleArray(picked.size) { track.latitudes[picked[it]] }
        val lons = DoubleArray(picked.size) { track.longitudes[picked[it]] }
        val perSegment = sources.speedLimits(lats, lons) ?: return null
        return spreadSegmentLimits(n, picked, perSegment)
    }

    internal companion object {
        /** The rows describing a learned profile, for the report and for the stored summary. */
        fun facts(p: LearnedProfile): List<Pair<String, String>> {
            val out = ArrayList<Pair<String, String>>()
            fun add(label: String, value: String) { out.add(Pair(label, value)) }

            add(LearnStrings.FACT_RIDES, LearnStrings.rides(p.ridesUsed, p.kmUsed))
            val lean = p.maxLeanDeg
            add(LearnStrings.FACT_LEAN, if (lean != null) LearnStrings.degrees(lean) else LearnStrings.NOT_LEARNED)
            val lateral = p.lateralAccelMs2
            add(LearnStrings.FACT_CORNERING, if (lateral != null) LearnStrings.cornering(lateral) else LearnStrings.NOT_LEARNED)
            val accel = p.accelMs2
            add(LearnStrings.FACT_ACCEL, if (accel != null) LearnStrings.accel(accel) else LearnStrings.NOT_LEARNED)
            val brake = p.brakeMs2
            add(LearnStrings.FACT_BRAKE, if (brake != null) LearnStrings.accel(brake) else LearnStrings.NOT_LEARNED)
            val over = p.overLimitFactor
            add(LearnStrings.FACT_LIMITS, if (over != null) LearnStrings.againstLimit(over) else LearnStrings.LIMITS_NOT_LEARNED)

            if (p.rpmRidesUsed == 0) {
                add(LearnStrings.FACT_ENGINE, LearnStrings.ENGINE_GENERIC_NO_OBD)
            } else {
                val engine = LearnStrings.engine(p.idleRpm, p.maxRpm)
                add(LearnStrings.FACT_ENGINE, if (engine.isEmpty()) LearnStrings.ENGINE_GENERIC_NOT_ENOUGH else engine)
                val gears = p.gearRatios
                add(LearnStrings.FACT_GEARS, if (gears != null) LearnStrings.gears(gears.size, p.rpmRidesUsed) else LearnStrings.GEARS_NOT_LEARNED)
                val shifts = LearnStrings.shifts(p.shiftUpRpm, p.shiftDownRpm)
                add(LearnStrings.FACT_SHIFTS, if (shifts.isEmpty()) LearnStrings.SHIFTS_NOT_LEARNED else shifts)
            }
            return out
        }

        const val NEEDED = 3
        const val MIN_RIDE_KM = 10
        const val MIN_RIDE_METERS = MIN_RIDE_KM * 1000.0
        const val LIST_LIMIT = 200
        const val MAX_RIDES = 30
        /** Only the most recent rides are sent to the network for limits. */
        const val LIMIT_RIDES = 5
        const val TRACK_POINTS = 20_000
        const val MIN_TRACK_POINTS = 50
        const val LIMIT_QUERY_POINTS = 1500

        /** [count] indices spread evenly over 0 until [n], first and last included, strictly increasing. */
        fun thinnedIndices(n: Int, count: Int): IntArray {
            if (n <= count) return IntArray(n) { it }
            val out = IntArray(count)
            for (j in 0 until count) out[j] = ((j.toLong() * (n - 1)) / (count - 1)).toInt()
            return out
        }

        /**
         * Per-point limits from per-segment ones: point p takes the limit of the segment that
         * starts at or before it, the last point the limit of the last segment. Null when the
         * server's answer does not match the points it was asked about.
         */
        fun spreadSegmentLimits(n: Int, picked: IntArray, perSegment: FloatArray): FloatArray? {
            if (picked.size < 2 || perSegment.size != picked.size - 1) return null
            val out = FloatArray(n) { Float.NaN }
            var seg = 0
            for (p in 0 until n) {
                while (seg < perSegment.size - 1 && p >= picked[seg + 1]) seg++
                val v = perSegment[seg]
                out[p] = if (v.isFinite() && v > 0f) v else Float.NaN
            }
            return out
        }
    }
}
