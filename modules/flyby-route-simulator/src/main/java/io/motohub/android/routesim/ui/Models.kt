// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// What the screens share: the module's environment, and the state of the planner, the preview and
// the saved plans. The screens draw it; these classes do the work, off the main thread.
package io.motohub.android.routesim.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.motohub.android.module.ModuleMap
import io.motohub.android.module.MotoHubModuleHost
import io.motohub.android.routesim.core.Plan
import io.motohub.android.routesim.core.PlanStore
import io.motohub.android.routesim.core.Prepare
import io.motohub.android.routesim.core.PreparedRoute
import io.motohub.android.routesim.core.PreviewSeries
import io.motohub.android.routesim.core.RideGenerator
import io.motohub.android.routesim.core.RideSettings
import io.motohub.android.routesim.core.GeneratedRide
import io.motohub.android.routesim.core.Save
import io.motohub.android.routesim.core.Stop
import io.motohub.android.routesim.core.cleanTitleOverride
import io.motohub.android.routesim.core.defaultStartMillis
import io.motohub.android.routesim.core.routePreferenceOf
import io.motohub.android.routesim.core.previewSeries
import io.motohub.android.routesim.experiences.ExperienceCatalog
import io.motohub.android.routesim.experiences.ExperienceRoutes
import io.motohub.android.routesim.experiences.FilterStore
import io.motohub.android.routesim.learn.Learner
import io.motohub.android.routesim.learn.LearnedProfile
import io.motohub.android.routesim.learn.LearnedProfileStore
import io.motohub.android.routesim.sim.RideStyle
import io.motohub.android.routesim.sim.TrafficLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** How many points the preview's charts and map line are drawn from. */
internal const val PREVIEW_POINTS = 500

/** The most points the planner's map line is drawn from. */
internal const val MAP_LINE_POINTS = 3000

/** How many differently coloured lines the preview's route is made of at most. */
internal const val SPEED_RUNS = 60

/**
 * What every screen of the module needs, made once per module: the generator, the plan store, the
 * module's own coroutine scope and the maps it has open.
 *
 * The scope is cancelled when the module is released, which is what stops a route that is still
 * being planned from saving a ride after the module was replaced.
 */
internal class RsEnv(val host: MotoHubModuleHost, val scope: CoroutineScope) {
    val generator = RideGenerator(host)
    val plans = PlanStore(host.storage)
    val learner = Learner(host)
    val learnedStore = LearnedProfileStore(host.storage)
    val zone: ZoneId get() = ZoneId.systemDefault()

    /** The experiences catalogue (the module's own packs and the downloaded ones) and the rider's filters. */
    val catalog = ExperienceCatalog(host.storage)
    val filterStore = FilterStore(host.storage)

    /**
     * Routes of the experiences, cached for as long as the module is loaded; made when first needed.
     * The sights found are also kept in the module's storage, so a new start does not ask the public
     * map servers again.
     */
    val experienceRoutes: ExperienceRoutes by lazy {
        ExperienceRoutes(host.routing, host.sights, storageDir = host.storage)
    }

    private val catalogUpdateClaimed = AtomicBoolean(false)

    /** True once per module load: the first page to ask is the one that refreshes the catalogue. */
    fun claimCatalogUpdate(): Boolean = catalogUpdateClaimed.compareAndSet(false, true)

    /** The refresh that was claimed could not reach the server: let the next page that opens try again. */
    fun releaseCatalogUpdate() {
        catalogUpdateClaimed.set(false)
    }

    /**
     * What the module learned from the rider's rides, or null for the generic styles. Read once
     * (a small file; a damaged one counts as none) and kept here, so every screen sees the same
     * answer the moment the calibration changes it.
     */
    var learned by mutableStateOf<LearnedProfile?>(loadLearned())

    /** Null until the first answer; Flyby may be installed or removed while the module is loaded. */
    var flybyInstalled by mutableStateOf<Boolean?>(null)

    private val openMaps = ArrayList<ModuleMap>()

