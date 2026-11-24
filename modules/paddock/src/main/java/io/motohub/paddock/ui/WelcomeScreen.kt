// SPDX-License-Identifier: MPL-2.0
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
// of the MPL was not distributed with this file, you can obtain one at https://mozilla.org/MPL/2.0/.
package io.motohub.paddock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableTarget
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.motohub.android.module.ModuleUi

/**
 * First open, before there is a rider ID: what Paddock is in one line, the four privacy facts,
 * and the one thing to do. The picture runs under the status bar.
 */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun WelcomeScreen(onMakeId: () -> Unit, onRestore: () -> Unit, onHowItWorks: () -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().height(300.dp)) {
                HeroRoad(Modifier.fillMaxSize())
                Box(Modifier.statusBarsPadding().padding(12.dp)) {
                    RoundIconButton(PI.BACK, "Back", onBack, bg = P.glass, outlined = false)
                }
            }
            Column(Modifier.widthIn(max = MAX_W).fillMaxWidth().padding(horizontal = S.gutter)) {
                Txt("PADDOCK", Ty.LABEL, P.accent)
                Spacer(Modifier.height(8.dp))
                Txt("Ride together, privately.", Ty.DISPLAY, P.text)
                Spacer(Modifier.height(10.dp))
                Txt("Keep a room for each group you ride with, and see each other on the NAV map while you ride.", Ty.BODY, P.text2)
                Spacer(Modifier.height(S.section))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    InfoBlock(
                        PI.NO_ACCOUNT, P.ok, P.okSoft, "No account",
                        "No email, no phone number, no password. Your rider ID is made on this phone."
                    )
                    InfoBlock(
                        PI.PHONES, P.ok, P.okSoft, "Phones talk to each other",
                        "Messages go from phone to phone, encrypted. MOTO-HUB only rings a rider's phone when their app is asleep, " +
                            "and never sees names, messages or positions."
                    )
                    InfoBlock(
                        PI.LOCK, P.ok, P.okSoft, "End-to-end encrypted",
                        "Only the riders of your rooms can read your name and bike, and only the room you ride with sees where you are."
                    )
                    InfoBlock(
                        PI.RELAY, P.ok, P.okSoft, "A public community network",
                        "Messages travel over Reticulum, an open network. Its relays pass on encrypted data they cannot read."
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }
        Column(
            Modifier.widthIn(max = MAX_W).fillMaxWidth().padding(horizontal = S.gutter).padding(top = 8.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            PrimaryButton("Make my rider ID", PI.KEY, true, onClick = onMakeId)
            Spacer(Modifier.height(2.dp))
            TextLink("I already have a rider ID", onClick = onRestore)
            TextLink("How it works", onClick = onHowItWorks)
        }
    }
}
