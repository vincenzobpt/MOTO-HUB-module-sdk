// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// What the calibration changes in the screens, kept out of the composables so it can be tested
// without a phone: which profile a style starts from, how the planner says so, and the rows of
// the summary of what was learned.
package io.motohub.android.routesim.ui

import io.motohub.android.routesim.learn.LearnReport
import io.motohub.android.routesim.learn.LearnStrings
import io.motohub.android.routesim.learn.LearnedProfile
import io.motohub.android.routesim.learn.withLearned
import io.motohub.android.routesim.sim.DrivingProfile
import io.motohub.android.routesim.sim.RideStyle
import java.time.ZoneId

/** One line of the summary: what, and how it came out. */
internal class FactRow(val label: String, val value: String)

/** Where a ride's riding style starts from: the built-in one, with the rider's learned numbers when there are any. */
internal object StyleBase {

    /** True when [learned] holds at least one number the styles can use. */
    fun isTuned(learned: LearnedProfile?): Boolean = learned != null && learned.hasAnything

    /** The profile the three style chips start from. */
    fun profile(style: RideStyle, learned: LearnedProfile?): DrivingProfile =
        DrivingProfile.generic(style).withLearned(learned)

    /** Whether [p] still is the profile its style starts from, so a new calibration may replace it. */
    fun isUntouched(p: DrivingProfile, style: RideStyle, learned: LearnedProfile?): Boolean =
        p.style == style && AdvancedParam.isUntouched(p, profile(style, learned))

    /** The line in the planner's style section. */
    fun indicator(learned: LearnedProfile?): String =
        if (isTuned(learned)) Strings.STYLE_TUNED else Strings.STYLE_GENERIC

    /** The About page's yes or no. */
    fun tunedAnswer(learned: LearnedProfile?): String =
        if (isTuned(learned)) Strings.YES else Strings.NO
}

internal object CalibrationView {

    /** "2026-10-03 14:05", in the phone's zone. */
    fun learnedOn(p: LearnedProfile, zone: ZoneId): String =
        WhenMath.formatDate(p.learnedAtMillis, zone) + " " + WhenMath.formatTime(p.learnedAtMillis, zone)

    /** The status line at the top of the calibration page. */
    fun status(learned: LearnedProfile?, zone: ZoneId): String {
        if (learned == null || !learned.hasAnything) return Strings.STATUS_GENERIC
        return Strings.learnedOn(learnedOn(learned, zone))
    }

    /** The report's facts, in the order the learner gave them; none when it stayed generic. */
    fun rows(report: LearnReport): List<FactRow> {
        val out = ArrayList<FactRow>()
        if (!report.ok) return out
        for (fact in report.facts) out.add(FactRow(fact.first, fact.second))
        return out
    }

    /**
     * The words above the rows: that it worked, or why it did not (for instance "3 rides of at
     * least 10 km are needed, you have 2").
     */
    fun headline(report: LearnReport): String =
        if (report.ok) Strings.LEARNED_TITLE else report.message

    /**
     * What is in use when no run has been made since the page opened: the rides it came from, and
     * the engine line, which says outright that engine speed is generic when no ride had an OBD adapter.
     */
    fun storedRows(p: LearnedProfile): List<FactRow> {
        val out = ArrayList<FactRow>()
        for (fact in io.motohub.android.routesim.learn.Learner.facts(p)) out.add(FactRow(fact.first, fact.second))
        return out
    }
}