    /** A map of the app's, or null if it could not be opened; closed by [closeMap] or at release. */
    fun openMap(): ModuleMap? {
        val map = try {
            host.maps.open()
        } catch (error: Throwable) {
            host.log.log("routesim: the map could not be opened: ${error.message}")
            return null
        }
        synchronized(openMaps) { openMaps.add(map) }
        return map
    }

    fun closeMap(map: ModuleMap) {
        val known = synchronized(openMaps) { openMaps.remove(map) }
        if (!known) return
        try { map.setListener(null) } catch (_: Throwable) { }
        try { map.close() } catch (_: Throwable) { }
    }

    fun closeAllMaps() {
        val all = synchronized(openMaps) {
            val copy = ArrayList(openMaps)
            openMaps.clear()
            copy
        }
        for (map in all) {
            try { map.setListener(null) } catch (_: Throwable) { }
            try { map.close() } catch (_: Throwable) { }
        }
    }

    private fun loadLearned(): LearnedProfile? = try { learnedStore.load() } catch (_: Throwable) { null }

    fun refreshFlyby() {
        scope.launch(Dispatchers.Default) {
            val installed = try { generator.flybyInstalled() } catch (_: Throwable) { false }
            withContext(Dispatchers.Main) { flybyInstalled = installed }
        }
    }
}

internal enum class RunMode { PREVIEW, SAVE, SAVE_AND_OPEN }

/** A ride that was saved to TRIPS from the planner, and what it was called. */
internal class SavedRide(val rideId: String, val title: String)

/** The planner: the stops, the settings, and the running of a generation. */
internal class PlannerModel(val env: RsEnv) {

    var stops by mutableStateOf<List<Stop>>(emptyList())
    var selected by mutableStateOf(-1)
    var settings by mutableStateOf(initialSettings())

    /** Bumped whenever the map should frame the stops or the route again. */
    var fitVersion by mutableStateOf(0)

    var busy by mutableStateOf(false)
    var progress by mutableStateOf("")
    var message by mutableStateOf("")
    var messageIsError by mutableStateOf(false)
    var savedRide by mutableStateOf<SavedRide?>(null)

    /** The preview of the ride just generated, while its screen is up. */
    var preview by mutableStateOf<PreviewModel?>(null)

    /** The route planned for the stops and preference whose key is [preparedKey]; kept so a regeneration costs no network. */
    var prepared by mutableStateOf<PreparedRoute?>(null)
    var preparedKey by mutableStateOf("")

    /** What the ride is called instead of "A → B": the experience the stops came from. Null for a plan made by hand. */
    var titleOverride by mutableStateOf<String?>(null)

    /**
     * The road is planned the scenic way, as the experience's card was: true while the stops are an
     * experience's. Editing the stops keeps it, as it keeps the title; "Clear all" and a blank plan reset it.
     */
    var scenic by mutableStateOf(false)

    private var planId: String? = null
    private var planName: String? = null
    private var job: Job? = null

    /** Counts the runs, so a run that was cancelled cannot report over the one that came after it. */
    @Volatile
    private var generation = 0

    private fun initialSettings(): RideSettings = RideSettings(
        startAtMillis = defaultStartMillis(System.currentTimeMillis(), env.zone),
        style = RideStyle.NORMAL,
        traffic = TrafficLevel.LIGHT,
        profile = StyleBase.profile(RideStyle.NORMAL, env.learned)
    )

    fun stopsKey(list: List<Stop>): String {
        val lats = DoubleArray(list.size) { list[it].latitude }
        val lons = DoubleArray(list.size) { list[it].longitude }
        return coordinatesKey(lats, lons)
    }

    /** The route to draw: the planned one, while it still belongs to the stops. */
    fun routeToShow(): PreparedRoute? {
        val route = prepared ?: return null
        return if (preparedKey == ExperiencesLogic.preparedKeyFor(stopsKey(stops), scenic)) route else null
    }

    // ---- stops

    private fun stopsChanged() {
        savedRide = null
        message = ""
    }

