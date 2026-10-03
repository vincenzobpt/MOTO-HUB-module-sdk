// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.routesim.ui

import io.motohub.android.routesim.sim.RideStyle
import io.motohub.android.routesim.sim.TrafficLevel
import java.util.Locale

/**
 * Every word the module shows, in one place.
 *
 * English only for now. The screens never write a sentence of their own: they ask for it here,
 * so another language is a second implementation of this object and no change to the UI.
 */
internal object Strings {

    // The module's card under Modules, and the entry offered inside Flyby.
    const val MODULE_NAME = "Flyby Route Simulator"
    const val ABOUT_TITLE = "Route Simulator"
    const val ABOUT_DESCRIPTION = "Plan a route and ride it virtually, to make a flyby of it"
    const val SIMULATE_TITLE = "Simulate a ride"
    const val SIMULATE_DESCRIPTION = "Plan a route and generate a ride to make a flyby of"
    const val ABOUT_BLURB =
        "Plan a route on the map, choose when and how it is ridden, and the module rides it " +
            "virtually: speeds that follow the real limits and the bends, lean, engine speed, " +
            "stops in traffic. The result is saved in TRIPS, marked as simulated, ready for a flyby."
    const val ABOUT_NOW = "Right now"
    const val ABOUT_START = "Plan a ride"
    const val ABOUT_START_HINT = "Pick the places, the day and the riding style"
    const val ABOUT_FACT_FLYBY = "Flyby"
    const val ABOUT_FACT_PLANS = "Saved plans"
    const val ABOUT_FACT_NETWORK = "Needs"
    const val ABOUT_FACT_TUNED = "Tuned to your rides"
    const val ABOUT_CALIBRATE = "Learn from my rides"
    const val ABOUT_CALIBRATE_HINT = "Make the riding styles follow the way you ride"
    const val YES = "Yes"
    const val NO = "No"
    const val ABOUT_NETWORK_VALUE = "An internet connection, to plan the route"
    const val INSTALLED = "Installed"
    const val NOT_INSTALLED = "Not installed"
    const val CHECKING = "Checking…"

    // Shared.
    const val BACK = "Back"
    const val CANCEL = "Cancel"
    const val CLOSE = "Close"

    // Planner.
    const val PLANNER_TITLE = "Plan a ride"
    const val PLANS_BUTTON = "Plans"
    const val MAP_BIGGER = "Bigger map"
    const val MAP_SMALLER = "Smaller map"
    const val MAP_HINT =
        "Long-press the map to drop a pin: the first is the start, the last the destination, " +
            "the ones between are places to pass through."
    const val STOPS_TITLE = "Route"
    const val STOPS_EMPTY = "No places yet. Long-press the map or search below."
    const val STOP_START = "Start"
    const val STOP_FINISH = "Finish"
    const val MOVE_UP = "Move up"
    const val MOVE_DOWN = "Move down"
    const val REMOVE_STOP = "Remove"
    const val CLEAR_ALL = "Clear all"

    const val SEARCH_TITLE = "Add a place"
    const val SEARCH_HINT = "Search a place, or paste lat, lon"
    const val SEARCHING = "Searching…"
    const val NO_RESULTS = "Nothing found. Try another spelling."
    const val SEARCH_TOO_SHORT = "Type at least 3 letters."

    const val WHEN_TITLE = "When"
    const val DATE_LABEL = "Date"
    const val TIME_LABEL = "Start time"
    const val DATE_HINT = "yyyy-mm-dd"
    const val TIME_HINT = "hh:mm"
    const val WHEN_NOTE = "Any day is fine, in the past too. Flyby places the sun from it."

    const val STYLE_TITLE = "Riding style"
    const val TRAFFIC_TITLE = "Traffic"
    const val ADVANCED_TITLE = "Advanced"
    const val ADVANCED_NOTE = "Starts from the style above, with your learned numbers if there are any. Choosing another style puts these back."
    const val ADVANCED_RESET = "Back to the style's values"

    const val GENERATE = "Generate"
    const val PREVIEW = "Preview"
    const val GENERATE_AND_OPEN = "Generate and open in Flyby"
    const val FLYBY_MISSING = "Flyby is not installed, so the ride cannot be opened there."
    const val FLYBY_CHECKING = "Checking for Flyby…"
    const val SAVE_PLAN = "Save this plan"
    const val NEED_TWO_STOPS = "Add at least a start and a destination."

    // Progress and results.
    const val PROGRESS_ROUTING = "Planning the route…"
    const val PROGRESS_SIMULATING = "Riding it virtually…"
    const val PROGRESS_SAVING = "Saving to TRIPS…"
    const val PROGRESS_OPENING = "Opening Flyby…"
    const val OFFLINE = "No internet connection. The route is planned online, so connect and try again."
    const val RATE_LIMITED = "The routing service is busy. Wait a minute and try again."
    const val NO_API_KEY = "The routing service needs a key. Set one in the NAV settings."
    const val GENERIC_FAILURE = "The route could not be planned."
    const val OPEN_IN_FLYBY = "Open in Flyby"
    const val FLYBY_OPEN_FAILED = "Flyby could not be opened. Find the ride in TRIPS."
    const val SAVE_FAILED = "The ride could not be saved."
    const val ROUTE_REMOVED = "The route is no longer available."

    // Preview.
    const val PREVIEW_TITLE = "Preview"
    const val REGENERATE = "Regenerate"
    const val SAVE = "Save to TRIPS"
    const val SAVING = "Saving…"
    const val SAVED = "Saved to TRIPS"
    const val PREVIEW_HINT = "Drag across a chart to move along the ride."
    const val FACT_DISTANCE = "Distance"
    const val FACT_DURATION = "Duration"
    const val FACT_AVERAGE = "Average"
    const val FACT_TOP = "Top speed"
    const val NO_DATA = "No data"
    const val CHART_SPEED = "Speed"
    const val CHART_ALTITUDE = "Altitude"
    const val CHART_LEAN = "Lean"
    const val CHART_RPM = "Engine"

