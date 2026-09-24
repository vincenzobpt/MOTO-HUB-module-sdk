// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Vincenzo Buonomano.
// Part of the MOTO-HUB module SDK; see module-api/LICENSE.
package io.motohub.android.module

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableTarget

/**
 * The app's own visual language, lent to a module's screens.
 *
 * A module can draw with plain Compose, and the first one that did looked exactly like what it
 * was: a foreign page bolted into the app - different type scale, different spacing, no back
 * link where a rider expects one. Every module would have reproduced that badly and differently,
 * because a module cannot reach the app's components: they live in the app, under names R8
 * rewrites, and nothing exports them.
 *
 * So the app hands over the shapes instead of the code. These are declared here, in the package
 * R8 keeps whole, and implemented by the app - which means a module's screen picks up a change to
 * the app's look without being rebuilt, and a module author gets a screen that fits by default
 * rather than by effort.
 *
 * Deliberately small. This is a vocabulary for saying things about a module - what it is, what it
 * is doing, what a rider can do about it - not a general UI toolkit. A module that needs to draw
 * something genuinely its own still has all of Compose.
 *
 * No default arguments anywhere, deliberately. Kotlin compiles a default into a synthetic
 * `$default` bridge on the interface, which then calls the implementation through a signature
 * carrying an extra mask parameter - and the module and the app compile that bridge separately,
 * on either side of a boundary neither can see across. The first version of this had one default
 * (`technical = false`) and it was an AbstractMethodError the moment a module drew a Fact.
 *
 * Every function states its applier explicitly. Compose infers that from the declaration's source
 * file, and these declarations reach a module as a compiled dependency with no source file at all
 * - which crashes the compiler outright ("Unknown file") rather than falling back. Saying it here
 * is also simply true: these draw into the UI tree like any other composable.
 */
interface ModuleUi {

    /**
     * A full screen with a back link and a title, scrolling its content.
     *
     * [onBack] is the one the host handed the feature: a module never decides what "back" means,
     * because it does not know what it was opened from.
     */
    @Composable
    @ComposableTarget(UI_APPLIER)
    fun Screen(title: String, onBack: () -> Unit, content: @Composable @ComposableTarget(UI_APPLIER) () -> Unit)

    /** A short paragraph of explanation, in the app's secondary text style. */
    @Composable
    @ComposableTarget(UI_APPLIER)
    fun Paragraph(text: String)

    /** A small heading that separates one group of rows from the next. */
    @Composable
    @ComposableTarget(UI_APPLIER)
    fun SectionLabel(text: String)

    /**
     * One fact, as a label and its value. For things a rider reads rather than taps.
     *
     * [technical] marks a value that only means something to whoever wrote the module - a port, a
     * path, a protocol version. The app renders those differently so a rider can tell at a glance
     * which lines are for them, instead of a page of equally-weighted text where two lines matter
     * and eight are noise. Always stated: see the note about defaults above.
     */
    @Composable
    @ComposableTarget(UI_APPLIER)
    fun Fact(label: String, value: String, technical: Boolean)

    /** A tappable row that opens something else, with a title and a line saying what it is. */
    @Composable
    @ComposableTarget(UI_APPLIER)
    fun ActionRow(title: String, description: String, onClick: () -> Unit)

    /** The screen's main action, drawn as the app draws its own. */
    @Composable
    @ComposableTarget(UI_APPLIER)
    fun PrimaryButton(text: String, enabled: Boolean, onClick: () -> Unit)

    companion object {
        /** The applier every one of these draws into: the ordinary Compose UI tree. */
        const val UI_APPLIER = "androidx.compose.ui.UiComposable"
    }
}
