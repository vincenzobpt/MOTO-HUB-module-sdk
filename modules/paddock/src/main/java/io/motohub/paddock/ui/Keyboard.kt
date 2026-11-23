// SPDX-License-Identifier: MPL-2.0
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
// of the MPL was not distributed with this file, you can obtain one at https://mozilla.org/MPL/2.0/.
package io.motohub.paddock.ui

import android.graphics.Rect
import android.view.ViewTreeObserver
import android.view.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView

/**
 * Bottom padding for the keyboard and the navigation bar, from where they really are on screen.
 *
 * navigationBarsPadding().imePadding() trusts that the window neither pans nor resizes for the
 * keyboard. On some phones it still does (the soft-input mode set while Paddock is shown is not
 * applied, or the app sets its own again), and the composer then sat a whole keyboard height
 * above the keys. Here the padding is how far this screen's bottom reaches below the window's
 * visible frame (above the keyboard and the navigation bar), whatever the window did.
 *
 * Some phones (OnePlus, OxygenOS 16) leave the keyboard out of the visible frame when the window
 * does not resize: the system's keyboard inset says where it is. The higher of the two wins.
 */
@Composable
internal fun Modifier.keyboardPadding(): Modifier {
    val view = LocalView.current
    val density = LocalDensity.current
    var visibleBottom by remember { mutableIntStateOf(-1) }
    var boxBottom by remember { mutableIntStateOf(-1) }
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val measure = {
        val r = Rect()
        view.getWindowVisibleDisplayFrame(r)
        val onScreen = IntArray(2).also { view.getLocationOnScreen(it) }
        val inWindow = IntArray(2).also { view.getLocationInWindow(it) }
        val windowTop = onScreen[1] - inWindow[1]
        val inset = view.rootWindowInsets?.getInsets(WindowInsets.Type.ime() or WindowInsets.Type.navigationBars())?.bottom ?: 0
        val byInsets = windowTop + view.rootView.height - inset
        val visible = minOf(r.bottom, byInsets)
        if (visible != visibleBottom) visibleBottom = visible
        coords[0]?.takeIf { it.isAttached }?.let { c ->
            // Window position on screen + this box's bottom in the window: its bottom on screen.
            val bottom = windowTop + (c.positionInWindow().y + c.size.height).toInt()
            if (bottom != boxBottom) boxBottom = bottom
        }
    }
    DisposableEffect(view) {
        val listener = ViewTreeObserver.OnGlobalLayoutListener { measure() }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }
    // Compose's own WindowInsets.ime is shrunk out of the app (checkModuleLinkage), and a window that
    // does not resize may not lay out again when the keyboard moves: look ten times a second. State
    // changes only when a number does, so a still screen does not recompose.
    LaunchedEffect(view) {
        while (true) {
            measure()
            kotlinx.coroutines.delay(100)
        }
    }
    val overlap = if (visibleBottom < 0 || boxBottom < 0) 0 else maxOf(0, boxBottom - visibleBottom)
    return this
        .onGloballyPositioned { c ->
            coords[0] = c
            measure()
        }
        .padding(bottom = with(density) { overlap.toDp() })
}
