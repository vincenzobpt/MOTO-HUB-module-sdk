// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.tomtom

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableTarget
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.motohub.android.module.ModuleUi
import io.motohub.android.module.MotoHubModuleHost
import java.text.DateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale

/** An incident told in plain words. */
internal object Words {

    /** "3.4 km", or "238 m" under a kilometre. */
    fun length(meters: Double): String =
        if (meters < 995) "${Math.round(meters / 10.0) * 10} m" else String.format(Locale.ROOT, "%.1f km", meters / 1000.0)

    /** "+12 min" as the delay of an incident, "+1 h 5 min" past an hour. */
    fun delay(seconds: Int): String {
        val minutes = maxOf(1, (seconds + 30) / 60)
        return if (minutes < 60) "+$minutes min" else "+${minutes / 60} h ${minutes % 60} min"
    }

    /** A TomTom timestamp ("2026-10-17T15:37:00Z") in the phone's own time and language; null when it cannot be read. */
    fun moment(iso: String?): String? {
        val instant = iso?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date.from(instant))
    }
}

/** One incident: what it is, where, and the facts TomTom has. Opened from a pin on the NAV map. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun IncidentPage(host: MotoHubModuleHost, incident: Incident?, onBack: () -> Unit) {
    host.ui.Backdrop(onBack) {
        Column(
            Modifier.fillMaxSize().background(Ink.Ground).verticalScroll(rememberScrollState())
                .statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassKey("‹", false, onBack)
            }
            Spacer(Modifier.height(10.dp))
            if (incident == null) {
                Txt("This incident is no longer on the map.", 17.sp, Ink.Muted, FontWeight.Medium, Modifier.padding(4.dp), 3)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt(IncidentLook.symbol(incident.category), 44.sp, Ink.Text, FontWeight.Normal, Modifier, 1)
                    Spacer(Modifier.width(14.dp))
                    Txt(IncidentLook.title(incident.category), 26.sp, Ink.Text, FontWeight.Bold, Modifier.weight(1f), 2)
                }
                val where = listOfNotNull(
                    incident.roads.joinToString(", ").takeIf { it.isNotEmpty() },
                    listOfNotNull(incident.from, incident.to).takeIf { it.isNotEmpty() }?.joinToString(" → ")
                )
                where.forEachIndexed { i, line ->
                    Txt(line, if (i == 0) 18.sp else 15.sp, if (i == 0) Ink.Text else Ink.Muted, if (i == 0) FontWeight.SemiBold else FontWeight.Normal, Modifier.padding(start = 4.dp, top = 8.dp), 3)
                }
                Spacer(Modifier.height(18.dp))
                LinesCard {
                    InfoLine("Delay", incident.delaySeconds?.let { Words.delay(it) })
                    InfoLine("Length", incident.lengthMeters?.let { Words.length(it) })
                    InfoLine("Since", Words.moment(incident.start))
                    InfoLine("Until", Words.moment(incident.end))
                    InfoLine("Magnitude", IncidentLook.magnitudeWords(incident.magnitude))
                }
            }
            Spacer(Modifier.height(24.dp))
            Txt("Traffic data © TomTom", 12.sp, Ink.Faint, FontWeight.Normal, Modifier.padding(horizontal = 4.dp), 1)
            Spacer(Modifier.height(28.dp))
        }
    }
}
