// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.immich

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableTarget
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.motohub.android.module.ModuleUi
import io.motohub.android.module.MotoHubModuleHost
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The photos of one pin, full screen: a grid when the pin holds several, then each photo on black,
 * swiped through, pinched and double-tapped into, with everything Immich knows one swipe up.
 */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun PhotoViewer(host: MotoHubModuleHost, server: ImmichServer, spot: Spot?, onBack: () -> Unit) {
    if (spot == null) {
        host.ui.Backdrop(onBack) {
            Box(Modifier.fillMaxSize().background(Ink.Ground), contentAlignment = Alignment.Center) {
                Txt("Tap a photo on the NAV map to open it here.", 16.sp, Ink.Muted, FontWeight.Medium, Modifier.padding(32.dp), 3)
            }
        }
        return
    }
    val ids = spot.ids
    var grid by remember(spot) { mutableStateOf(ids.size > 1) }
    var index by remember(spot) { mutableIntStateOf(0) }
    var chrome by remember(spot) { mutableStateOf(true) }
    var info by remember(spot) { mutableStateOf(false) }
    val details = remember(spot) { mutableStateMapOf<String, PhotoDetails>() }
    val problems = remember(spot) { mutableStateMapOf<String, String>() }

    val current = ids[index.coerceIn(0, ids.lastIndex)]
    LaunchedEffect(current) {
        if (current in details) return@LaunchedEffect
        try {
            details[current] = withContext(kotlinx.coroutines.Dispatchers.IO) { server.details(current) }
        } catch (e: Exception) {
            problems[current] = e.message ?: "Immich did not answer."
        }
    }

    // Back closes what is on top first: the info, then the photo (back to the grid), then the page.
    host.ui.Backdrop({
        when {
            info -> info = false
            !grid && ids.size > 1 -> grid = true
            else -> onBack()
        }
    }) {
        if (grid) {
            Gallery(server, spot, onBack = onBack) { picked ->
                index = picked
                grid = false
                chrome = true
            }
        } else {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                Pager(
                    server = server,
                    ids = ids,
                    index = index,
                    videos = { details[it]?.video == true },
                    onIndex = { index = it },
                    onTap = { chrome = !chrome },
                    onSwipeUp = { info = true },
                    onSwipeDown = { if (ids.size > 1) grid = true else onBack() }
                )
                AnimatedVisibility(chrome && !info, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
                    TopBar(
                        details = details[current],
                        fallbackPlace = listOfNotNull(spot.marker.city, spot.marker.country).joinToString(", "),
                        counter = if (ids.size > 1) "${index + 1} / ${ids.size}" else null,
                        onBack = { if (ids.size > 1) grid = true else onBack() },
                        onGrid = if (ids.size > 1) ({ grid = true }) else null,
                        onInfo = { info = true }
                    )
                }
                AnimatedVisibility(chrome && !info && ids.size > 1, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
                    Filmstrip(server, ids, index) { index = it }
                }
                AnimatedVisibility(
                    info,
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    InfoSheet(host, server, current, details[current], problems[current], onClose = { info = false })
                }
            }
        }
    }
}

