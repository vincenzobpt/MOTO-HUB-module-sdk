// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// The small pieces every screen of the simulator is made of: colours, text, buttons, fields.
package io.motohub.android.routesim.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableTarget
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.motohub.android.module.ModuleUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** High contrast on purpose: it is read on a phone in the sun. */
internal object RsColors {
    val Bg = Color(0xFF0B0F14)
    val Surface = Color(0xFF151C24)
    val Surface2 = Color(0xFF1F2933)
    val Line = Color(0xFF3A4856)
    val Text = Color(0xFFF4F7FA)
    val Text2 = Color(0xFFC3CDD7)
    val Dim = Color(0xFF8F9DAB)
    val Accent = Color(0xFFFFB23F)
    val AccentInk = Color(0xFF1A1100)
    val Ok = Color(0xFF4FD08A)
    val Warn = Color(0xFFFFC94D)
    val Bad = Color(0xFFFF7A6E)
    val Blue = Color(0xFF6DB3FF)
}

internal val RsShape12 = RoundedCornerShape(12.dp)
internal val RsShape16 = RoundedCornerShape(16.dp)

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun Txt(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = RsColors.Text,
    size: Int = 16,
    weight: FontWeight = FontWeight.Normal,
    maxLines: Int = Int.MAX_VALUE,
    align: TextAlign? = null
) {
    Text(
        text, modifier = modifier, color = color, fontSize = size.sp, lineHeight = (size + 6).sp,
        fontWeight = weight, maxLines = maxLines, overflow = TextOverflow.Ellipsis, textAlign = align
    )
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun Card(modifier: Modifier = Modifier, content: @Composable @ComposableTarget(ModuleUi.UI_APPLIER) () -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RsShape16).background(RsColors.Surface).border(1.dp, RsColors.Line, RsShape16).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) { content() }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun SectionTitle(text: String) {
    Txt(text, color = RsColors.Dim, size = 14, weight = FontWeight.Bold)
}

/** A choice among a few, drawn as a block that is filled when chosen. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun Chip(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.heightIn(min = 48.dp).clip(RsShape12)
            .background(if (selected) RsColors.Accent else RsColors.Surface2)
            .border(1.dp, if (selected) RsColors.Accent else RsColors.Line, RsShape12)
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Txt(text, color = if (selected) RsColors.AccentInk else RsColors.Text, size = 15, weight = FontWeight.Bold, align = TextAlign.Center)
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun BigButton(text: String, modifier: Modifier, enabled: Boolean, primary: Boolean, onClick: () -> Unit) {
    val bg = if (!enabled) RsColors.Surface2 else if (primary) RsColors.Accent else RsColors.Surface2
    val fg = if (!enabled) RsColors.Dim else if (primary) RsColors.AccentInk else RsColors.Text
    Box(
        modifier.heightIn(min = 54.dp).clip(RsShape12).background(bg)
            .border(1.dp, if (enabled && primary) RsColors.Accent else RsColors.Line, RsShape12)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Txt(text, color = fg, size = 16, weight = FontWeight.Bold, align = TextAlign.Center)
    }
}

/** A text link-sized action, still a comfortable target. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun TextAction(text: String, color: Color, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 48.dp).clip(RsShape12).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Txt(text, color = if (enabled) color else RsColors.Dim, size = 15, weight = FontWeight.Bold)
    }
}

/** Icons drawn rather than typed: a font may not have them. */
internal enum class Glyph { UP, DOWN, CLOSE, BACK, CHEVRON_DOWN, CHEVRON_UP, PLUS, MINUS }

