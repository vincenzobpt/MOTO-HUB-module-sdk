// SPDX-License-Identifier: MPL-2.0
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
// of the MPL was not distributed with this file, you can obtain one at https://mozilla.org/MPL/2.0/.
package io.motohub.paddock.ui

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableTarget
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.motohub.android.module.ModuleUi
import io.motohub.paddock.engine.PaddockController
import io.motohub.paddock.engine.PaddockEvent
import io.motohub.paddock.engine.Room
import io.motohub.paddock.qr.QrScanner
import io.motohub.paddock.qr.ScannerStatus
import io.motohub.paddock.qr.hasCameraPermission
import io.motohub.paddock.qr.requestCameraPermission
import kotlin.math.min
import kotlin.math.sin

private const val NOT_A_ROOM = "That's not a Paddock room code. Ask for the code on the room's Invite riders screen in Paddock."

/**
 * The camera, full screen, with a viewfinder: point it at the leader's code. A link sent in a
 * message can be pasted instead. A good code hands over to the new room's screen as soon as the
 * engine lists it (phase JOINING), which then says that the leader is being asked.
 */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun JoinScreen(controller: PaddockController, rooms: List<Room>, slot: BackSlot, paste: Boolean, onJoined: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasCameraPermission(context)) }
    var status by remember { mutableStateOf(ScannerStatus.STARTING) }
    var problem by remember { mutableStateOf<String?>(null) }
    var joining by remember { mutableStateOf(false) }
    var pasting by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }
    var attempt by remember { mutableIntStateOf(0) }
    // The rooms there were when the code was read: the one that appears after it is the new one.
    var before by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(rooms, before) {
        val known = before ?: return@LaunchedEffect
        for (r in rooms) if (!known.contains(r.id)) {
            onJoined(r.id)
            return@LaunchedEffect
        }
    }

    // The permission answer comes back to the activity, not here: look again until it is given.
    LaunchedEffect(granted) {
        while (!granted) {
            kotlinx.coroutines.delay(700)
            granted = hasCameraPermission(context)
        }
    }
    LaunchedEffect(controller) {
        controller.events.collect {
            if (it is PaddockEvent.JoinFailed || it is PaddockEvent.Error) {
                joining = false
                before = null
            }
        }
    }
    LaunchedEffect(problem) {
        if (problem != null) {
            kotlinx.coroutines.delay(6000)
            problem = null
        }
    }
    val tryJoin: (String) -> Unit = { text ->
        if (!joining) {
            val link = extractLink(text)
            val known = roomIds(rooms)
            if (link != null && controller.joinRoom(link)) {
                before = known
                joining = true
                problem = null
                pasting = false
            } else {
                problem = NOT_A_ROOM
            }
        }
    }

    // Chosen on Home: the link alone, without the camera or its permission.
    if (paste) {
        Page("Paste a room link", onBack) {
            Spacer(Modifier.height(8.dp))
            if (joining) {
                WaitingRow("Room link read. Joining…", "Paddock is contacting the room's leader.")
                Spacer(Modifier.height(16.dp))
            }
            PasteForm(pasted, { pasted = it; problem = null }, problem, joining, { pasted = clipboardText(context).let { extractLink(it) ?: it }; problem = null }) { tryJoin(pasted) }
        }
        return
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        key(attempt) { QrScanner(Modifier.fillMaxSize(), onResult = tryJoin, onStatus = { status = it }) }
        Viewfinder(Modifier.fillMaxSize())

        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            RoundIconButton(PI.CLOSE, "Close", onBack, tint = Color.White, bg = P.glass, outlined = false)
            Txt("Join a room", Ty.TITLE, Color.White, Modifier.weight(1f), maxLines = 1)
        }

        Column(
            Modifier.align(Alignment.BottomCenter).widthIn(max = MAX_W).fillMaxWidth().navigationBarsPadding()
                .padding(horizontal = S.gutter, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val shownProblem = problem
            when {
                joining -> WaitingRow("Room code read. Joining…", "Paddock is contacting the room's leader.")
                shownProblem != null -> Notice(PI.WARNING, P.warn, P.surface, shownProblem)
                status == ScannerStatus.NO_CAMERA ->
                    Notice(PI.CAMERA, P.warn, P.surface, "This phone has no camera Paddock can use. Paste the room link instead.")
                status == ScannerStatus.ERROR -> {
                    Notice(PI.WARNING, P.warn, P.surface, "The camera could not start. Another app may be using it.")
                    SecondaryButton("Try the camera again", PI.CAMERA) { attempt++ }
                }
                status == ScannerStatus.STARTING -> WaitingRow("Starting the camera…", null)
                else -> Txt(
                    "Point the camera at the room's QR code.",
                    Ty.HEADING, Color.White, Modifier.fillMaxWidth(), align = TextAlign.Center
                )
            }
            SecondaryButton("Paste a link instead", PI.LINK) { pasting = true }
        }

        if (!granted || status == ScannerStatus.NO_PERMISSION) CameraPermission(context, onBack) { pasting = true }

        Sheet(pasting, slot, "Paste a room link", { pasting = false }) {
            PasteForm(pasted, { pasted = it; problem = null }, problem, joining, { pasted = clipboardText(context).let { extractLink(it) ?: it }; problem = null }) { tryJoin(pasted) }
        }
    }
}

