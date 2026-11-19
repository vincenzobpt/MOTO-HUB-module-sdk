// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Vincenzo Buonomano.
// Part of the MOTO-HUB module SDK; see module-api/LICENSE.
package io.motohub.android.module

/**
 * Voice control's mind, from a module (contract 40).
 *
 * The app keeps what Android wants inside it: the microphone and its foreground service, the
 * speech services, the spoken voice, the panel on the screens, app control through Accessibility,
 * and every action a command runs. The module decides: which languages it speaks, what was said,
 * what to do about it, what to answer and when to ask first. So voice control gets better with a
 * module update, without a new app.
 *
 * Every call may block (the AI, the manual, a place search): the app calls from its listening
 * thread, never the main one.
 */
interface ModuleVoiceBrain {

    /** The languages it speaks, the first one the default. */
    fun languages(): List<ModuleVoiceLanguage>

    /**
     * True when the offline recogniser's result - `{"result":[{"word","conf"}...]}`, against
     * [ModuleVoiceLanguage.wakeGrammar] - is the wake phrase.
     */
    fun isWake(languageId: String, resultJson: String): Boolean

    /**
     * The answer to "Confirm?" in the offline recogniser's result against
     * [ModuleVoiceLanguage.answerGrammar]: true for yes, false for no, null for neither.
     */
    fun answer(languageId: String, resultJson: String): Boolean?

    /**
     * What to do about one request: [alternatives] are the online speech service's guesses, best
     * first. [host] is this conversation's view of the app.
     */
    fun interpret(languageId: String, alternatives: List<String>, host: ModuleVoiceHost): ModuleVoiceTurn

    /**
     * What to say after [action] ran with [outcome] (one of [ModuleVoiceOutcome]); null says the
     * language's plain "done" or "failed".
     */
    fun afterAction(languageId: String, action: ModuleVoiceAction, outcome: String): String?
}

/**
 * One language: its offline model, the words its grammars hold, and the few lines the app says
 * on its own - [texts], keyed by [ModuleVoiceText].
 */
class ModuleVoiceLanguage(
    /** Stable id, stored in the app's settings: "it", "en". */
    val id: String,
    /** BCP 47 tag for the online speech service and the spoken voice: "it-IT". */
    val localeTag: String,
    /** The language in its own name: "Italiano". */
    val displayName: String,
    /** The offline model: a zip the app downloads once and unpacks into a folder named [modelName]. */
    val modelName: String,
    val modelUrl: String,
    val modelMegabytes: Int,
    /** The wake recogniser's grammar, phrases as they are ("hey moto hub"), "[unk]" left out. */
    val wakeGrammar: List<String>,
    /** The yes/no recogniser's grammar, phrases as they are ("va bene"), "[unk]" left out. */
    val answerGrammar: List<String>,
    val texts: Map<String, String>
)

/** The keys of [ModuleVoiceLanguage.texts]: what the app says without asking the module. */
object ModuleVoiceText {
    /** Shown when "Hey Motohub" is heard: "Listening…". */
    const val LISTENING = "listening"
    /** Shown while [ModuleVoiceBrain.interpret] works: "Thinking…". */
    const val THINKING = "thinking"
    /** Said after a question that asks first: "Confirm?". */
    const val CONFIRM = "confirm"
    const val DONE = "done"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
    /** The online speech service has no internet. */
    const val NO_NETWORK = "noNetwork"
    /** The speech service heard words it could not make out. */
    const val NOT_UNDERSTOOD = "notUnderstood"
}

/** What one request comes to. */
sealed class ModuleVoiceTurn {
    /** Shows [shown] and says [spoken] (null: says nothing), then listens for the wake phrase again. */
    class Say(val shown: String, val spoken: String? = shown) : ModuleVoiceTurn()

    /** Runs [action] now, then says [ModuleVoiceBrain.afterAction]; [shown] is the panel's line meanwhile. */
    class Run(val action: ModuleVoiceAction, val shown: String) : ModuleVoiceTurn()

