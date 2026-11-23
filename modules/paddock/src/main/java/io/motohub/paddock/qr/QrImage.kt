// SPDX-License-Identifier: MPL-2.0
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
// of the MPL was not distributed with this file, you can obtain one at https://mozilla.org/MPL/2.0/.
package io.motohub.paddock.qr

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableTarget
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.motohub.android.module.ModuleUi
import kotlin.math.floor
import kotlin.math.min

/**
 * The QR matrix for [text]: error correction M, no quiet zone (the caller pads). Square, one cell
 * per module. Throws com.google.zxing.WriterException if the text is too long for a QR code.
 */
fun qrMatrix(text: String): BitMatrix {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 0,
        EncodeHintType.CHARACTER_SET to "UTF-8"
    )
    // Requested size 0x0: the writer then returns exactly one cell per module.
    return QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
}

/**
 * A QR code drawn as crisp squares: the module size is a whole number of pixels, the code is
 * centred in the box, and [light] fills the rest of the box. Text too long to encode draws only
 * the [light] ground.
 */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
fun QrImage(text: String, modifier: Modifier, dark: Color, light: Color) {
    val matrix = remember(text) { try { qrMatrix(text) } catch (e: Exception) { null } }
    Canvas(modifier = modifier) {
        drawRect(color = light, size = size)
        val m = matrix ?: return@Canvas
        val n = m.width
        val raw = min(size.width, size.height) / n
        // Whole pixels per module when there is room; fractional only below 1 px per module.
        val cell = if (raw >= 1f) floor(raw) else raw
        val ox = floor((size.width - cell * n) / 2f)
        val oy = floor((size.height - cell * n) / 2f)
        for (y in 0 until m.height) {
            var x = 0
            while (x < n) {
                if (!m.get(x, y)) { x++; continue }
                val start = x
                while (x < n && m.get(x, y)) x++
                drawRect(
                    color = dark,
                    topLeft = Offset(ox + start * cell, oy + y * cell),
                    size = Size((x - start) * cell, cell)
                )
            }
        }
    }
}
