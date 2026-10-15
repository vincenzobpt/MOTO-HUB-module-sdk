// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.immich

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableTarget
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.motohub.android.module.ModuleUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The module's colours: Immich's indigo on a dark ground, the same on any app theme. */
internal object Ink {
    val Accent = Color(0xFF7B8CFF)
    val AccentDeep = Color(0xFF4250AF)
    val Ground = Color(0xFF0B0D12)
    val Panel = Color(0xFF151922)
    val PanelHigh = Color(0xFF1E2430)
    val Line = Color(0x1FFFFFFF)
    val Text = Color(0xFFF2F4F8)
    val Muted = Color(0xA6E6EAF2)
    val Faint = Color(0x66E6EAF2)
    val Good = Color(0xFF3DDC97)
    val Bad = Color(0xFFFF6B6B)
    val Gold = Color(0xFFFFC94D)
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun Txt(
    text: String,
    size: TextUnit,
    color: Color,
    weight: FontWeight,
    modifier: Modifier,
    lines: Int
) {
    BasicText(
        text,
        modifier = modifier,
        style = TextStyle(color = color, fontSize = size, fontWeight = weight, lineHeight = size * 1.3f),
        maxLines = lines,
        overflow = TextOverflow.Ellipsis
    )
}

internal fun Modifier.card(color: Color = Ink.Panel, radius: Dp = 20.dp): Modifier =
    clip(RoundedCornerShape(radius)).background(color).border(1.dp, Ink.Line, RoundedCornerShape(radius))

/** A section title over a card: small, spaced capitals. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun Eyebrow(text: String, modifier: Modifier) {
    Txt(text.uppercase(), 12.sp, Ink.Faint, FontWeight.Bold, modifier.padding(start = 4.dp, top = 18.dp, bottom = 8.dp), 1)
}

/** One line of a card: what on the left, the value on the right. Nothing at all when there is no value. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun InfoLine(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 9.dp), verticalAlignment = Alignment.Top) {
        Txt(label, 14.sp, Ink.Muted, FontWeight.Normal, Modifier.width(112.dp), 1)
        Txt(value, 15.sp, Ink.Text, FontWeight.Medium, Modifier.weight(1f), 4)
    }
}

/** A small rounded word: "Favourite", "Live photo", a person, an album. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun Chip(text: String, color: Color, fill: Color) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(fill).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Txt(text, 13.sp, color, FontWeight.SemiBold, Modifier, 1)
    }
}

/** The big filled button. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun MainButton(text: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(54.dp).clip(RoundedCornerShape(16.dp))
            .background(if (enabled) Ink.AccentDeep else Ink.PanelHigh)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Txt(text, 16.sp, if (enabled) Color.White else Ink.Faint, FontWeight.Bold, Modifier, 1)
    }
}

/** The quiet outlined button beside it. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun SideButton(text: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(54.dp).clip(RoundedCornerShape(16.dp))
            .border(1.5.dp, if (enabled) Ink.Accent else Ink.Line, RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Txt(text, 16.sp, if (enabled) Ink.Accent else Ink.Faint, FontWeight.Bold, Modifier, 1)
    }
}

/** A round glass key over a photo: back, info, the grid. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun GlassKey(symbol: String, lit: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.padding(4.dp).clip(RoundedCornerShape(50)).background(if (lit) Ink.AccentDeep else Color(0x73000000))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Txt(symbol, 20.sp, Color.White, FontWeight.Bold, Modifier, 1)
    }
}

/**
 * Decoded images, a few at a time: swiping back to the photo just seen must not fetch and decode
 * it again, and a phone's memory is not a disk cache.
 */
internal object Bitmaps {
    private val cache = object : LruCache<String, ImageBitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    fun held(id: String, size: String): ImageBitmap? = cache.get("$size/$id")

    fun clear() = cache.evictAll()

    /** Fetched and decoded off the main thread, at most [maxSide] pixels on the long side. */
    suspend fun load(server: ImmichServer, id: String, size: String, maxSide: Int): ImageBitmap? {
        held(id, size)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val bytes = server.image(id, size) ?: return@runCatching null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?.asImageBitmap()
            }.getOrNull()?.also { cache.put("$size/$id", it) }
        }
    }
}

/** A photo's thumbnail, square-cropped; a dark tile until it arrives. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun Thumb(server: ImmichServer, id: String, modifier: Modifier) {
    var bitmap by remember(id) { mutableStateOf(Bitmaps.held(id, ImmichServer.THUMBNAIL)) }
    LaunchedEffect(id) {
        if (bitmap == null) bitmap = Bitmaps.load(server, id, ImmichServer.THUMBNAIL, 512)
    }
    Box(modifier.background(Ink.PanelHigh)) {
        bitmap?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

/** A card holding lines, with a little air above and below. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun LinesCard(content: @Composable @ComposableTarget(ModuleUi.UI_APPLIER) () -> Unit) {
    Column(Modifier.fillMaxWidth().card().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) { content() }
}