    /** Says [prompt] and "Confirm?", then runs [action] on a yes, and says "cancelled" on a no. */
    class Confirm(val prompt: String, val action: ModuleVoiceAction) : ModuleVoiceTurn()

    /** Nothing to say: the panel closes. */
    object Silent : ModuleVoiceTurn()
}

/**
 * One of the app's actions, by [id] (see [ModuleVoiceActions]) with its [args]. The app refuses
 * an id it does not know with [ModuleVoiceOutcome.UNKNOWN], so a module newer than the app degrades.
 */
class ModuleVoiceAction(val id: String, val args: Map<String, String> = emptyMap()) {
    override fun toString() = if (args.isEmpty()) id else "$id$args"
}

/** The actions an app of contract 40 runs. Arguments are named after the constant. */
object ModuleVoiceActions {
    const val MEDIA_PLAY_PAUSE = "media.playPause"
    const val MEDIA_NEXT = "media.next"
    const val MEDIA_PREVIOUS = "media.previous"
    const val MEDIA_VOLUME_UP = "media.volumeUp"
    const val MEDIA_VOLUME_DOWN = "media.volumeDown"
    const val MEDIA_MUTE = "media.mute"
    const val MEDIA_UNMUTE = "media.unmute"
    /** Arg "percent": 0-100. */
    const val MEDIA_VOLUME_SET = "media.volumeSet"

    const val DASH_NEXT_PANEL = "dash.nextPanel"
    const val DASH_FULLSCREEN_MAP = "dash.fullscreenMap"
    const val DASH_MAP_ZOOM = "dash.mapZoom"
    const val DASH_NEXT = "dash.next"
    const val DASH_PREVIOUS = "dash.previous"
    const val DASH_NIGHT = "dash.night"
    const val LAP_MARK = "lap.mark"
    const val LAP_RESET = "lap.reset"
    const val SENSOR_LEVEL = "sensor.level"

    const val NAV_HOME = "nav.home"
    const val NAV_WORK = "nav.work"
    const val NAV_STOP = "nav.stop"
    /** Args "latitude", "longitude", "label". */
    const val NAV_POINT = "nav.point"
    /** Arg "query": a town, an address, a landmark, searched when it runs. */
    const val NAV_QUERY = "nav.query"

    const val TRIP_START = "trip.start"
    const val TRIP_STOP = "trip.stop"

    const val BIKE_CONNECT = "bike.connect"
    const val BIKE_DISCONNECT = "bike.disconnect"

    /**
     * Arg "mode": what the Ride page starts on the motorcycle's screen - "selected" (the map, as the
     * page has it chosen), "dashboard", "map_app", "maplibre", "openstreetmap", "pov_3d",
     * "projection" or "mirroring". Connects first when needed; "selected" with a mode already
     * running brings its map back over an app.
     */
    const val MODE_START = "mode.start"

    /** Arg "package": an app from [ModuleVoiceHost.apps]; "label", its name, for what is said after. */
    const val APP_OPEN = "app.open"
    /** The map back in the dashboard's panel, or the dashboard back from its app on a head unit. */
    const val APP_CLOSE = "app.close"
    const val APP_BACK = "app.back"
    /** Arg "forward": "true" scrolls down. */
    const val APP_SCROLL = "app.scroll"
    /** Arg "index": 1-based, into the last [ModuleVoiceHost.screen]. */
    const val APP_TAP = "app.tap"
    /** Arg "text": typed into the search of the last [ModuleVoiceHost.screen]. */
    const val APP_SEARCH = "app.search"

    /** Turns "Hey Motohub" off, after the answer is said. */
    const val VOICE_STOP = "voice.stop"

    /** Arg "id": one of [ModuleVoiceHost.pages]; "title", what is said after (contract 41). */
    const val PAGE_OPEN = "page.open"

    /** Marks the request before this one as wrong in the app's voice journal (contract 41). */
    const val VOICE_MARK_WRONG = "voice.markWrong"
}

