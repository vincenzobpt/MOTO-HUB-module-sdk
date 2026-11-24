// SPDX-License-Identifier: MPL-2.0
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
// of the MPL was not distributed with this file, you can obtain one at https://mozilla.org/MPL/2.0/.
package io.motohub.paddock.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableTarget
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.motohub.android.module.ModuleUi

/**
 * What is shared with whom, what is kept where, what the relay sees, and the cryptography, in
 * plain words. Every sentence here must stay true of the code (DESIGN.md §6): change them together.
 */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun PrivacyScreen(onBack: () -> Unit) {
    Page("Privacy & how it works", onBack) {
        RelayDiagram(Modifier.fillMaxWidth())
        Spacer(Modifier.height(S.section))
        Txt("Your rides stay between your riders", Ty.TITLE, P.text)
        Spacer(Modifier.height(10.dp))
        Txt(
            "Paddock has no account. The riders' phones send each other end-to-end encrypted messages through " +
                "a public Reticulum relay. MOTO-HUB's only part is a doorbell for riders whose app is in the background.",
            Ty.BODY, P.text2
        )
        Spacer(Modifier.height(20.dp))
        InfoBlock(
            PI.USERS, P.ok, P.okSoft, "What is shared, and with whom",
            "Your name, colour and bike go only to the riders of your rooms, and a chat message only to the riders of its room. Your position goes only to " +
                "the one room you ride with, every 10 seconds, and only from Start ride to Stop ride. Other rooms get nothing."
        )
        InfoBlock(
            PI.PHONES, P.ok, P.okSoft, "What is kept, and where",
            "On this phone: your rider ID, your profile, the relay you chose, and your rooms with their riders. A MOTO-HUB " +
                "backup file (.mhb) you make includes your rider ID too. When you leave a room or its leader " +
                "deletes it, Paddock deletes it with its riders' names, bikes and positions; Reticulum's routing cache keeps only " +
                "their Paddock addresses (random codes, no names) until you forget your rider ID."
        )
        InfoBlock(
            PI.PHONES, P.ok, P.okSoft, "The doorbell",
            "When a rider cannot be reached because MOTO-HUB is in the background on their phone, MOTO-HUB's push " +
                "service asks Google to ring it: \"a message\", \"a call\" or \"a rider asking to join\", with a random room code. " +
                "No names, no text, no positions, and it keeps nothing. Your phone's push code goes only to the riders of your " +
                "rooms and, if you lead a room, into its QR code and link."
        )
        InfoBlock(
            PI.RELAY, P.ok, P.okSoft, "What the relay sees",
            "The relay forwards encrypted messages that it cannot read: it never sees names, bikes or positions. " +
                "Like any internet service, it does see your phone's IP address and that some traffic is flowing."
        )
        InfoBlock(
            PI.MAILBOX, P.ok, P.okSoft, "Messages for riders who are offline",
            "A chat message a rider could not receive straight away waits on a public propagation node, encrypted so " +
                "that only that rider can read it. The node sees who it is for, its size and when it arrived, not the text."
        )
        InfoBlock(
            PI.QR, P.accent, P.accentSoft, "Who can join",
            "Anyone who has a room's QR code or link can ask to join it, so show it only to the people you ride with. " +
                "The leader's phone lets them in. The leader can delete the room at any time, and then the code stops working."
        )
        InfoBlock(
            PI.KEY, P.ok, P.okSoft, "Your rider ID",
            "A pair of keys made on this phone. The public key goes to your rooms, so riders know a message really " +
                "comes from you. Paddock never sends the secret key anywhere: it leaves this phone only if you back it up yourself, in Settings, where Forget my rider ID also deletes it."
        )
        InfoBlock(
            PI.LOCK, P.ok, P.okSoft, "The cryptography",
            "Keys are agreed with X25519, messages are encrypted with AES-256 and signed with Ed25519: the " +
                "cryptography of Reticulum and LXMF.\n\n" +
                "In a post-quantum room (the default) every message, quick message and position is first sealed with " +
                "the room's own key (AES-256 with HMAC-SHA256). That key is made on the leader's phone and travels only " +
                "in the room's code and link, never through Reticulum, so a future quantum computer that breaks X25519 " +
                "still finds a sealed message. Calls are not sealed this way."
        )
        Spacer(Modifier.height(S.section))
        SectionLabel("Built on open source")
        Txt(
            "Paddock builds on Reticulum and LXMF by Mark Qvist, and on Columba by torlando-tech. Thank you. " +
                "Paddock itself is free software under the Mozilla Public License 2.0.",
            Ty.SMALL, P.dim
        )
    }
}