/** Every photo of the pin as a grid of squares, newest Immich order. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun Gallery(server: ImmichServer, spot: Spot, onBack: () -> Unit, onPick: (Int) -> Unit) {
    val place = listOfNotNull(spot.marker.city, spot.marker.country).joinToString(", ")
    Column(Modifier.fillMaxSize().background(Ink.Ground)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GlassKey("‹", false, onBack)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Txt(place.ifEmpty { "Photos here" }, 22.sp, Ink.Text, FontWeight.Bold, Modifier, 1)
                Txt("${spot.ids.size} photos taken here", 14.sp, Ink.Muted, FontWeight.Medium, Modifier, 1)
            }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 3.dp, end = 3.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            itemsIndexed(spot.ids.chunked(COLUMNS)) { row, ids ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    ids.forEachIndexed { column, id ->
                        Thumb(
                            server, id,
                            Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(4.dp))
                                .clickable { onPick(row * COLUMNS + column) }
                        )
                    }
                    repeat(COLUMNS - ids.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun TopBar(
    details: PhotoDetails?,
    fallbackPlace: String,
    counter: String?,
    onBack: () -> Unit,
    onGrid: (() -> Unit)?,
    onInfo: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
            .statusBarsPadding().padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 28.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GlassKey("‹", false, onBack)
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            val day = details?.takenAt?.let { Words.day(it) }
            Txt(day ?: fallbackPlace.ifEmpty { "Photo" }, 16.sp, Color.White, FontWeight.Bold, Modifier, 1)
            val sub = listOfNotNull(details?.takenAt?.let { Words.time(it) }, details?.place ?: fallbackPlace.ifEmpty { null }).joinToString(" · ")
            if (sub.isNotEmpty()) Txt(sub, 13.sp, Color(0xCCFFFFFF), FontWeight.Medium, Modifier, 1)
        }
        if (counter != null) Txt(counter, 13.sp, Color(0xCCFFFFFF), FontWeight.SemiBold, Modifier.padding(end = 4.dp), 1)
        if (onGrid != null) GlassKey("▦", false, onGrid)
        GlassKey("ⓘ", false, onInfo)
    }
}

/** The pin's photos in a row under the picture; the open one is ringed, and the row follows it. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun Filmstrip(server: ImmichServer, ids: List<String>, index: Int, onPick: (Int) -> Unit) {
    val list = rememberLazyListState()
    LaunchedEffect(index) { list.scrollToItem(max(0, index - 2)) }
    Box(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
            .navigationBarsPadding().padding(top = 28.dp, bottom = 12.dp)
    ) {
        LazyRow(state = list, contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(ids) { i, id ->
                val open = i == index
                Thumb(
                    server, id,
                    Modifier.size(if (open) 64.dp else 54.dp).clip(RoundedCornerShape(10.dp))
                        .border(if (open) 2.5.dp else 0.dp, if (open) Color.White else Color.Transparent, RoundedCornerShape(10.dp))
                        .clickable { onPick(i) }
                )
            }
        }
    }
}

/**
 * The photo and its neighbours, side by side. One gesture handler for everything, so the touches
 * never fight: two fingers zoom about the point between them; one finger pans a zoomed photo,
 * otherwise swipes to the next one, up to the info or down to leave; a tap shows or hides the bars
 * and a double tap zooms in on that point, or back out.
 */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun Pager(
    server: ImmichServer,
    ids: List<String>,
    index: Int,
    videos: (String) -> Boolean,
    onIndex: (Int) -> Unit,
    onTap: () -> Unit,
    onSwipeUp: () -> Unit,
    onSwipeDown: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var width by remember { mutableFloatStateOf(1f) }
    var height by remember { mutableFloatStateOf(1f) }
    val slide = remember { Animatable(0f) }
    val lift = remember { Animatable(0f) }
    var scale by remember(index) { mutableFloatStateOf(1f) }
    var pan by remember(index) { mutableStateOf(Offset.Zero) }
    // The open photo's size on screen at scale 1, for how far a zoomed photo may be pushed.
    var fitted by remember(index) { mutableStateOf(Offset.Zero) }
    val taps = remember { TapClock() }

    fun clamp(p: Offset, s: Float): Offset {
        val maxX = max(0f, (fitted.x * s - width) / 2f)
        val maxY = max(0f, (fitted.y * s - height) / 2f)
        return Offset(p.x.coerceIn(-maxX, maxX), p.y.coerceIn(-maxY, maxY))
    }

    Box(
        Modifier.fillMaxSize()
            .onSizeChanged { width = it.width.toFloat(); height = it.height.toFloat() }
            .pointerInput(ids, index) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var multi = false
                    var moved = false
                    var dragged = Offset.Zero
                    var horizontal = true
                    val slop = viewConfiguration.touchSlop
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } > 1) multi = true
                        val zoomBy = event.calculateZoom()
                        val panBy = event.calculatePan()
                        if (multi || scale > 1.01f) {
                            val centroid = event.calculateCentroid(true)
                            val next = (scale * zoomBy).coerceIn(1f, MAX_ZOOM)
                            if (centroid.isSpecified()) {
                                val c = centroid - Offset(width / 2f, height / 2f)
                                pan = clamp(c - (c - pan) * (next / scale) + panBy, next)
                            }
                            scale = next
                            moved = true
                            event.changes.forEach { it.consume() }
                        } else {
                            dragged += panBy
                            if (!moved && dragged.getDistance() > slop) {
                                moved = true
                                horizontal = abs(dragged.x) >= abs(dragged.y)
                            }
                            if (moved) {
                                if (horizontal) {
                                    // Past the first or the last photo the page only gives a little.
                                    val edge = (index == 0 && dragged.x > 0) || (index == ids.lastIndex && dragged.x < 0)
                                    scope.launch { slide.snapTo(if (edge) dragged.x / 4f else dragged.x) }
                                } else {
                                    scope.launch { lift.snapTo(dragged.y) }
                                }
                                event.changes.forEach { it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    if (multi || scale > 1.01f) {
                        if (scale < 1.05f) { scale = 1f; pan = Offset.Zero }
                    } else if (moved && horizontal) {
                        val target = when {
                            dragged.x < -width * PAGE_SHARE && index < ids.lastIndex -> index + 1
                            dragged.x > width * PAGE_SHARE && index > 0 -> index - 1
                            else -> index
                        }
                        scope.launch {
                            if (target != index) {
                                slide.animateTo(if (target > index) -width else width, tween(220))
                                onIndex(target)
                            } else {
                                slide.animateTo(0f, tween(180))
                            }
                            slide.snapTo(0f)
                        }
                    } else if (moved) {
                        when {
                            dragged.y < -height * LIFT_SHARE -> onSwipeUp()
                            dragged.y > height * LIFT_SHARE -> onSwipeDown()
                        }
                        scope.launch { lift.animateTo(0f, tween(200)) }
                    } else {
                        val at = down.position
                        if (taps.second(at)) {
                            if (scale > 1.01f) {
                                scale = 1f
                                pan = Offset.Zero
                            } else {
                                val c = at - Offset(width / 2f, height / 2f)
                                scale = DOUBLE_TAP_ZOOM
                                pan = clamp(-c * (DOUBLE_TAP_ZOOM - 1f), DOUBLE_TAP_ZOOM)
                            }
                        } else {
                            taps.first(at, scope.launch { delay(TAP_WAIT_MS); onTap() })
                        }
                    }
                }
            }
    ) {
        for (step in -1..1) {
            val i = index + step
            if (i !in ids.indices) continue
            val open = step == 0
            Page(
                server = server,
                id = ids[i],
                full = open && scale > 1.6f,
                video = videos(ids[i]),
                onFitted = { if (open) fitted = it },
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    translationX = slide.value + step * width + if (open) pan.x else 0f
                    translationY = if (open) pan.y + lift.value else 0f
                    if (open) {
                        scaleX = scale
                        scaleY = scale
                        alpha = 1f - min(0.6f, abs(lift.value) / height)
                    }
                }
            )
        }
    }
}