    fun addStop(stop: Stop) {
        stops = ArrayList(stops).apply { add(stop) }
        selected = stops.size - 1
        fitVersion++
        stopsChanged()
    }

    /** A pin dropped on the map: named by its coordinates at once, by the place when that is known. */
    fun addStopAt(latitude: Double, longitude: Double) {
        addStop(Stop(latitude, longitude, Strings.droppedPin(latitude, longitude)))
        env.scope.launch(Dispatchers.Default) {
            val name = try { env.host.places.reverse(latitude, longitude) } catch (_: Throwable) { null }
            if (name.isNullOrBlank()) return@launch
            withContext(Dispatchers.Main) {
                val index = indexOfStopAt(latitude, longitude)
                if (index >= 0) {
                    val copy = ArrayList(stops)
                    copy[index] = Stop(latitude, longitude, name)
                    stops = copy
                }
            }
        }
    }

    private fun indexOfStopAt(latitude: Double, longitude: Double): Int {
        for (i in stops.indices) {
            if (stops[i].latitude == latitude && stops[i].longitude == longitude) return i
        }
        return -1
    }

    fun removeAt(index: Int) {
        if (index < 0 || index >= stops.size) return
        val copy = ArrayList(stops)
        copy.removeAt(index)
        stops = copy
        selected = -1
        fitVersion++
        stopsChanged()
    }

    fun move(index: Int, delta: Int) {
        val to = index + delta
        if (index < 0 || index >= stops.size || to < 0 || to >= stops.size) return
        val copy = ArrayList(stops)
        val stop = copy.removeAt(index)
        copy.add(to, stop)
        stops = copy
        selected = to
        stopsChanged()
    }

    fun clearStops() {
        stops = emptyList()
        selected = -1
        planId = null
        planName = null
        titleOverride = null
        scenic = false
        stopsChanged()
    }

    /** A new, empty plan, as the planner has always opened; whatever was running stops. */
    fun startBlank() {
        cancelRun()
        clearStops()
        fitVersion++
    }

    /**
     * "Plan by hand": the planner as the rider left it when it holds stops of their own making (nothing
     * is cleared, nothing is cancelled); a blank plan when it is empty or holds an experience's stops.
     */
    fun planByHand() {
        if (ExperiencesLogic.byHandKeepsPlanner(stops.isNotEmpty(), titleOverride)) return
        startBlank()
    }

    /**
     * A new plan from an experience: its stops, and its name as the ride's title. The rider sees the
     * stops and can edit them; [OpenPlan.note] (a detour that could not be had, no connection) is shown as a notice.
     */
    fun openExperience(plan: OpenPlan) {
        cancelRun()
        stops = ArrayList(plan.stops)
        selected = -1
        planId = null
        planName = null
        titleOverride = cleanTitleOverride(plan.title)
        scenic = true
        fitVersion++
        stopsChanged()
        plan.note?.let { notice(it, false) }
    }

    fun select(index: Int) {
        selected = if (index >= 0 && index < stops.size) index else -1
    }

    // ---- settings

    fun setStyle(style: RideStyle) {
        if (settings.style == style) return
        settings = settings.copy(style = style, profile = StyleBase.profile(style, env.learned))
        learnedBase = env.learned
    }

    /**
     * The learned profile the settings' profile was built from. When the calibration has changed
     * since, a profile the rider has not edited is rebuilt from the new one; an edited one stays.
     */
    private var learnedBase: LearnedProfile? = env.learned

    fun syncLearned() {
        val now = env.learned
        if (now === learnedBase) return
        val current = settings
        if (StyleBase.isUntouched(current.profile, current.style, learnedBase)) {
            settings = current.copy(profile = StyleBase.profile(current.style, now))
        }
        learnedBase = now
    }

    fun setTraffic(level: TrafficLevel) {
        settings = settings.copy(traffic = level)
    }

    fun setStart(millis: Long) {
        settings = settings.copy(startAtMillis = millis)
    }

