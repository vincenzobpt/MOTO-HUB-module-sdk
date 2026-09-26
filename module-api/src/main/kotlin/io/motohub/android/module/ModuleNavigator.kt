// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Vincenzo Buonomano.
// Part of the MOTO-HUB module SDK; see module-api/LICENSE.
package io.motohub.android.module

import android.view.Surface
import androidx.car.app.Session

/**
 * The other direction from [ModuleProjection].
 *
 * [ModuleProjection] is the app playing head unit: a module hands it a picture and it puts that
 * on the motorcycle's own screen. This is the app being an app: the screen belongs to somebody
 * else's head unit - a Carpuride on the bars, a Chigee, a car - and what MOTO-HUB puts on it is
 * its own Ride Dashboard.
 *
 * The split is the same one the module system has everywhere. The module owns the protocol - here
 * that is the Car App Library, its templates and its host - and the app owns the dashboard. They
 * meet over a [Surface] and a handful of verbs, exactly as they do over the T-Box.
 *
 * Why any of it is in a module when a `CarAppService` has to be declared in the manifest: the
 * service is a shell, and everything it would say lives here. See
 * documentation/NAVIGATOR_MODULE_PLAN.md.
 */

/**
 * The capability a module offers when it can put the dashboard on somebody else's screen.
 *
 * [createSession] is called by the app's `CarAppService` for each display the host offers, and
 * returns the Car App Library [Session] that will drive it. [cluster] is true for the instrument
 * cluster behind the wheel, which takes no input and shows map tiles only - a module that does
 * not do clusters returns a session that says so rather than refusing, because the host may hand
 * over a cluster and nothing else.
 */
interface ModuleNavigator {
    fun createSession(host: MotoHubModuleHost, cluster: Boolean): Session
}

/**
 * What the app lends a module that has a screen to fill: the Ride Dashboard, drawn on a surface
 * the module got from somewhere the app knows nothing about.
 *
 * The dashboard is the app's - its widgets, its map, its telemetry, its trip recording. A module
 * never draws it and never sees inside it; it says where to draw and passes on what the rider
 * does. That is the whole of the boundary, and it is why a second head-unit protocol needs no
 * change here at all.
 */
interface ModuleDashboardHost {

    /**
     * Starts a dashboard on [surface].
     *
     * Null means the app cannot draw one right now, and it is an ordinary answer rather than a
     * failure: today a T-Box session already streaming is the reason, and the module is expected
     * to show the rider a message instead of a black screen. The reason reaches the module
     * through [ModuleDashboardListener.onStopped] on the same call, so there is exactly one place
     * that explains why there is no dashboard.
     *
     * [densityDpi] is the host's, not the phone's - a car screen at arm's length is not a phone
     * at arm's length, and the dashboard sizes its text from it.
     */
    fun attach(
        surface: Surface,
        width: Int,
        height: Int,
        densityDpi: Int,
        cluster: Boolean,
        listener: ModuleDashboardListener
    ): ModuleDashboardSession?

    /**
     * Sends the rider somewhere, as the head unit's assistant asked.
     *
     * False when the app could not take it - no route engine running, no fix yet. The module
     * turns that into whatever its host expects to hear; the app does not know what that is.
     */
    fun navigateTo(latitude: Double, longitude: Double, label: String): Boolean

    /**
     * Rides the active route without a motorcycle, or stops doing so.
     *
     * Android Auto asks a navigation app for exactly this - it is how a reviewer watches the app
     * navigate from a desk - and a module cannot provide it: the positions the dashboard and the
     * routing engine follow are the app's, on the far side of this boundary. So the module passes
     * the request through and the app drives.
     *
     * False when there is nothing to drive, which is the ordinary answer with no route running.
     * Turning it off always succeeds.
     */
    fun setAutoDrive(enabled: Boolean): Boolean

    /**
     * Ends the ride the app is guiding, as the head unit's own stop asked.
     *
     * The counterpart [navigateTo] needed from the first day and did not have: a host that stops
     * navigating tells the module to stop sending guidance, and until this existed that was all
     * that happened - the phone kept routing and kept speaking, which is half of what the rider
     * asked for and the confusing half.
     *
     * False when nothing was being navigated, which is not a failure.
     */
    fun stopNavigating(): Boolean

