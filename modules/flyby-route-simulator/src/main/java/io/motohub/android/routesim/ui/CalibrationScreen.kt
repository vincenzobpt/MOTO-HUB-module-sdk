// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// "Learn from my rides": the one place the module reads the rider's recorded rides, on request,
// and the one place the learned style is removed again.
package io.motohub.android.routesim.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableTarget
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.motohub.android.module.ModuleUi
import io.motohub.android.routesim.learn.LearnReport
import io.motohub.android.routesim.learn.Learner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CancellationException

/** The calibration page's state, and the two things it does: learn, and go back to the generic style. */
internal class CalibrationModel(private val env: RsEnv) {

    var busy by mutableStateOf(false)
    var progress by mutableStateOf("")

    /** The last run of this visit; null until the rider has asked for one. */
    var report by mutableStateOf<LearnReport?>(null)

    var notice by mutableStateOf("")
    var noticeIsError by mutableStateOf(false)

    private var job: Job? = null

    /** Counts the runs, so one that was left behind cannot report over the screen or store a profile. */
    @Volatile
    private var generation = 0

    /** Reads the rides (this takes a while and uses the network), and when something came out, keeps it and applies it. */
    fun learn() {
        if (busy) return
        generation += 1
        val mine = generation
        busy = true
        progress = Strings.LEARN_STARTING
        notice = ""
        report = null
        job = env.scope.launch(Dispatchers.Default) {
            try {
                val result = env.learner.learn({ text -> if (mine == generation) progress = text }, System.currentTimeMillis())
                if (mine != generation) return@launch
                val learned = result.profile
                var stored = true
                if (result.ok && learned != null) {
                    stored = try { env.learnedStore.save(learned); true } catch (_: Exception) { false }
                }
                withContext(Dispatchers.Main) {
                    if (mine != generation) return@withContext
                    if (result.ok && learned != null && stored) {
                        report = result
                        env.learned = learned
                    } else if (result.ok) {
                        report = null
                        notice = Strings.LEARN_SAVE_FAILED
                        noticeIsError = true
                    } else {
                        report = result
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (mine == generation) {
                    notice = Strings.unexpected(error.message)
                    noticeIsError = true
                }
            } finally {
                if (mine == generation) {
                    busy = false
                    progress = ""
                }
            }
        }
    }

    /** Removes the stored profile; the styles are the built-in ones again. */
    fun backToGeneric() {
        if (busy) return
        notice = ""
        env.scope.launch(Dispatchers.Default) {
            val ok = try { env.learnedStore.clear(); true } catch (_: Exception) { false }
            withContext(Dispatchers.Main) {
                if (ok) {
                    env.learned = null
                    report = null
                    notice = Strings.CLEARED
                    noticeIsError = false
                } else {
                    notice = Strings.CLEAR_FAILED
                    noticeIsError = true
                }
            }
        }
    }

    /** Stops waiting for a run that is under way; whatever it finds is dropped. */
    fun cancel() {
        generation += 1
        job?.cancel()
        job = null
        busy = false
        progress = ""
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun CalibrationScreen(env: RsEnv, model: CalibrationModel, onBack: () -> Unit) {
    val learned = env.learned
    val report = model.report
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        TopBar(Strings.CALIBRATE_TITLE, onBack) { }
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card {
                Txt(Strings.CALIBRATE_STATUS_TITLE, size = 18, weight = FontWeight.Bold)
                Txt(
                    StyleBase.indicator(learned), size = 16, weight = FontWeight.Bold,
                    color = if (StyleBase.isTuned(learned)) RsColors.Ok else RsColors.Text2
                )
                Txt(CalibrationView.status(learned, env.zone), color = RsColors.Dim, size = 14)
                Txt(Strings.calibrateIntro(Learner.NEEDED, Learner.MIN_RIDE_KM), color = RsColors.Dim, size = 14)
                Txt(Strings.CALIBRATE_NETWORK_NOTE, color = RsColors.Dim, size = 13)
            }

            BigButton(Strings.LEARN_BUTTON, Modifier.fillMaxWidth(), !model.busy, true) { model.learn() }
            if (model.busy) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Txt(model.progress, modifier = Modifier.weight(1f), color = RsColors.Accent, size = 15, weight = FontWeight.Bold, maxLines = 2)
                    TextAction(Strings.CANCEL, RsColors.Text2, true) { model.cancel() }
                }
            }
            if (model.notice.isNotEmpty()) {
                Txt(model.notice, color = if (model.noticeIsError) RsColors.Bad else RsColors.Ok, size = 14, maxLines = 3)
            }

            if (report != null) ReportCard(report)
            // What is in use, unless the summary above already is it.
            if ((report == null || !report.ok) && learned != null && StyleBase.isTuned(learned)) {
                Card {
                    Txt(Strings.IN_USE_TITLE, size = 18, weight = FontWeight.Bold)
                    for (row in CalibrationView.storedRows(learned)) FactLine(row)
                }
            }

            BigButton(Strings.BACK_TO_GENERIC, Modifier.fillMaxWidth(), learned != null && !model.busy, false) { model.backToGeneric() }
        }
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun ReportCard(report: LearnReport) {
    Card {
        Txt(
            CalibrationView.headline(report), size = if (report.ok) 18 else 15,
            weight = if (report.ok) FontWeight.Bold else FontWeight.Normal,
            color = if (report.ok) RsColors.Text else RsColors.Warn
        )
        if (report.ok) {
            for (row in CalibrationView.rows(report)) FactLine(row)
            Txt(Strings.LEARNED_APPLIED, color = RsColors.Dim, size = 13)
        } else {
            Txt(Strings.LEARN_NOT_APPLIED, color = RsColors.Dim, size = 13)
        }
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun FactLine(row: FactRow) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Txt(row.label, color = RsColors.Dim, size = 13)
        Txt(row.value, size = 16)
    }
}
