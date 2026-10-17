// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.tomtom

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableTarget
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.motohub.android.module.ModuleUi
import io.motohub.android.module.MotoHubModuleHost
import java.text.NumberFormat

private const val DEVELOPERS_URL = "https://developer.tomtom.com"
private const val TERMS_URL = "https://www.tomtom.com/en_gb/thirdpartyproductterms/"

/** The module's page under Modules: is it set up, what is on, how much of the month is used. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun HomePage(host: MotoHubModuleHost, service: TomTomService, onBack: () -> Unit) {
    // The key being typed and the terms being ticked are only a draft until "Check my key" passes.
    var setUp by remember { mutableStateOf(service.ready) }
    var key by remember { mutableStateOf(service.apiKey) }
    var terms by remember { mutableStateOf(service.termsAccepted) }
    var checking by remember { mutableStateOf(false) }
    var rows by remember { mutableStateOf(service.lastCheck) }
    // Bumped when work on another thread ends, so the counts and rows are read again.
    var version by remember { mutableIntStateOf(0) }

    fun check(newKey: String, newTerms: Boolean) {
        checking = true
        Thread {
            val before = service.apiKey to service.termsAccepted
            service.apiKey = newKey.trim()
            service.termsAccepted = newTerms
            val result = service.check()
            // A key that passes nothing is not kept: the page stays where it was.
            val passed = result.any { it.problem == null }
            if (passed) {
                service.save()
            } else {
                service.apiKey = before.first
                service.termsAccepted = before.second
            }
            setUp = service.ready
            rows = result
            checking = false
            version++
        }.start()
    }

    host.ui.Backdrop(onBack) {
        version.let { }
        Column(
            Modifier.fillMaxSize().background(Ink.Ground).verticalScroll(rememberScrollState())
                .statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassKey("‹", false, onBack)
                Spacer(Modifier.width(6.dp))
                Txt("TomTom", 26.sp, Ink.Text, FontWeight.Bold, Modifier.weight(1f), 1)
                if (setUp) Chip("LIVE", Ink.Good, Ink.PanelHigh) else Chip("SET UP", Ink.Gold, Ink.PanelHigh)
            }
            Spacer(Modifier.height(14.dp))
            Hero()

            if (!setUp) {
                Eyebrow("Three steps", Modifier)
                Steps(host, key, terms, checking, { key = it }, { terms = it }) { check(key, terms) }
                rows?.let { Eyebrow("Result", Modifier); CheckRows(it) }
            } else {
                Eyebrow("On your NAV", Modifier)
                Column(Modifier.fillMaxWidth().card()) {
                    Feature.entries.forEachIndexed { i, f ->
                        if (i > 0) Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp).height(1.dp).background(Ink.Line))
                        FeatureRow(f, service.isOn(f)) { service.setOn(f, it); version++ }
                    }
                }

                Eyebrow("This month", Modifier)
                UsageCard(service.usage)

                Eyebrow("Your key", Modifier)
                Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Num(service.maskedKey, 22.sp, Ink.Text, FontWeight.Bold, Modifier)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SideButton(if (checking) "Checking…" else "Check again", !checking, Modifier.weight(1f)) { check(service.apiKey, service.termsAccepted) }
                        SideButton("Remove", !checking, Modifier.weight(1f)) {
                            service.forgetKey()
                            key = ""
                            setUp = false
                            rows = null
                            version++
                        }
                    }
                    rows?.let { CheckRows(it, bare = true) }
                }
            }

            Eyebrow("Credits", Modifier)
            Credits(host, service, setUp)

            Spacer(Modifier.height(24.dp))
            Txt(
                "Not made by or affiliated with TomTom. Map and traffic data © TomTom.",
                12.sp, Ink.Faint, FontWeight.Normal, Modifier.padding(horizontal = 4.dp), 3
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}

/** A road bending across a card, flowing from green to amber to red, with a pin where it ends. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun Hero() {
    Column(Modifier.fillMaxWidth().card().padding(bottom = 18.dp)) {
        Canvas(Modifier.fillMaxWidth().height(120.dp).background(Ink.PanelHigh)) {
            val w = size.width
            val h = size.height
            val a = Offset(w * 0.08f, h * 0.74f)
            val b = Offset(w * 0.34f, h * -0.05f)
            val c = Offset(w * 0.56f, h * 1.12f)
            val d = Offset(w * 0.84f, h * 0.52f)
            fun at(t: Float): Offset {
                val u = 1 - t
                return Offset(
                    u * u * u * a.x + 3 * u * u * t * b.x + 3 * u * t * t * c.x + t * t * t * d.x,
                    u * u * u * a.y + 3 * u * u * t * b.y + 3 * u * t * t * c.y + t * t * t * d.y
                )
            }
            fun stretch(from: Float, to: Float): Path = Path().apply {
                val steps = 24
                for (i in 0..steps) {
                    val p = at(from + (to - from) * i / steps)
                    if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                }
            }
            val width = 12.dp.toPx()
            // The road's edge first, so the colours sit in a kerb.
            drawPath(stretch(0f, 1f), Ink.Panel, style = Stroke(width + 8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(stretch(0f, 0.38f), Ink.Good, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(stretch(0.38f, 0.7f), Ink.Gold, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(stretch(0.7f, 1f), Ink.Bad, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
            // The pin drop: a disc on a point, standing on the end of the road.
            val tip = at(1f)
            val head = Offset(tip.x, tip.y - 30.dp.toPx())
            val r = 12.dp.toPx()
            drawPath(
                Path().apply {
                    moveTo(head.x - r * 0.8f, head.y + r * 0.6f)
                    lineTo(tip.x, tip.y - 4.dp.toPx())
                    lineTo(head.x + r * 0.8f, head.y + r * 0.6f)
                    close()
                },
                Ink.Accent
            )
            drawCircle(Ink.Accent, r, head)
            drawCircle(Color.White, r * 0.4f, head)
        }
        Txt(
            "Traffic, places and search from TomTom on your phone. Never on the bike's screen.",
            15.sp, Ink.Muted, FontWeight.Normal, Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp), 4
        )
    }
}

/** The three things to do before the module can call TomTom. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun Steps(
    host: MotoHubModuleHost,
    key: String,
    terms: Boolean,
    checking: Boolean,
    onKey: (String) -> Unit,
    onTerms: (Boolean) -> Unit,
    onCheck: () -> Unit
) {
    Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Step(1, "Get a free key", "A TomTom developer account gives you a key with a free allowance every month.") {
            SideButton("Open TomTom for developers", true, Modifier.fillMaxWidth()) { openLink(host, DEVELOPERS_URL) }
        }
        Step(2, "Paste it here", "The key stays on this phone, sealed, and goes only to TomTom.") {
            KeyField(key, onKey) { pasted(host)?.let(onKey) }
        }
        Step(3, "Accept TomTom's terms", "TomTom's data may be shown on the phone only, never in a vehicle.") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ink.PanelHigh)
                        .clickable { onTerms(!terms) }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Tick(terms)
                    Spacer(Modifier.width(12.dp))
                    Txt("I accept TomTom's end-user terms", 15.sp, Ink.Text, FontWeight.Medium, Modifier.weight(1f), 2)
                }
                Txt(
                    "Read the terms",
                    14.sp, Ink.Accent, FontWeight.Bold,
                    Modifier.clickable { openLink(host, TERMS_URL) }.padding(horizontal = 4.dp, vertical = 4.dp), 1
                )
            }
        }
        MainButton(if (checking) "Checking…" else "Check my key", key.isNotBlank() && terms && !checking, Modifier.fillMaxWidth(), onCheck)
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun Step(number: Int, title: String, body: String, content: @Composable @ComposableTarget(ModuleUi.UI_APPLIER) () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(Ink.AccentDeep), contentAlignment = Alignment.Center) {
            Num(number.toString(), 15.sp, Color.White, FontWeight.Bold, Modifier)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt(title, 17.sp, Ink.Text, FontWeight.SemiBold, Modifier.padding(top = 3.dp), 2)
            Txt(body, 14.sp, Ink.Muted, FontWeight.Normal, Modifier, 4)
            Spacer(Modifier.height(2.dp))
            content()
        }
    }
}

/** A checkbox drawn by hand. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun Tick(on: Boolean) {
    Box(
        Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(if (on) Ink.Accent else Color.Transparent)
            .border(2.dp, if (on) Ink.Accent else Ink.Faint, RoundedCornerShape(7.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (on) Txt("✓", 16.sp, Color.White, FontWeight.Bold, Modifier, 1)
    }
}

/** The key's field: masked, with Paste and Show beside it. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun KeyField(value: String, onChange: (String) -> Unit, onPaste: () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ink.PanelHigh).padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.weight(1f).padding(vertical = 15.dp)) {
            if (value.isEmpty()) Txt("Your TomTom key", 16.sp, Ink.Faint, FontWeight.Normal, Modifier, 1)
            BasicTextField(
                value = value,
                onValueChange = { onChange(it.filter { c -> !c.isWhitespace() }) },
                singleLine = true,
                textStyle = TextStyle(color = Ink.Text, fontSize = 16.sp),
                cursorBrush = SolidColor(Ink.Accent),
                visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Box(Modifier.clickable(onClick = onPaste).padding(horizontal = 12.dp, vertical = 15.dp)) {
            Txt("Paste", 14.sp, Ink.Accent, FontWeight.Bold, Modifier, 1)
        }
        Box(Modifier.clickable { shown = !shown }.padding(end = 16.dp, top = 15.dp, bottom = 15.dp)) {
            Txt(if (shown) "Hide" else "Show", 14.sp, Ink.Muted, FontWeight.Bold, Modifier, 1)
        }
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun FeatureRow(feature: Feature, on: Boolean, onChange: (Boolean) -> Unit) {
    val (title, where) = when (feature) {
        Feature.TRAFFIC -> "Traffic on the map" to "Layers ▸ Traffic"
        Feature.PLACES -> "Places" to "Layers ▸ Place icons: TomTom"
        Feature.SEARCH -> "Search" to "In the NAV search box"
        Feature.DRIVE -> "Drive times to fuel" to "Route preview ▸ Supplies"
        Feature.ROAD -> "Road info" to "The Road info key on the NAV map"
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Txt(title, 16.sp, Ink.Text, FontWeight.SemiBold, Modifier, 1)
            Txt(where, 13.sp, Ink.Muted, FontWeight.Normal, Modifier.padding(top = 2.dp), 2)
        }
        Spacer(Modifier.width(12.dp))
        Toggle(on, onChange)
    }
}

/** One bar per API this month: the more used, the warmer. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun UsageCard(usage: Usage) {
    val format = NumberFormat.getIntegerInstance()
    val used = Api.entries.filter { usage.used(it) > 0 }
    Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (used.isEmpty()) Txt("Nothing used yet this month.", 14.sp, Ink.Muted, FontWeight.Normal, Modifier, 2)
        used.forEach { api ->
            val count = usage.used(api)
            val share = (count.toFloat() / api.allowance).coerceIn(0f, 1f)
            val color = when {
                count >= api.allowance -> Ink.Bad
                share > 0.8f -> Ink.Gold
                else -> Ink.Accent
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt(api.label, 15.sp, Ink.Text, FontWeight.Medium, Modifier.weight(1f), 1)
                    Num(format.format(count) + " / " + format.format(api.allowance), 14.sp, Ink.Muted, FontWeight.Medium, Modifier)
                }
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Ink.PanelHigh)) {
                    // A used call is always visible, however small the share.
                    Box(Modifier.fillMaxWidth(maxOf(share, 0.02f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(color))
                }
            }
        }
        Txt(
            "Counted on this phone; TomTom's dashboard has the exact numbers. Map tiles are counted by TomTom only.",
            12.sp, Ink.Faint, FontWeight.Normal, Modifier, 4
        )
    }
}

/** The result of the check: a line for each part, in TomTom's own words when it said no. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun CheckRows(rows: List<CheckRow>, bare: Boolean = false) {
    Column(
        (if (bare) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().card().padding(18.dp)),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        rows.forEach { r ->
            Row(verticalAlignment = Alignment.Top) {
                Txt(if (r.problem == null) "✓" else "✕", 18.sp, if (r.problem == null) Ink.Good else Ink.Bad, FontWeight.Bold, Modifier.width(26.dp), 1)
                Column(Modifier.weight(1f)) {
                    Txt(r.name, 15.sp, Ink.Text, FontWeight.SemiBold, Modifier, 1)
                    Txt(r.problem ?: "Works", 13.sp, if (r.problem == null) Ink.Good else Ink.Bad, FontWeight.Medium, Modifier.padding(top = 2.dp), 4)
                }
            }
        }
    }
}

private fun openLink(host: MotoHubModuleHost, url: String) {
    runCatching { host.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Whatever text the clipboard holds, without the spaces a copied key may carry. */