/** Where the link comes from, the field, and Paste / Join. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun PasteForm(pasted: String, onChange: (String) -> Unit, problem: String?, joining: Boolean, onPaste: () -> Unit, onJoin: () -> Unit) {
    Txt(
        "Ask a rider of the room to open Invite riders, tap Share link and send it to you. Copy the whole message and paste it here.",
        Ty.SMALL, P.dim
    )
    Spacer(Modifier.height(16.dp))
    Field("Room link", pasted, onChange, "paddock://join?…", 2000)
    if (problem != null) {
        Spacer(Modifier.height(12.dp))
        Notice(PI.WARNING, P.warn, P.warnSoft, problem)
    }
    Spacer(Modifier.height(18.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SecondaryButton("Paste", PI.PASTE, modifier = Modifier.weight(1f), onClick = onPaste)
        PrimaryButton("Join", PI.CHECK, pasted.isNotBlank() && !joining, Modifier.weight(1f), onClick = onJoin)
    }
}

/** Why the camera, and the two ways to allow it. Android stops asking once a rider said never. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun CameraPermission(context: Context, onBack: () -> Unit, onPaste: () -> Unit) {
    Box(Modifier.fillMaxSize().background(P.bg)) {
        Page("Join a room", onBack, backIcon = PI.CLOSE) {
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(Modifier.size(112.dp).clip(CircleShape).background(P.accentSoft), contentAlignment = Alignment.Center) {
                    PIcon(PI.CAMERA, P.accent, 56.dp)
                }
            }
            Spacer(Modifier.height(24.dp))
            Txt("Allow the camera", Ty.TITLE, P.text)
            Spacer(Modifier.height(8.dp))
            Txt(
                "Paddock uses the camera only to read the room's QR code. The picture is read on this phone, " +
                    "and it is never recorded or sent.",
                Ty.BODY, P.text2
            )
            Spacer(Modifier.height(S.section))
            PrimaryButton("Allow camera", PI.CAMERA, true) { requestCameraPermission(context) }
            Spacer(Modifier.height(10.dp))
            SecondaryButton("Open app settings", PI.GEAR) { openAppSettings(context) }
            Spacer(Modifier.height(10.dp))
            Txt(
                "If Android does not ask, open the app settings, tap Permissions, then Camera, and choose Allow only while using the app.",
                Ty.SMALL, P.dim
            )
            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TextLink("Paste a link instead", onClick = onPaste)
            }
        }
    }
}

/** A dimmed frame with a clear square in it, orange corners, and a line that sweeps the square. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun Viewfinder(modifier: Modifier) {
    val clock = rememberSeconds()
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val side = min(w * 0.72f, 300.dp.toPx())
        val left = (w - side) / 2f
        val top = h * 0.42f - side / 2f
        val dim = Color(0x9E05080C)
        val none = CornerRadius(0f)
        // Four bands around the square: the square itself stays clear.
        drawRoundRect(dim, Offset(0f, 0f), Size(w, top), none)
        drawRoundRect(dim, Offset(0f, top + side), Size(w, h - top - side), none)
        drawRoundRect(dim, Offset(0f, top), Size(left, side), none)
        drawRoundRect(dim, Offset(left + side, top), Size(w - left - side, side), none)

        val len = side * 0.16f
        val r = 14.dp.toPx()
        val stroke = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        for (corner in 0..3) {
            val sx = if (corner == 0 || corner == 3) 1f else -1f
            val sy = if (corner < 2) 1f else -1f
            val cx = if (sx > 0) left else left + side
            val cy = if (sy > 0) top else top + side
            drawPath(Path().apply {
                moveTo(cx, cy + sy * len)
                lineTo(cx, cy + sy * r)
                cubicTo(cx, cy + sy * r * 0.45f, cx + sx * r * 0.45f, cy, cx + sx * r, cy)
                lineTo(cx + sx * len, cy)
            }, P.accent, style = stroke)
        }
        val sweep = 0.5f + 0.5f * sin(clock.floatValue * 1.8f)
        val y = top + side * (0.1f + 0.8f * sweep)
        drawRoundRect(P.accent.copy(alpha = 0.16f), Offset(left + side * 0.08f, y - 9.dp.toPx()), Size(side * 0.84f, 18.dp.toPx()), CornerRadius(9.dp.toPx()))
        drawRoundRect(P.accent.copy(alpha = 0.85f), Offset(left + side * 0.08f, y - 1.dp.toPx()), Size(side * 0.84f, 2.dp.toPx()), CornerRadius(1.dp.toPx()))
    }
}

/** The room link inside whatever was pasted or scanned: the share text wraps it in a sentence. */
internal fun extractLink(text: String): String? {
    val start = text.indexOf("paddock://join?")
    if (start < 0) return null
    var end = start
    while (end < text.length && !Character.isWhitespace(text[end])) end++
    return text.substring(start, end)
}

internal fun clipboardText(context: Context): String = try {
    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
    if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(context).toString() else ""
} catch (e: RuntimeException) {
    ""
}

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: RuntimeException) {
        // No settings screen to open: the sentence under the button still says where to go.
    }
}