    fun nudgeParam(param: AdvancedParam, direction: Int) {
        settings = settings.copy(profile = param.nudge(settings.profile, direction))
    }

    fun resetProfile() {
        settings = settings.copy(profile = StyleBase.profile(settings.style, env.learned))
        learnedBase = env.learned
    }

    // ---- plans

    fun load(plan: Plan) {
        stops = ArrayList(plan.stops)
        settings = plan.settings
        selected = -1
        planId = plan.id
        planName = plan.name
        titleOverride = plan.titleOverride
        scenic = plan.scenic
        fitVersion++
        stopsChanged()
    }

    /** Keeps the stops and settings in the module's plan list; a plan opened from it is updated, not copied. */
    fun savePlan() {
        if (stops.isEmpty()) return
        val labels = ArrayList<String>()
        for (s in stops) labels.add(Fmt.shortLabel(s.label))
        val id = planId ?: java.util.UUID.randomUUID().toString()
        val name = planName ?: titleOverride ?: Strings.defaultPlanName(labels)
        val plan = Plan(id, name, ArrayList(stops), settings, System.currentTimeMillis(), titleOverride, scenic)
        planId = id
        planName = name
        env.scope.launch(Dispatchers.Default) {
            val ok = try { env.plans.save(plan); true } catch (_: Throwable) { false }
            withContext(Dispatchers.Main) {
                if (ok) notice(Strings.planSaved(name), false) else notice(Strings.SAVE_FAILED, true)
            }
        }
    }

    // ---- running

    fun notice(text: String, error: Boolean) {
        message = text
        messageIsError = error
    }

    /** Whether the actions can be tried: two stops, and nothing already running. */
    fun canRun(): Boolean = !busy && stops.size >= 2

    fun cancelRun() {
        generation += 1
        job?.cancel()
        job = null
        busy = false
        progress = ""
    }

    fun dispose() {
        cancelRun()
        preview?.cancel()
        preview = null
    }

