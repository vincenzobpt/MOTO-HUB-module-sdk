// SPDX-License-Identifier: MPL-2.0
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
// of the MPL was not distributed with this file, you can obtain one at https://mozilla.org/MPL/2.0/.
package io.motohub.paddock.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableTarget
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.motohub.android.module.ModuleUi
import io.motohub.paddock.engine.PaddockController

/**
 * Backing up the rider ID: first what the text is and how to keep it, then - only on request -
 * the text itself, to copy or share. It is the private key, so it is never on screen by default.
 */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun BackupScreen(controller: PaddockController, onToast: (ToastMessage) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var shown by remember { mutableStateOf(false) }
    val key = remember(shown) { if (shown) controller.exportIdentity() else null }

    Page("Back up my rider ID", onBack) {
        IconTile(PI.KEY, P.ok, P.okSoft)
        Spacer(Modifier.height(16.dp))
        Txt("This text is your rider ID", Ty.TITLE, P.text)
        Spacer(Modifier.height(16.dp))
        InfoBlock(
            PI.PHONES, P.ok, P.okSoft, "Move it, or get it back",
            "With it you can use your rider ID on a new phone, or get it back after a reset."
        )
        InfoBlock(
            PI.LOCK, P.warn, P.warnSoft, "Keep it like a password",
            "Anyone who has this text can act as you in Paddock. Keep it in a password manager, and never post it."
        )
        InfoBlock(
            PI.SHIELD, P.ok, P.okSoft, "Already in MOTO-HUB's backup",
            "MOTO-HUB's own backup file (.mhb) contains your rider ID too."
        )
        Spacer(Modifier.height(S.section))
        when {
            !shown -> PrimaryButton("Show my rider ID", PI.KEY, true) { shown = true }
            key == null -> Notice(
                PI.WARNING, P.warn, P.warnSoft,
                "There is no rider ID on this phone yet. Paddock makes it the first time it starts."
            )
            else -> {
                SectionLabel("Your rider ID")
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(P.surface).padding(16.dp)) {
                    // Read-only text, so the rider can also select part of it by hand.
                    BasicTextField(
                        value = inBlocks(key),
                        onValueChange = { },
                        readOnly = true,
                        textStyle = TextStyle(color = P.text, fontSize = 17.sp, lineHeight = 28.sp, fontFamily = FontFamily.Monospace),
                        cursorBrush = SolidColor(P.accent),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Txt(
                    "The spaces are only there to make it easier to read: Paddock ignores them when you restore.",
                    Ty.SMALL, P.dim, Modifier.padding(start = 4.dp, top = 8.dp)
                )
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton("Copy", PI.PASTE, modifier = Modifier.weight(1f)) {
                        if (copySensitive(context, key)) {
                            onToast(ToastMessage("Copied. Paste it somewhere safe, then clear your clipboard.", PI.CHECK, P.ok))
                        }
                    }
                    SecondaryButton("Share", PI.SHARE, modifier = Modifier.weight(1f)) { shareKey(context, key) }
                }
            }
        }
    }
}

/**
 * Putting a saved rider ID on this phone. From Settings it replaces the one in use, so it asks
 * first; from the welcome screen there is none yet, and the profile comes next.
 */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun RestoreScreen(controller: PaddockController, slot: BackSlot, replacing: Boolean, onDone: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var asking by remember { mutableStateOf(false) }
    val restore: () -> Unit = {
        val problem = controller.importIdentity(text)
        if (problem == null) onDone() else error = problem
    }

    Box(Modifier.fillMaxSize()) {
        Page(
            "Restore a rider ID", onBack,
            bottom = {
                PrimaryButton("Restore", PI.KEY, text.isNotBlank()) {
                    error = null
                    if (replacing) asking = true else restore()
                }
            }
        ) {
            IconTile(PI.KEY, P.ok, P.okSoft)
            Spacer(Modifier.height(16.dp))
            Txt("Bring back a saved rider ID", Ty.TITLE, P.text)
            Spacer(Modifier.height(8.dp))
            Txt(
                "Paste the text you saved with Back up my rider ID. Paddock reads it on this phone and sends it nowhere.",
                Ty.BODY, P.text2
            )
            if (replacing) {
                Spacer(Modifier.height(16.dp))
                Notice(
                    PI.WARNING, P.warn, P.warnSoft,
                    "This replaces the rider ID on this phone, and removes from it the rooms of the old ID. The rooms themselves stay as they are for their other riders."
                )
            }
            Spacer(Modifier.height(20.dp))
            Field("Rider ID", text, { text = it; error = null }, "Paste the rider ID text", 400)
            Spacer(Modifier.height(10.dp))
            SecondaryButton("Paste", PI.PASTE) { text = clipboardText(context); error = null }
            val shownError = error
            if (shownError != null) {
                Spacer(Modifier.height(12.dp))
                Notice(PI.WARNING, P.warn, P.warnSoft, shownError)
            }
            Spacer(Modifier.height(16.dp))
            Txt(
                if (replacing) "Then close MOTO-HUB completely and open it again: Paddock starts with the restored rider ID."
                else "Next, you choose the name, colour and bike that riders see: they are not part of the rider ID.",
                Ty.SMALL, P.dim
            )
        }
        if (replacing) ConfirmSheet(
            asking, slot, PI.KEY, danger = true,
            title = "Replace your rider ID?",
            body = "The rider ID on this phone is replaced by the one you pasted. Without a backup, the current one " +
                "cannot be brought back, and you leave your rooms.",
            confirm = "Replace",
            onDismiss = { asking = false },
            onConfirm = { asking = false; restore() }
        )
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun IconTile(icon: PI, tint: Color, soft: Color) {
    Box(Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(soft), contentAlignment = Alignment.Center) {
        PIcon(icon, tint, 30.dp)
    }
}

/** "ABCDEFGHIJKLMNOP…" → "ABCDEFGH IJKLMNOP …": blocks of 8 are easier to check by eye. */
private fun inBlocks(text: String): String {
    val out = StringBuilder(text.length + text.length / 8)
    for (i in 0 until text.length) {
        if (i > 0 && i % 8 == 0) out.append(' ')
        out.append(text[i])
    }
    return out.toString()
}

/** Copies [text] marked sensitive, so Android does not show it in the clipboard preview. */
private fun copySensitive(context: Context, text: String): Boolean = try {
    val clip = ClipData.newPlainText("Paddock rider ID", text)
    clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    val manager = context.getSystemService(ClipboardManager::class.java)
    manager?.setPrimaryClip(clip)
    manager != null
} catch (e: RuntimeException) {
    false
}

private fun shareKey(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "My Paddock rider ID")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    val chooser = Intent.createChooser(send, "Save your rider ID")
    if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(chooser)
    } catch (e: RuntimeException) {
        // No app can take text: Copy still works.
    }
}