private fun pasted(host: MotoHubModuleHost): String? = runCatching {
    val clipboard = host.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()?.filter { !it.isWhitespace() }
}.getOrNull()?.takeIf { it.isNotEmpty() }

private const val SOURCE_URL = "https://github.com/vincenzobpt/MOTO-HUB-module-sdk/tree/main/modules/tomtom"

/** TomTom's notice, as its Copyright API words it, and this module's own licence. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun Credits(host: MotoHubModuleHost, service: TomTomService, setUp: Boolean) {
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(setUp) {
        if (setUp) notice = withContext(Dispatchers.IO) { service.copyrights() }
    }
    Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Txt("Map, traffic and place data", 15.sp, Ink.Text, FontWeight.Bold, Modifier, 1)
        Txt(
            notice ?: "© TomTom. The full notice appears here once your key is set up.",
            13.sp, Ink.Muted, FontWeight.Normal, Modifier, 12
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.Line))
        Txt("This module", 15.sp, Ink.Text, FontWeight.Bold, Modifier, 1)
        Txt(
            "Free software under the GNU Affero General Public License v3. Its source is public.",
            13.sp, Ink.Muted, FontWeight.Normal, Modifier, 3
        )
        Txt(
            "View the source",
            14.sp, Ink.Accent, FontWeight.Bold,
            Modifier.clickable { openLink(host, SOURCE_URL) }.padding(vertical = 4.dp), 1
        )
    }
}
