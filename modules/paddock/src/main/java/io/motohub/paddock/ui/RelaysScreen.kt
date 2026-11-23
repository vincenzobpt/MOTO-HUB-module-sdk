// SPDX-License-Identifier: MPL-2.0
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
// of the MPL was not distributed with this file, you can obtain one at https://mozilla.org/MPL/2.0/.
package io.motohub.paddock.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableTarget
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.motohub.android.module.ModuleUi
import io.motohub.paddock.engine.PaddockController
import io.motohub.paddock.engine.PaddockState
import io.motohub.paddock.engine.Relay
import kotlinx.coroutines.delay
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.PI as MathPi
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

/** The relays: automatic or one of the rider's, the ones in use, and every relay heard of, on a map. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun RelaysScreen(controller: PaddockController, state: PaddockState, onBack: () -> Unit) {
    val view by controller.relays.collectAsState()
    LaunchedEffect(Unit) {
        while (true) { controller.refreshRelays(); delay(5000) }
    }
    var custom by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf<String?>(null) }
    val me = remember { controller.myLocation() }
    Page("Relays", onBack) {
        val up = view.connected.filter { it.online }
        val down = view.connected.filter { !it.online }
        Txt(
            "Paddock reaches the other riders through public relays: computers on the internet that pass on " +
                "messages they cannot read. " + (if (view.auto) "It picks the fastest ones by itself." else "You chose one relay below.") +
                " Riders of a room also connect to the relays its leader shares in the room's code.",
            Ty.SMALL, P.text2
        )
        Spacer(Modifier.height(16.dp))
        RelayMap(me, view.connected, view.discovered)
        Txt("Map © OpenStreetMap contributors", Ty.SMALL, P.faint, Modifier.fillMaxWidth().padding(top = 4.dp), align = TextAlign.End)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Legend(P.ok, "Connected"); Legend(P.accent, "Other relays"); if (me != null) Legend(Color(0xFF3B82F6), "You")
        }

        Spacer(Modifier.height(S.section))
        SectionLabel("Connected · ${up.size}")
        if (up.isEmpty()) Txt(if (view.loading) "Connecting…" else "Not connected to any relay yet.", Ty.SMALL, P.warn)
        else Card {
            up.forEachIndexed { i, r ->
                if (i > 0) Hairline(start = 38.dp)
                RelayRow(r, me, when {
                    r.room -> "Connected · shared by your room's leader"
                    r.found -> "Connected · found on the network"
                    else -> "Connected · one of the fastest from here"
                })
            }
        }
        if (down.isNotEmpty()) Txt(
            down.joinToString(", ") { it.name } + (if (down.size == 1) " does" else " do") + " not answer now. Paddock keeps trying.",
            Ty.SMALL, P.dim, Modifier.padding(start = 4.dp, top = 6.dp)
        )

        Spacer(Modifier.height(S.section))
        val others = view.discovered.filter { d -> view.connected.none { it.host == d.host && it.port == d.port } }
        SectionLabel("Other relays · ${others.size}")
        if (others.isEmpty()) Txt(
            "Relays tell the network about themselves now and then. They appear here, and on the map, as Paddock hears of them.",
            Ty.SMALL, P.dim
        ) else {
            Txt("Paddock can switch to these by itself if the connected ones stop answering.", Ty.SMALL, P.dim, Modifier.padding(start = 4.dp, bottom = 8.dp))
            Card {
                others.sortedBy { km(me, it) ?: Double.MAX_VALUE }.take(30).forEachIndexed { i, r ->
                    if (i > 0) Hairline(start = 38.dp)
                    RelayRow(r, me, if (r.status == "available") "Heard of recently" else "Not heard of for a day or more")
                }
            }
        }

        Spacer(Modifier.height(S.section))
        SectionLabel("Use one relay of your own")
        Field("Relay (host:port)", custom, { custom = it; saved = null }, "rns.example.org:4242", 120)
        val parsed = custom.trim().let { t ->
            val host = t.substringBeforeLast(':', "")
            val port = t.substringAfterLast(':', "").toIntOrNull()
            if (host.isNotEmpty() && port != null && port in 1..65535) host to port else null
        }
        Spacer(Modifier.height(12.dp))
        SecondaryButton("Use only this relay", PI.CHECK, enabled = parsed != null && saved == null) {
            if (parsed != null) { controller.setRelay(parsed.first, parsed.second); saved = "${parsed.first}:${parsed.second}" }
        }
        if (!view.auto) {
            Spacer(Modifier.height(8.dp))
            SecondaryButton("Back to automatic", PI.RELAY, enabled = saved != "auto") { controller.setAutoRelay(); saved = "auto" }
        }
        saved?.let {
            Txt(
                (if (it == "auto") "Automatic" else it) + " from the next start of MOTO-HUB.",
                Ty.SMALL, P.ok, Modifier.padding(start = 4.dp, top = 8.dp)
            )
        }
        Spacer(Modifier.height(36.dp))
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Txt(label, Ty.SMALL, P.dim)
    }
}

/** One relay: its name, one plain line about it, and how far it is from you when both are known. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun RelayRow(r: Relay, me: Pair<Double, Double>?, about: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(relayColor(r)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Txt(r.name, Ty.BODY_STRONG, P.text, maxLines = 1)
            Txt(about, Ty.SMALL, P.dim, maxLines = 1)
        }
        km(me, r)?.let { Txt(if (it < 10) "near you" else "${it.toInt()} km", Ty.SMALL_STRONG, P.text2, maxLines = 1) }
    }
}

/** Great-circle distance in km between you and a relay that says where it is. */
private fun km(me: Pair<Double, Double>?, r: Relay): Double? {
    val (a, b) = me ?: return null
    val lat = r.lat ?: return null
    val lon = r.lon ?: return null
    val p1 = Math.toRadians(a); val p2 = Math.toRadians(lat)
    val dp = p2 - p1; val dl = Math.toRadians(lon - b)
    val h = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2)
    return 6371.0 * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h))
}

