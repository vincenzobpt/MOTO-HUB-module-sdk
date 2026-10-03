// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.routesim.ui

import io.motohub.android.routesim.learn.LearnReport
import io.motohub.android.routesim.learn.LearnStrings
import io.motohub.android.routesim.learn.LearnedProfile
import io.motohub.android.routesim.sim.DrivingProfile
import io.motohub.android.routesim.sim.RideStyle
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationLogicTest {

    private fun learned(
        rpmRides: Int = 2, accel: Double? = 2.8, idle: Double? = 1150.0, max: Double? = 9800.0, lean: Double? = 44.0,
    ) = LearnedProfile(
        learnedAtMillis = 1_760_000_000_000L, ridesUsed = 6, kmUsed = 240.0, rpmRidesUsed = rpmRides,
        maxLeanDeg = lean, lateralAccelMs2 = 4.0, accelMs2 = accel, brakeMs2 = 3.6, overLimitFactor = 1.05,
        gearRatios = null, idleRpm = idle, maxRpm = max, shiftUpRpm = null, shiftDownRpm = null,
    )

    private fun empty() = LearnedProfile(0L, 3, 40.0, 0, null, null, null, null, null, null, null, null, null, null)

    // ---- which profile a style starts from

    @Test
    fun withoutLearningAStyleStartsFromTheGenericProfile() {
        for (style in RideStyle.values()) {
            val p = StyleBase.profile(style, null)
            val g = DrivingProfile.generic(style)
            assertEquals(g.accelMs2, p.accelMs2, 0.0)
            assertEquals(g.maxLeanDeg, p.maxLeanDeg, 0.0)
            assertEquals(style, p.style)
        }
    }

    @Test
    fun learningBecomesTheBaseOfAllThreeStylesAndTheyKeepTheirOrder() {
        val l = learned()
        val calm = StyleBase.profile(RideStyle.CALM, l)
        val normal = StyleBase.profile(RideStyle.NORMAL, l)
        val sporty = StyleBase.profile(RideStyle.SPORTY, l)
        assertEquals(2.8, normal.accelMs2, 1e-9)
        assertEquals(44.0, normal.maxLeanDeg, 1e-9)
        assertTrue(calm.accelMs2 < normal.accelMs2)
        assertTrue(normal.accelMs2 < sporty.accelMs2)
        assertEquals(RideStyle.CALM, calm.style)
        assertEquals(RideStyle.SPORTY, sporty.style)
    }

    @Test
    fun aLearnedProfileWithNothingInItIsNotTuned() {
        assertFalse(StyleBase.isTuned(null))
        assertFalse(StyleBase.isTuned(empty()))
        assertTrue(StyleBase.isTuned(learned()))
    }

    // ---- the indicator

    @Test
    fun theIndicatorSaysWhetherTheStylesAreTuned() {
        assertEquals("Style: tuned to your rides", StyleBase.indicator(learned()))
        assertEquals("Generic style", StyleBase.indicator(null))
        assertEquals("Generic style", StyleBase.indicator(empty()))
        assertEquals("Yes", StyleBase.tunedAnswer(learned()))
        assertEquals("No", StyleBase.tunedAnswer(null))
    }

    // ---- an edited profile survives a new calibration, an untouched one follows it

    @Test
    fun theBaseProfileCountsAsUntouched() {
        val l = learned()
        for (style in RideStyle.values()) {
            assertTrue(StyleBase.isUntouched(StyleBase.profile(style, l), style, l))
            assertTrue(StyleBase.isUntouched(DrivingProfile.generic(style), style, null))
        }
    }

    @Test
    fun theGenericProfileIsNoLongerUntouchedOnceSomethingWasLearned() {
        val g = DrivingProfile.generic(RideStyle.NORMAL)
        assertFalse(StyleBase.isUntouched(g, RideStyle.NORMAL, learned()))
    }

    @Test
    fun anEditedProfileIsNotUntouched() {
        val l = learned()
        val edited = AdvancedParam.ACCEL.nudge(StyleBase.profile(RideStyle.NORMAL, l), 1)
        assertFalse(StyleBase.isUntouched(edited, RideStyle.NORMAL, l))
    }

    @Test
    fun aProfileOfAnotherStyleIsNotUntouched() {
        assertFalse(StyleBase.isUntouched(DrivingProfile.generic(RideStyle.CALM), RideStyle.SPORTY, null))
    }

    @Test
    fun theAdvancedSectionResetsToTheGivenBase() {
        val base = StyleBase.profile(RideStyle.SPORTY, learned())
        assertTrue(AdvancedParam.isUntouched(base, base))
        assertFalse(AdvancedParam.isUntouched(DrivingProfile.generic(RideStyle.SPORTY), base))
        // The one-argument form still compares with the generic profile.
        assertTrue(AdvancedParam.isUntouched(DrivingProfile.generic(RideStyle.SPORTY)))
        assertFalse(AdvancedParam.isUntouched(base))
    }

    // ---- the summary

    @Test
    fun aSuccessfulReportBecomesOneRowPerFactInOrder() {
        val facts = listOf(
            Pair(LearnStrings.FACT_RIDES, "6 rides, 240 km"),
            Pair(LearnStrings.FACT_LEAN, "44°"),
            Pair(LearnStrings.FACT_ENGINE, LearnStrings.ENGINE_GENERIC_NO_OBD),
        )
        val report = LearnReport(true, 6, 3, "", facts, learned(rpmRides = 0))
        val rows = CalibrationView.rows(report)
        assertEquals(3, rows.size)
        assertEquals("Rides used", rows[0].label)
        assertEquals("6 rides, 240 km", rows[0].value)
        assertEquals("Maximum lean", rows[1].label)
        assertEquals("Engine speed", rows[2].label)
        assertEquals("generic (no ride with an OBD adapter)", rows[2].value)
        assertEquals("What was learned", CalibrationView.headline(report))
    }

    @Test
    fun aReportThatLearnedNothingShowsItsMessageAndNoRows() {
        val message = LearnStrings.needMoreRides(3, 10, 2)
        val report = LearnReport(false, 2, 3, message, ArrayList(), null)
        assertTrue(CalibrationView.rows(report).isEmpty())
        assertEquals("3 rides of at least 10 km are needed, you have 2", CalibrationView.headline(report))
    }

    @Test
    fun aFailedReportShowsNoRowsEvenIfItCarriedSome() {
        val report = LearnReport(false, 3, 3, LearnStrings.NOTHING_LEARNED, listOf(Pair("a", "b")), null)
        assertTrue(CalibrationView.rows(report).isEmpty())
        assertEquals(LearnStrings.NOTHING_LEARNED, CalibrationView.headline(report))
    }

    // ---- what is stored

    @Test
    fun theStoredSummaryNamesTheRidesAndSaysEngineSpeedIsGenericWithoutObd() {
        val rows = CalibrationView.storedRows(learned(rpmRides = 0, idle = null, max = null))
        assertEquals("6 rides, 240 km", rows[0].value)
        val engine = rows.first { it.label == "Engine speed" }
        assertEquals("generic (no ride with an OBD adapter)", engine.value)
        // The full summary, not only the first and last line: lean and acceleration are there too.
        assertTrue(rows.any { it.label == "Maximum lean" })
        assertTrue(rows.none { it.label == "Gears" })
    }

    @Test
    fun theStoredSummaryGivesTheEngineNumbersWhenThereWereObdRides() {
        val rows = CalibrationView.storedRows(learned(rpmRides = 2))
        assertEquals("idle 1150 rpm, up to 9800 rpm", rows.first { it.label == "Engine speed" }.value)
    }

    @Test
    fun theStoredSummaryAdmitsWhenObdRidesGaveNoEngineNumbers() {
        val rows = CalibrationView.storedRows(learned(rpmRides = 2, idle = null, max = null))
        assertEquals("generic (not enough engine data from your OBD rides)", rows.first { it.label == "Engine speed" }.value)
    }

    // ---- the date

    @Test
    fun theLearnDateIsShownInThePhonesZone() {
        val l = learned()
        assertEquals("2025-10-09 08:53", CalibrationView.learnedOn(l, ZoneOffset.UTC))
        assertEquals("2025-10-09 10:53", CalibrationView.learnedOn(l, ZoneOffset.ofHours(2)))
        assertEquals("Tuned to your rides, learned on 2025-10-09 08:53", CalibrationView.status(l, ZoneOffset.UTC))
    }

    @Test
    fun theStatusSaysNothingWasLearnedWhenNothingWas() {
        assertEquals("Generic style. Nothing has been learned yet.", CalibrationView.status(null, ZoneOffset.UTC))
        assertEquals("Generic style. Nothing has been learned yet.", CalibrationView.status(empty(), ZoneOffset.UTC))
    }
}