    /**
     * Sends the rider to a place named rather than located.
     *
     * A head unit's assistant answers "navigate to Kreuzberg" with `geo:0,0?q=Kreuzberg` - a name
     * and no coordinates - and only the app can turn that into a point: the geocoder, the rider's
     * own recent places and the network to reach them are all on its side.
     *
     * Like [navigateTo] this answers "will I try", not "have I arrived": the lookup and the
     * routing are both network round trips and the host wants an answer now.
     */
    fun navigateToQuery(query: String): Boolean

    /**
     * Kilometres or miles, as the rider set them in the app.
     *
     * The contract carries metres everywhere and always will - a unit is a presentation choice,
     * and the two sides would only disagree about rounding. But a head unit draws its own turn
     * card from what the module hands it, so the module has to know which unit to build a
     * `Distance` in, and the Car App Library exposes no preference of the host's own. Guessing
     * from the phone's locale is wrong for precisely the riders who care - a British rider in
     * France, an American anywhere.
     */
    fun distanceUnits(): ModuleDistanceUnits
}

/** What the rider reads distances in. The contract itself is always metres; see [ModuleDashboardHost.distanceUnits]. */
enum class ModuleDistanceUnits {
    KILOMETERS,
    MILES
}

/**
 * One dashboard the app is drawing for a module. Every call is safe after the surface has gone:
 * the module finds out asynchronously, and a late call must be dropped rather than crash a host
 * process the app does not own.
 */
interface ModuleDashboardSession {

    /**
     * The part of the surface nothing is drawn over right now, in surface pixels.
     *
     * The dashboard already knows how to draw inside a border it does not own - that is how a
     * rider's taught T-Box margins work - so this is the same fact from a different authority.
     */
    fun setVisibleArea(left: Int, top: Int, right: Int, bottom: Int)

    /** The part that is *always* visible, whatever the host draws. Anything that must never be
     * covered goes here rather than in the visible area. */
    fun setStableArea(left: Int, top: Int, right: Int, bottom: Int)

    fun setNightMode(night: Boolean)

    /** A tap at surface coordinates. */
    fun tap(x: Float, y: Float)

    /** A drag, in surface pixels since the last call. */
    fun scroll(distanceX: Float, distanceY: Float)

    /** A pinch: [factor] > 1 is a zoom in, about the focus point in surface coordinates. */
    fun scale(focusX: Float, focusY: Float, factor: Float)

    /** The three dashboard verbs the handlebar already has, offered to a head unit's controls. */
    fun cyclePanels()

    fun toggleFullscreenMap()

    fun toggleMapZoom()

    /** Stops this dashboard. Idempotent; the app also stops it if the process is going away. */
    fun detach()
}

/** How a module learns its dashboard is no longer being drawn, whoever decided that. */
interface ModuleDashboardListener {
    fun onStopped(reason: String)
}

/**
 * Where the rider is going, as the app's own navigation engine sees it.
 *
 * Deliberately not [ModuleGuidance]. That one travels the other way - a module telling the app
 * what the projected phone is doing, so the Navigation widget can draw it - and the two
 * directions want different facts: a head unit's trip card needs the destination and the road
 * being travelled, which a projected session never reports. Sharing one class would have meant
 * adding fields to it, and a data class constructor is a signature: a module built against the
 * old shape would find it gone.
 *
 * Distances in metres, times in seconds, -1 for "not known". [maneuverType] is the Car App
 * Library's own `Maneuver` type, which is what this is for; a module maps it or ignores it.
 */
data class ModuleRideGuidance(
    val active: Boolean,
    val rerouting: Boolean,
    val maneuverType: Int,
    val roundaboutExitNumber: Int,
    val currentRoad: String,
    val nextRoad: String,
    val destinationLabel: String,
    val distanceToManeuverMeters: Int,
    val distanceRemainingMeters: Int,
    val timeToArrivalSeconds: Long
)

fun interface ModuleRideGuidanceListener {
    fun onRideGuidance(guidance: ModuleRideGuidance)
}

/**
 * The app's navigation, offered to a module.
 *
 * A head unit expects a navigation app to keep telling it what the next turn is, so it can draw
 * its own turn card and light its own cluster. The app has all of it already; before this there
 * was simply no way to ask.
 */
interface ModuleGuidanceSource {
    /** Replaces whatever listener was installed. Null clears it. Called on any thread. */
    fun setListener(listener: ModuleRideGuidanceListener?)
}