    /**
     * Plans the route (unless it already is), rides it, and then shows it ([RunMode.PREVIEW]) or
     * saves it to TRIPS ([RunMode.SAVE]), and with [RunMode.SAVE_AND_OPEN] opens it in Flyby.
     * [onPreviewReady] is called on the main thread when the preview screen can be shown.
     */
    fun run(mode: RunMode, onPreviewReady: () -> Unit) {
        if (busy) return
        val list = ArrayList(stops)
        if (list.size < 2) {
            notice(Strings.NEED_TWO_STOPS, true)
            return
        }
        val snapshot = settings
        val title = titleOverride
        val roadScenic = scenic
        generation += 1
        val mine = generation
        busy = true
        message = ""
        savedRide = null
        progress = Strings.PROGRESS_ROUTING
        job = env.scope.launch(Dispatchers.Default) {
            try {
                val route = ensurePrepared(list, title, roadScenic, mine) ?: return@launch
                progress = Strings.PROGRESS_SIMULATING
                val ride = env.generator.generate(route, snapshot, java.util.Random().nextLong())
                if (mode == RunMode.PREVIEW) {
                    val model = PreviewModel(env, route, snapshot, ride, previewSeries(ride.output, PREVIEW_POINTS))
                    withContext(Dispatchers.Main) {
                        preview = model
                        onPreviewReady()
                    }
                } else {
                    progress = Strings.PROGRESS_SAVING
                    when (val saved = env.generator.save(ride)) {
                        is Save.Ok -> {
                            val info = SavedRide(saved.rideId, route.title)
                            if (mode == RunMode.SAVE_AND_OPEN) {
                                progress = Strings.PROGRESS_OPENING
                                val opened = env.generator.openInFlyby(saved.rideId)
                                withContext(Dispatchers.Main) {
                                    savedRide = info
                                    if (opened) notice(Strings.savedToTrips(route.title), false)
                                    else notice(Strings.FLYBY_OPEN_FAILED, true)
                                }
                            } else {
                                withContext(Dispatchers.Main) {
                                    savedRide = info
                                    notice(Strings.savedToTrips(route.title), false)
                                }
                            }
                        }
                        is Save.Failed -> fail(mine, if (saved.message.isBlank()) Strings.SAVE_FAILED else saved.message)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                fail(mine, Strings.unexpected(error.message))
            } finally {
                if (mine == generation) {
                    busy = false
                    progress = ""
                }
            }
        }
    }

    /** Opens the ride just saved in Flyby. */
    fun openSavedInFlyby() {
        val info = savedRide ?: return
        if (busy) return
        generation += 1
        val mine = generation
        busy = true
        progress = Strings.PROGRESS_OPENING
        job = env.scope.launch(Dispatchers.Default) {
            try {
                val opened = env.generator.openInFlyby(info.rideId)
                if (!opened) fail(mine, Strings.FLYBY_OPEN_FAILED)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                fail(mine, Strings.unexpected(error.message))
            } finally {
                if (mine == generation) {
                    busy = false
                    progress = ""
                }
            }
        }
    }

    private fun fail(mine: Int, text: String) {
        if (mine != generation) return
        message = text
        messageIsError = true
    }

    /**
     * Blocking: the planned route for [list], from the cache when neither the stops nor the routing
     * preference ([scenic] or not) changed. The route carries [title] as its ride title when there is
     * one, a cached route too.
     */
    private fun ensurePrepared(list: List<Stop>, title: String?, scenic: Boolean, mine: Int): PreparedRoute? {
        val key = ExperiencesLogic.preparedKeyFor(stopsKey(list), scenic)
        val cached = prepared
        if (cached != null && key == preparedKey) {
            val titled = cached.withTitle(title)
            if (titled.title != cached.title) prepared = titled
            return titled
        }
        val result = env.generator.prepare(list, title, routePreferenceOf(scenic)) { text -> if (mine == generation) { progress = text } }
        return when (result) {
            is Prepare.Ok -> {
                prepared = result.route
                preparedKey = key
                fitVersion++
                result.route
            }
            is Prepare.Failed -> {
                fail(mine, failureText(result.kind, result.message))
                null
            }
        }
    }
}

/** The ride just generated, its charts' data, and what can be done with it. */
internal class PreviewModel(
    private val env: RsEnv,
    val prepared: PreparedRoute,
    val settings: RideSettings,
    first: GeneratedRide,
    firstSeries: PreviewSeries
) {
    var ride by mutableStateOf(first)
    var series by mutableStateOf(firstSeries)
    var stats by mutableStateOf(statsOf(first))

    /** Where the cursor stands along the ride's time, 0..1. */
    var cursor by mutableStateOf(0f)

    var busy by mutableStateOf(false)
    var busyText by mutableStateOf("")
    var message by mutableStateOf("")
    var messageIsError by mutableStateOf(false)
    var savedRideId by mutableStateOf<String?>(null)

    private var job: Job? = null

    private fun statsOf(r: GeneratedRide): RideStats =
        rideStats(r.output.timesMillis, r.output.speedsKph, r.output.distanceMeters)

    /** A new variant of the same route with the same settings. */
    fun regenerate() {
        if (busy) return
        busy = true
        busyText = Strings.PROGRESS_SIMULATING
        message = ""
        job = env.scope.launch(Dispatchers.Default) {
            try {
                val next = env.generator.generate(prepared, settings, java.util.Random().nextLong())
                val nextSeries = previewSeries(next.output, PREVIEW_POINTS)
                val nextStats = statsOf(next)
                withContext(Dispatchers.Main) {
                    ride = next
                    series = nextSeries
                    stats = nextStats
                    savedRideId = null
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                fail(Strings.unexpected(error.message))
            } finally {
                busy = false
                busyText = ""
            }
        }
    }

    fun save() {
        if (busy || savedRideId != null) return
        busy = true
        busyText = Strings.PROGRESS_SAVING
        message = ""
        val toSave = ride
        job = env.scope.launch(Dispatchers.Default) {
            try {
                when (val saved = env.generator.save(toSave)) {
                    is Save.Ok -> withContext(Dispatchers.Main) {
                        savedRideId = saved.rideId
                        message = Strings.savedToTrips(prepared.title)
                        messageIsError = false
                    }
                    is Save.Failed -> fail(if (saved.message.isBlank()) Strings.SAVE_FAILED else saved.message)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                fail(Strings.unexpected(error.message))
            } finally {
                busy = false
                busyText = ""
            }
        }
    }

    fun openInFlyby() {
        val id = savedRideId ?: return
        if (busy) return
        busy = true
        busyText = Strings.PROGRESS_OPENING
        job = env.scope.launch(Dispatchers.Default) {
            try {
                if (!env.generator.openInFlyby(id)) fail(Strings.FLYBY_OPEN_FAILED)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                fail(Strings.unexpected(error.message))
            } finally {
                busy = false
                busyText = ""
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    private fun fail(text: String) {
        message = text
        messageIsError = true
    }
}

/** The saved plans screen: the list, and the one thing happening to a plan at a time. */
internal class PlansModel(private val env: RsEnv) {
    var plans by mutableStateOf<List<Plan>>(emptyList())
    var loaded by mutableStateOf(false)

    /** The plan being turned into a route in the NAV, if any. */
    var busyId by mutableStateOf<String?>(null)
    var busyText by mutableStateOf("")

    /** The plan the rider tapped Delete on once. */
    var confirmDeleteId by mutableStateOf<String?>(null)

    /** The plan the last message is about, and the message. */
    var noticeId by mutableStateOf<String?>(null)
    var notice by mutableStateOf("")
    var noticeIsError by mutableStateOf(false)

    private var job: Job? = null

    fun reload() {
        env.scope.launch(Dispatchers.Default) {
            val list = try { env.plans.all() } catch (_: Throwable) { emptyList() }
            val sorted = ArrayList(list)
            java.util.Collections.sort(sorted, object : Comparator<Plan> {
                override fun compare(a: Plan, b: Plan): Int = java.lang.Long.compare(b.savedAtMillis, a.savedAtMillis)
            })
            withContext(Dispatchers.Main) {
                plans = sorted
                loaded = true
            }
        }
    }

    /** First tap arms the delete, the second does it. */
    fun delete(plan: Plan) {
        if (confirmDeleteId != plan.id) {
            confirmDeleteId = plan.id
            return
        }
        confirmDeleteId = null
        env.scope.launch(Dispatchers.Default) {
            try { env.plans.remove(plan.id) } catch (_: Throwable) { }
            withContext(Dispatchers.Main) {
                val copy = ArrayList<Plan>()
                for (p in plans) if (p.id != plan.id) copy.add(p)
                plans = copy
            }
        }
    }

    /** Plans the plan's route (online) and files it among the NAV's simulated routes. */
    fun saveAsRoute(plan: Plan) {
        if (busyId != null) return
        busyId = plan.id
        busyText = Strings.PROGRESS_ROUTING
        noticeId = null
        job = env.scope.launch(Dispatchers.Default) {
            try {
                when (val result = env.generator.prepare(plan.stops, plan.titleOverride, plan.routePreference) { text -> busyText = text }) {
                    is Prepare.Ok -> {
                        busyText = Strings.PROGRESS_SAVING
                        val id = env.generator.savePlanAsRoute(plan, result.route)
                        withContext(Dispatchers.Main) {
                            noticeId = plan.id
                            if (id != null) { notice = Strings.PLAN_ROUTE_SAVED; noticeIsError = false }
                            else { notice = Strings.PLAN_ROUTE_FAILED; noticeIsError = true }
                        }
                    }
                    is Prepare.Failed -> withContext(Dispatchers.Main) {
                        noticeId = plan.id
                        notice = failureText(result.kind, result.message)
                        noticeIsError = true
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                withContext(Dispatchers.Main) {
                    noticeId = plan.id
                    notice = Strings.unexpected(error.message)
                    noticeIsError = true
                }
            } finally {
                busyId = null
                busyText = ""
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}