/** What [ModuleVoiceHost.run] reports. */
object ModuleVoiceOutcome {
    const val DONE = "done"
    const val FAILED = "failed"
    /** The app does not know the action: a module newer than the app. */
    const val UNKNOWN = "unknown"
    const val ALREADY_RECORDING = "alreadyRecording"
    const val NOT_RECORDING = "notRecording"
    const val APP_CONTROL_OFF = "appControlOff"
    const val ALREADY_CONNECTED = "alreadyConnected"
    const val NOT_CONNECTED = "notConnected"
    const val NO_BIKE = "noBike"
    /** The mode asked for is already on the screen. */
    const val ALREADY_RUNNING = "alreadyRunning"
    /** Home or Work is not saved. */
    const val NO_PLACE = "noPlace"
    /** The phone is the dashboard: a page of the app would cover it (contract 41). */
    const val PHONE_IS_DASHBOARD = "phoneIsDashboard"
}

/** The app as one conversation sees it. */
interface ModuleVoiceHost {

    /** Runs [action] and says how it went, one of [ModuleVoiceOutcome]. Only for [ModuleVoiceTurn]s the app runs itself. */
    fun run(action: ModuleVoiceAction): String

    /** The app's live readings as plain English facts: GPS, OBD, sensors, phone, weather, route, trip, date and time. */
    fun readings(): String

    /** The app on screen, read now (app control on): null when there is none or app control is off. */
    fun screen(): ModuleVoiceScreen?

    /** Whether app control (Accessibility, Settings 7.5.3) is on. */
    fun appControlOn(): Boolean

    /** The apps the rider can open, MOTO-HUB left out. */
    fun apps(): List<ModuleVoiceApp>

    /** Home, Work and the named favourites. */
    fun savedPlaces(): List<ModuleVoicePlace>

    /** The route being followed: destination and kilometres to go; null when not navigating. */
    fun route(): ModuleVoiceRoute?

    /** Connected to the motorcycle or a head unit in any way, a running mode included. */
    fun linked(): Boolean

    /** Real places of the kind [query] names, nearest first, none closer than [minMeters]; widened when there are none. */
    fun nearby(query: String, minMeters: Double): List<ModuleVoicePlace>

    /** Where [query] is, found as navigation finds it, near the rider; null when nowhere. */
    fun find(query: String): ModuleVoicePlace?

    /** Straight-line metres from the rider to a point; null without a position. */
    fun distanceFromHere(latitude: Double, longitude: Double): Double?

    /** Metres by road from the rider to a point, routed as navigation would; null when it cannot be routed. */
    fun roadMetresTo(latitude: Double, longitude: Double): Double? = null

    /** Something is on the motorcycle's or a head unit's screen, so the dashboard's own actions can work. */
    fun modeRunning(): Boolean = linked()

    /**
     * [url]'s body as text, over the phone's mobile data when the motorcycle's Wi-Fi has no
     * internet; throws when it cannot be fetched.
     */
    fun fetch(url: String): String

    /** The last thing said, for "repeat"; null before anything. */
    fun lastSpoken(): String?

    /** MOTO-HUB's own screens [ModuleVoiceActions.PAGE_OPEN] can open, available ones only (contract 41). */
    fun pages(): List<ModuleVoicePage> = emptyList()
}

class ModuleVoiceScreen(
    val packageName: String,
    val appLabel: String,
    /** Top to bottom; [ModuleVoiceActions.APP_TAP]'s index is 1-based into this. */
    val elements: List<ModuleVoiceElement>
)

class ModuleVoiceElement(val label: String, val editable: Boolean)

class ModuleVoiceApp(val packageName: String, val label: String)

/**
 * One of MOTO-HUB's screens: [id] is "tab.<name>" or "settings.<number>"; [title] is how the app
 * names it, in the app's language, with where it lives: "7.5 AI assistant › Voice control".
 */
class ModuleVoicePage(val id: String, val title: String)

/** A place: [kind] is "home", "work", "favourite" or "found"; [distanceMeters] NaN when unknown. */
class ModuleVoicePlace(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val kind: String,
    val distanceMeters: Double = Double.NaN
)

class ModuleVoiceRoute(val destination: String, val kilometresToGo: Double)
