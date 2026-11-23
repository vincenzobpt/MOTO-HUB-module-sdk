// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Vincenzo Buonomano.
// Part of the MOTO-HUB module SDK; see module-api/LICENSE.
package io.motohub.android.module

/**
 * A shortcut a module puts on the Ride page (contract 46), under Connect, shown only while the
 * module has something to go back to: a group ride with rooms, say. Beside "Hey Motohub" when
 * the Voice module is installed, the whole width otherwise. A tap opens [featureId] full-screen,
 * as a feature opened from Modules, and its onBack returns to the Ride page.
 *
 * The app asks [shown], [subtitle], [badge], [alert], [alertUrgent] and [liveSinceMillis] about
 * every second while the Ride page shows (there is a timer), on the main thread: each must be
 * cheap - a field read, never a disk or network call. When several modules offer one, the
 * first by id that is [shown] wins. The app draws everything over the module's static [icon]:
 * the badge, the alert mark, the timer and the glow.
 */
interface ModuleRideShortcut {
    /** The id of the module's own feature ([ModuleFeatures]) the tap opens. */
    val featureId: String

    /** Whether the shortcut is on the Ride page now. False hides it, and gives the space back. */
    fun shown(): Boolean

    /** One short line, the module's own words: "Go to your Paddock". */
    fun title(): String

    /** Under the title: "Riding with Sunday group", "3 rooms". */
    fun subtitle(): String

    /** Unread messages, drawn as a number on the icon (above 99, "99+"). 0, the default, draws none. */
    fun badge(): Int = 0

    /**
     * A short live alert, "Luca: Stop" or "Anna: Fuel". Non-null, it takes the subtitle's place in
     * an amber accent and the icon carries a small alert mark. Null, the default, is no alert.
     */
    fun alert(): String? = null

    /** True for an emergency: the alert and its mark turn red and the mark pulses gently. */
    fun alertUrgent(): Boolean = false

    /**
     * Above 0, the module is sharing the rider's position live since this epoch time in
     * milliseconds: the card shows a running timer ("Sharing · 12:34", "1:05:12" past an hour)
     * and breathes a soft glow, so the rider sees at a glance they are broadcasting where they
     * are. 0, the default, is not sharing.
     */
    fun liveSinceMillis(): Long = 0L

    /**
     * A square picture about [sizePx] on a side, drawn by the module; null, the default, draws the
     * app's generic module glyph. Asked once, off the main thread, and kept.
     */
    fun icon(sizePx: Int): android.graphics.Bitmap? = null
}