private fun relayColor(r: Relay): Color = when {
    r.connected -> if (r.online) P.ok else P.faint
    r.status == "available" -> P.accent
    else -> P.faint
}

// ---------------------------------------------------------------- the map

private const val TILE = 256
// OpenStreetMap's own tiles: no key. Its tile policy asks for an app that says who it is (the
// User-Agent below), few requests (a handful per view, kept in memory) and the attribution.
private const val TILE_URL = "https://tile.openstreetmap.org/%d/%d/%d.png"
private val tiles = ConcurrentHashMap<String, ImageBitmap>()
private val asked = ConcurrentHashMap.newKeySet<String>()
private val fetcher = Executors.newFixedThreadPool(3) { Thread(it, "paddock-tiles").apply { isDaemon = true } }

private fun worldX(lon: Double, z: Int) = (lon + 180.0) / 360.0 * TILE * 2.0.pow(z)
private fun worldY(lat: Double, z: Int): Double {
    val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
    return (1.0 - ln(tan(r) + 1.0 / cos(r)) / MathPi) / 2.0 * TILE * 2.0.pow(z)
}

/** A slippy map from OpenStreetMap, dimmed, z 2-9: drag to move, + and - to zoom. Tiles are fetched once and kept in memory. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun RelayMap(me: Pair<Double, Double>?, connected: List<Relay>, discovered: List<Relay>) {
    var zoom by remember { mutableIntStateOf(4) }
    val start = me ?: (47.0 to 10.0)
    var cx by remember { mutableStateOf(worldX(start.second, 4)) }
    var cy by remember { mutableStateOf(worldY(start.first, 4)) }
    var loaded by remember { mutableIntStateOf(0) } // bumps when a tile arrives
    var size by remember { mutableStateOf(IntSize.Zero) }
    val shape = RoundedCornerShape(18.dp)
    Box(
        Modifier.fillMaxWidth().height(300.dp).clip(shape).background(Color(0xFF0E1117))
            .pointerInput(Unit) { detectDragGestures { change, drag -> change.consume(); cx -= drag.x; cy -= drag.y } }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            size = IntSize(this.size.width.toInt(), this.size.height.toInt())
            if (loaded < 0) return@Canvas // read: a tile that arrives draws the map again
            val n = 1 shl zoom
            val left = cx - this.size.width / 2
            val top = cy - this.size.height / 2
            val tx0 = floor(left / TILE).toInt()
            val ty0 = floor(top / TILE).toInt()
            val tx1 = floor((left + this.size.width) / TILE).toInt()
            val ty1 = floor((top + this.size.height) / TILE).toInt()
            for (ty in ty0..ty1) for (tx in tx0..tx1) {
                if (ty < 0 || ty >= n) continue
                val x = ((tx % n) + n) % n
                val key = "$zoom/$x/$ty"
                val img = tiles[key]
                if (img != null) {
                    drawImage(img, dstOffset = IntOffset((tx * TILE - left).toInt(), (ty * TILE - top).toInt()), dstSize = IntSize(TILE, TILE))
                } else if (asked.add(key)) {
                    fetcher.execute {
                        runCatching {
                            val c = URL(TILE_URL.format(zoom, x, ty)).openConnection() as HttpURLConnection
                            c.setRequestProperty("User-Agent", "MOTO-HUB Paddock (Android)")
                            c.connectTimeout = 8000; c.readTimeout = 8000
                            c.inputStream.use { BitmapFactory.decodeStream(it) }?.let { tiles[key] = it.asImageBitmap() }
                            c.disconnect()
                        }.onFailure { asked.remove(key) }
                        loaded++
                    }
                }
            }
            // OSM's map is light: dimmed, so the pins and the dark page stay readable.
            drawRect(Color.Black.copy(alpha = 0.35f))
            fun at(lat: Double, lon: Double): Offset {
                var x = worldX(lon, zoom) - left
                val world = TILE * n
                while (x < -world / 2) x += world
                while (x > this.size.width + world / 2) x -= world
                return Offset(x.toFloat(), (worldY(lat, zoom) - top).toFloat())
            }
            for (r in discovered + connected) {
                val lat = r.lat ?: continue
                val lon = r.lon ?: continue
                val p = at(lat, lon)
                val c = relayColor(r)
                drawCircle(c.copy(alpha = 0.25f), 14f, p)
                drawCircle(c, 7f, p)
            }
            me?.let { (lat, lon) ->
                val p = at(lat, lon)
                drawCircle(Color.White, 9f, p)
                drawCircle(Color(0xFF3B82F6), 6f, p)
                drawCircle(Color(0x553B82F6), 22f, p, style = Stroke(3f))
            }
        }
        Column(Modifier.align(Alignment.TopEnd).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ZoomButton("+") {
                if (zoom < 9) { zoom++; cx *= 2; cy *= 2 }
            }
            ZoomButton("−") {
                if (zoom > 2) { zoom--; cx /= 2; cy /= 2 }
            }
        }
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun ZoomButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(P.surfaceHi).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Txt(label, Ty.TITLE, P.text) }
}