    // Calibration: learning the rider's style from the recorded rides.
    const val CALIBRATE_TITLE = "Learn from my rides"
    const val CALIBRATE_DESCRIPTION = "Tune the simulated riding styles to the way you ride"
    const val CALIBRATE_LINK = "Calibrate"
    const val STYLE_TUNED = "Style: tuned to your rides"
    const val STYLE_GENERIC = "Generic style"
    const val CALIBRATE_STATUS_TITLE = "Your riding style"
    const val STATUS_GENERIC = "Generic style. Nothing has been learned yet."
    const val CALIBRATE_NETWORK_NOTE =
        "The speed limits of your latest rides are looked up online. Without a connection that part stays generic."
    const val LEARN_BUTTON = "Learn from my rides"
    const val BACK_TO_GENERIC = "Back to generic style"
    const val LEARN_STARTING = "Starting…"
    const val LEARNED_TITLE = "What was learned"
    const val IN_USE_TITLE = "What is in use"
    const val LEARNED_APPLIED = "Calm, Normal and Sporty now start from your numbers, each keeping its own character."
    const val LEARN_NOT_APPLIED = "Nothing was changed: the riding styles stay as they were."
    const val LEARN_SAVE_FAILED = "What was learned could not be stored, so the styles stay as they were."
    const val CLEARED = "Back to the generic style."
    const val CLEAR_FAILED = "The learned style could not be removed."

    // Saved plans.
    const val PLANS_TITLE = "Saved plans"
    const val PLANS_EMPTY = "No saved plans yet. Plan a ride and choose Save this plan."
    const val PLAN_OPEN = "Open"
    const val PLAN_DELETE = "Delete"
    const val PLAN_DELETE_CONFIRM = "Tap again to delete"
    const val PLAN_SAVE_AS_ROUTE = "Save as route in NAV"
    const val PLAN_ROUTE_SAVED = "Saved among your routes in NAV"
    const val PLAN_ROUTE_FAILED = "The route could not be saved in NAV."

    fun style(style: RideStyle): String = when (style) {
        RideStyle.CALM -> "Calm"
        RideStyle.NORMAL -> "Normal"
        RideStyle.SPORTY -> "Sporty"
    }

    fun styleNote(style: RideStyle): String = when (style) {
        RideStyle.CALM -> "Below the limits, gentle on the throttle and the brakes"
        RideStyle.NORMAL -> "About the limits, as most riders go"
        RideStyle.SPORTY -> "Above the limits, harder on the bends"
    }

    fun traffic(level: TrafficLevel): String = when (level) {
        TrafficLevel.NONE -> "None"
        TrafficLevel.LIGHT -> "Light"
        TrafficLevel.HEAVY -> "Heavy"
    }

    fun trafficNote(level: TrafficLevel): String = when (level) {
        TrafficLevel.NONE -> "An empty road"
        TrafficLevel.LIGHT -> "A few slowdowns and stops"
        TrafficLevel.HEAVY -> "Queues, lights, overtakes"
    }

    fun param(param: AdvancedParam): String = when (param) {
        AdvancedParam.ACCEL -> "Acceleration"
        AdvancedParam.BRAKE -> "Braking"
        AdvancedParam.LATERAL -> "Cornering force"
        AdvancedParam.OVER_LIMIT -> "Speed against the limit"
        AdvancedParam.MAX_LEAN -> "Maximum lean"
        AdvancedParam.MAX_RPM -> "Maximum engine speed"
        AdvancedParam.SHIFT_UP_RPM -> "Shift up at"
    }

    fun pinLabel(index: Int, count: Int): String = when {
        index == 0 -> "A"
        index == count - 1 -> "B"
        else -> index.toString()
    }

    fun stopRole(index: Int, count: Int): String = when {
        index == 0 -> STOP_START
        index == count - 1 -> STOP_FINISH
        else -> "Via $index"
    }

    fun droppedPin(latitude: Double, longitude: Double): String =
        String.format(Locale.US, "Pin at %.5f, %.5f", latitude, longitude)

    fun useCoordinates(latitude: Double, longitude: Double): String =
        String.format(Locale.US, "Use %.5f, %.5f", latitude, longitude)

    fun searchFailed(message: String?): String =
        if (message.isNullOrBlank()) "The search did not work. Check the connection." else message

    fun tooLong(maxKm: Int): String =
        "That route is longer than $maxKm km. Shorten it, or split it in two rides."

    fun unexpected(message: String?): String =
        if (message.isNullOrBlank()) "Something went wrong." else "Something went wrong: $message"

    fun savedToTrips(title: String): String = "Saved to TRIPS: $title"

    fun planSaved(name: String): String = "Plan saved: $name"

    fun planStops(count: Int): String = if (count == 1) "1 place" else "$count places"

    fun plansCount(count: Int): String = when (count) {
        0 -> "None yet"
        1 -> "1 plan"
        else -> "$count plans"
    }

    fun learnedOn(date: String): String = "Tuned to your rides, learned on $date"

    fun calibrateIntro(needed: Int, minKm: Int): String =
        "Reads your recorded rides, not simulated ones, and works out how you accelerate, brake, corner " +
            "and lean. It needs at least $needed rides of $minKm km or more. Nothing is learned unless you ask."

    fun cursorAt(clock: String): String = "at $clock"

    fun defaultPlanName(labels: List<String>): String {
        if (labels.isEmpty()) return "New plan"
        if (labels.size == 1) return labels[0]
        return labels[0] + " → " + labels[labels.size - 1]
    }
}
