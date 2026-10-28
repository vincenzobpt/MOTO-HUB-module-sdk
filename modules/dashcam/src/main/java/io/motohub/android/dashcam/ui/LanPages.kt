// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.dashcam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableTarget
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.motohub.android.dashcam.Candidate
import io.motohub.android.dashcam.CheckState
import io.motohub.android.dashcam.DashcamController
import io.motohub.android.dashcam.Target
import io.motohub.android.dashcam.net.Credentials
import io.motohub.android.module.ModuleUi
import kotlinx.coroutines.delay

/** The first question when setting up: does the camera make its own Wi-Fi, or join the phone's? */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun SetupPage(open: (Page) -> Unit, back: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        PageHeader("Camera connection", back) {}
        Text("How does the camera connect?", color = Dc.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Choice(
            Ico.WIFI, "Its own Wi-Fi",
            "The camera makes a Wi-Fi network and the phone joins it. Most battery dashcams work this way."
        ) { open(Page.Wifi) }
        Choice(
            Ico.NETWORK, "The phone's network",
            "The camera joins the same Wi-Fi as the phone, or the phone's hotspot, and has an address there. " +
                "IP cameras and some dashcams."
        ) { open(Page.Lan) }
        Text(
            "Not sure? The camera's app or manual says whether it makes a Wi-Fi of its own or joins one.",
            color = Dc.dim, fontSize = 13.sp, lineHeight = 19.sp
        )
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun Choice(icon: Ico, title: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Dc.surface)
            .border(1.dp, Dc.surfaceLine, RoundedCornerShape(22.dp)).clickable(onClick = onClick).padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(Dc.accentSoft), contentAlignment = Alignment.Center) {
            DcIcon(icon, Dc.accent, 30.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = Dc.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(detail, color = Dc.text2, fontSize = 14.sp, lineHeight = 20.sp)
        }
        DcIcon(Ico.CHEVRON, Dc.faint, 18.dp)
    }
}