internal fun DrawScope.drawGlyph(glyph: Glyph, color: Color) {
    val w = size.width
    val h = size.height
    val stroke = Math.max(2f, Math.min(w, h) * 0.13f)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
        drawLine(color, Offset(w * x1, h * y1), Offset(w * x2, h * y2), stroke, StrokeCap.Round)
    }
    when (glyph) {
        Glyph.UP, Glyph.CHEVRON_UP -> { line(0.18f, 0.66f, 0.5f, 0.32f); line(0.5f, 0.32f, 0.82f, 0.66f) }
        Glyph.DOWN, Glyph.CHEVRON_DOWN -> { line(0.18f, 0.34f, 0.5f, 0.68f); line(0.5f, 0.68f, 0.82f, 0.34f) }
        Glyph.CLOSE -> { line(0.24f, 0.24f, 0.76f, 0.76f); line(0.76f, 0.24f, 0.24f, 0.76f) }
        Glyph.BACK -> { line(0.64f, 0.18f, 0.32f, 0.5f); line(0.32f, 0.5f, 0.64f, 0.82f) }
        Glyph.PLUS -> { line(0.5f, 0.2f, 0.5f, 0.8f); line(0.2f, 0.5f, 0.8f, 0.5f) }
        Glyph.MINUS -> line(0.2f, 0.5f, 0.8f, 0.5f)
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun IconBox(glyph: Glyph, boxSize: Dp, enabled: Boolean, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(boxSize).clip(RsShape12).background(RsColors.Surface2)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size((boxSize.value * 0.5f).dp)) { drawGlyph(glyph, if (enabled) tint else RsColors.Dim.copy(alpha = 0.4f)) }
    }
}

/** A button that steps once on a tap, then in a stream while the finger stays down. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun RepeatBox(glyph: Glyph, enabled: Boolean, onStep: () -> Unit) {
    val scope = rememberCoroutineScope()
    val step by rememberUpdatedState(onStep)
    Box(
        Modifier.size(48.dp).clip(RsShape12).background(RsColors.Surface2)
            .border(1.dp, if (enabled) RsColors.Accent.copy(alpha = 0.6f) else RsColors.Line, RsShape12)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    step()
                    val repeat = scope.launch {
                        delay(REPEAT_DELAY_MS)
                        while (true) {
                            step()
                            delay(REPEAT_MS)
                        }
                    }
                    tryAwaitRelease()
                    repeat.cancel()
                })
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(24.dp)) { drawGlyph(glyph, if (enabled) RsColors.Accent else RsColors.Dim.copy(alpha = 0.4f)) }
    }
}

private const val REPEAT_DELAY_MS = 450L
private const val REPEAT_MS = 70L

/** A label, a value between a minus and a plus. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun StepperRow(label: String, value: String, canMinus: Boolean, canPlus: Boolean, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt(label, modifier = Modifier.weight(1f), color = RsColors.Text2, size = 15)
        RepeatBox(Glyph.MINUS, canMinus, onMinus)
        Txt(value, modifier = Modifier.widthIn(min = 92.dp), size = 16, weight = FontWeight.Bold, align = TextAlign.Center)
        RepeatBox(Glyph.PLUS, canPlus, onPlus)
    }
}

/**
 * A single-line text field. Every parameter of the foundation function is given, none left to a
 * default: a default would go through a bridge the app may not have kept.
 */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun RsField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier, invalid: Boolean) {
    Box(
        modifier.heightIn(min = 52.dp).clip(RsShape12).background(RsColors.Surface)
            .border(1.dp, if (invalid) RsColors.Bad else RsColors.Line, RsShape12)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        BasicTextField(
            value,
            { onChange(it.replace('\n', ' ')) },
            Modifier.fillMaxWidth(),
            true,
            false,
            TextStyle(color = RsColors.Text, fontSize = 17.sp, lineHeight = 22.sp),
            KeyboardOptions.Default,
            KeyboardActions.Default,
            true,
            1,
            1,
            VisualTransformation.None,
            { _: TextLayoutResult -> },
            remember { MutableInteractionSource() },
            SolidColor(RsColors.Accent),
            { inner ->
                Box {
                    if (value.isEmpty()) Txt(hint, color = RsColors.Dim, size = 17, maxLines = 1)
                    inner()
                }
            }
        )
    }
}