/** One photo fitted to the screen: the thumbnail at once, the preview over it, the whole picture once zoomed in. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun Page(server: ImmichServer, id: String, full: Boolean, video: Boolean, onFitted: (Offset) -> Unit, modifier: Modifier) {
    var thumb by remember(id) { mutableStateOf(Bitmaps.held(id, ImmichServer.THUMBNAIL)) }
    var preview by remember(id) { mutableStateOf(Bitmaps.held(id, ImmichServer.PREVIEW)) }
    var whole by remember(id) { mutableStateOf<ImageBitmap?>(null) }
    var box by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(id) {
        if (preview == null && thumb == null) thumb = Bitmaps.load(server, id, ImmichServer.THUMBNAIL, 512)
        if (preview == null) preview = Bitmaps.load(server, id, ImmichServer.PREVIEW, 2048)
    }
    LaunchedEffect(id, full) {
        // A video's whole file is the film, not a picture: its preview is as far as it goes.
        if (full && whole == null && !video) whole = Bitmaps.load(server, id, ImmichServer.FULLSIZE, 4096)
    }
    val shown = whole ?: preview ?: thumb
    LaunchedEffect(shown, box) {
        val b = shown ?: return@LaunchedEffect
        if (box.x <= 0f || box.y <= 0f) return@LaunchedEffect
        val k = min(box.x / b.width, box.y / b.height)
        onFitted(Offset(b.width * k, b.height * k))
    }
    Box(modifier.onSizeChanged { box = Offset(it.width.toFloat(), it.height.toFloat()) }, contentAlignment = Alignment.Center) {
        if (shown != null) {
            Image(shown, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        } else {
            Txt("Loading…", 14.sp, Ink.Faint, FontWeight.Medium, Modifier, 1)
        }
        if (video) {
            Box(Modifier.size(76.dp).clip(CircleShape).background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                Txt("▶", 30.sp, Color.White, FontWeight.Bold, Modifier.padding(start = 4.dp), 1)
            }
        }
    }
}

/** Tells a double tap from a single one: the single waits [TAP_WAIT_MS] and is called off by a second. */
private class TapClock {
    private var at = Offset.Zero
    private var time = 0L
    private var pending: Job? = null

    fun second(position: Offset): Boolean {
        val now = System.currentTimeMillis()
        val double = now - time < TAP_WAIT_MS && (position - at).getDistance() < 120f
        if (double) {
            pending?.cancel()
            time = 0L
        }
        return double
    }

    fun first(position: Offset, job: Job) {
        at = position
        time = System.currentTimeMillis()
        pending = job
    }
}

private fun Offset.isSpecified(): Boolean = x.isFinite() && y.isFinite()

private const val COLUMNS = 3
private const val MAX_ZOOM = 6f
private const val DOUBLE_TAP_ZOOM = 2.5f
private const val PAGE_SHARE = 0.18f
private const val LIFT_SHARE = 0.12f
private const val TAP_WAIT_MS = 280L