/**
 * The search on the phone's network: started on arrival, the cameras it found as rows, and the way
 * to type an address when it finds nothing.
 */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun LanPage(controller: DashcamController, open: (Page) -> Unit, home: () -> Unit, back: () -> Unit) {
    val scan by controller.scan.collectAsState()
    val check by controller.check.collectAsState()
    var picked by remember { mutableStateOf<Candidate?>(null) }

    LaunchedEffect(Unit) {
        controller.clearCheck()
        // Back from the login or the address page a moment later: the list is still good.
        if (!scan.running && System.currentTimeMillis() - scan.at > RESEARCH_AFTER_MS) controller.search()
    }
    FollowCheck(controller, picked != null, { open(Page.LanLogin(check.target, picked?.onvif, picked?.name.orEmpty())) }, home)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        PageHeader("Find the camera", back) { if (!scan.running) SmallButton("Search again") { controller.search() } }
        Text(
            "The camera must be on the same Wi-Fi as the phone, or connected to the phone's hotspot.",
            color = Dc.text2, fontSize = 15.sp, lineHeight = 22.sp
        )

        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Dc.surface)
                .border(1.dp, Dc.surfaceLine, RoundedCornerShape(18.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                DcIcon(Ico.NETWORK, if (scan.running) Dc.accent else Dc.dim, 24.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            scan.running -> "Searching…"
                            scan.found.isEmpty() -> "No camera found"
                            scan.found.size == 1 -> "1 camera found"
                            else -> "${scan.found.size} cameras found"
                        },
                        color = Dc.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        if (scan.networks.isEmpty() && (scan.searched || scan.running)) "The phone is on no Wi-Fi and its hotspot is off"
                        else if (scan.networks.isEmpty()) "" else "On the phone's " + scan.networks.joinToString(" and "),
                        color = Dc.dim, fontSize = 13.sp
                    )
                }
            }
            if (scan.running) {
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Dc.line)) {
                    Box(Modifier.fillMaxWidth(scan.progress.coerceIn(0.03f, 1f)).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Dc.accent))
                }
            }
        }

        if (scan.found.isNotEmpty()) {
            Card {
                scan.found.forEachIndexed { i, c ->
                    if (i > 0) Hairline()
                    CandidateRow(c, check.running && check.target == c.host, !check.running) {
                        picked = c
                        controller.check(c.host, c.onvif, null, c.name)
                    }
                }
            }
        }
        if (picked != null) CheckStatus(check)

        Card {
            NavRow(Ico.SLIDERS, "Enter the address yourself", "An IP address, or the stream address from the manual", !check.running) {
                open(Page.LanManual)
            }
        }
        Text(
            "Not in the list? The router's or the hotspot's list of connected devices shows the camera's address.",
            color = Dc.dim, fontSize = 13.sp, lineHeight = 19.sp
        )
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun CandidateRow(c: Candidate, checking: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(72.dp).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Dc.accentSoft), contentAlignment = Alignment.Center) {
            DcIcon(Ico.CAMERA, Dc.accent, 24.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(c.name, color = Dc.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${c.host} · ${c.services}", color = Dc.dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (checking) Text("Checking…", color = Dc.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        else DcIcon(Ico.CHEVRON, Dc.faint, 18.dp)
    }
}

/** An address typed by hand: an IP, `IP:port`, or the `rtsp://` address from the camera's manual. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun LanManualPage(controller: DashcamController, open: (Page) -> Unit, home: () -> Unit, back: () -> Unit) {
    val check by controller.check.collectAsState()
    val store = controller.store
    var address by remember { mutableStateOf(if (store.lan) store.lanUrl.ifEmpty { store.lanHost } else "") }
    var asked by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { controller.clearCheck() }
    FollowCheck(controller, asked, { open(Page.LanLogin(check.target, null, "")) }, home)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        PageHeader("Camera address", back) {}
        Text(
            "Type the camera's IP address - the router's or the hotspot's list of connected devices shows it - " +
                "or the stream address from its manual. The module finds out the rest.",
            color = Dc.text2, fontSize = 15.sp, lineHeight = 22.sp
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Field("Address", address, false) { address = it }
            Text("Like 192.168.1.50, 192.168.1.50:8554 or rtsp://192.168.1.50/stream1", color = Dc.dim, fontSize = 13.sp)
        }
        if (asked) CheckStatus(check)
        BigButton(if (check.running) "Checking…" else "Check and connect", null, !check.running && address.isNotBlank(), false) {
            asked = true
            controller.check(address.trim(), null, null)
        }
    }
}

/** The camera's own login, asked for when the camera turns the module away without one. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun LanLoginPage(controller: DashcamController, target: String, onvif: String?, name: String, home: () -> Unit, back: () -> Unit) {
    val check by controller.check.collectAsState()
    var user by remember { mutableStateOf(controller.store.lanUser.ifEmpty { "admin" }) }
    var password by remember { mutableStateOf("") }
    var asked by remember { mutableStateOf(false) }
    val host = Target.parse(target)?.host ?: target

    LaunchedEffect(Unit) { controller.clearCheck() }
    // A second refusal stays on this page, with its reason; there is no further page to go to.
    FollowCheck(controller, asked, {}, home)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        PageHeader("Camera login", back) {}
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(Dc.accentSoft), contentAlignment = Alignment.Center) {
                DcIcon(Ico.LOCK, Dc.accent, 28.dp)
            }
            Column(Modifier.weight(1f)) {
                Text(name.ifEmpty { "Camera" }, color = Dc.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(host, color = Dc.dim, fontSize = 13.sp)
            }
        }
        Text(
            "The camera asks for a user name and password. They are the camera's own - on its sticker, in its manual " +
                "or set in its app - not the Wi-Fi password.",
            color = Dc.text2, fontSize = 15.sp, lineHeight = 22.sp
        )
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Field("User name", user, false) { user = it }
            Field("Password", password, true) { password = it }
        }
        if (asked) CheckStatus(check)
        BigButton(if (check.running) "Checking…" else "Sign in", Ico.LOCK, !check.running && user.isNotBlank(), false) {
            asked = true
            controller.check(target, onvif, Credentials(user.trim(), password), name)
        }
    }
}

/**
 * What a check leads to, for the page that asked: the login page when the camera wants one, the
 * module's home page once the camera is found and connected.
 */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun FollowCheck(controller: DashcamController, asked: Boolean, onLogin: () -> Unit, home: () -> Unit) {
    val check by controller.check.collectAsState()
    val state by controller.state.collectAsState()
    LaunchedEffect(check.needsLogin, asked) {
        if (asked && check.needsLogin && check.message.isEmpty()) onLogin()
    }
    LaunchedEffect(check.done, state.connected) {
        if (asked && check.done && state.connected) {
            delay(700)
            home()
        }
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun CheckStatus(check: CheckState) {
    when {
        check.running -> Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Dc.surface).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(Dc.accent))
            Text("Asking ${Target.parse(check.target)?.host ?: check.target} what it offers…", color = Dc.text, fontSize = 14.sp)
        }
        check.done -> Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Dc.okSoft).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            DcIcon(Ico.CHECK, Dc.ok, 16.dp)
            Text("Found it. Connecting…", color = Dc.text, fontSize = 14.sp)
        }
        check.message.isNotEmpty() -> Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Dc.warnSoft).padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(Modifier.padding(top = 5.dp).size(8.dp).clip(CircleShape).background(Dc.warn))
            Text(check.message, color = Dc.text, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}

private const val RESEARCH_AFTER_MS = 60_000L
