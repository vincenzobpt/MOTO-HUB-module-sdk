// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// The state of the Experiences page: the country and its filters, the ten cards, the routes being
// computed for them, and the card being opened. The screen draws it; this class does the work, off
// the main thread, and the arithmetic lives in ExperiencesLogic.
package io.motohub.android.routesim.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.motohub.android.routesim.experiences.CatalogUpdater
import io.motohub.android.routesim.experiences.CountryInfo
import io.motohub.android.routesim.experiences.CountryPack
import io.motohub.android.routesim.experiences.Experience
import io.motohub.android.routesim.experiences.ExperienceFilters
import io.motohub.android.routesim.experiences.ExperienceRoute
import io.motohub.android.routesim.experiences.ExperienceRanker
import io.motohub.android.routesim.experiences.RankedExperience
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class ExperiencesModel(private val env: RsEnv) {

    /** True until the catalogue has been read and a country chosen. */
    var loading by mutableStateOf(true)

    var countries by mutableStateOf<List<CountryInfo>>(emptyList())
    var country by mutableStateOf("")

    /** The country's pack; null when the catalogue has nothing for it. */
    var pack by mutableStateOf<CountryPack?>(null)
    var regions by mutableStateOf<List<String>>(emptyList())

    var filters by mutableStateOf(ExperienceFilters.DEFAULT)
    var filtersOpen by mutableStateOf(false)

    /** The cards, best first. */
    var ranked by mutableStateOf<List<RankedExperience>>(emptyList())

    /** The alternative of detours asked for per experience id; absent is the best set. */
    var alts by mutableStateOf<Map<String, Int>>(emptyMap())

    /** The last route that came back per experience id. */
    var results by mutableStateOf<Map<String, CardResult>>(emptyMap())

    /** The experiences with a route waiting or being computed. */
    var busyIds by mutableStateOf<Set<String>>(emptySet())

    /** The name of the experience being opened while its route is computed; null when none is. */
    var openingName by mutableStateOf<String?>(null)

    private val queue = RouteQueue()
    private val resultsLock = Any()
    private val visible = HashSet<String>()
    private var debounce: Job? = null

    /** When the pending refresh is due (a [System.nanoTime] in ms), and which wait it is; guarded by [debounceLock]. */
    private val debounceLock = Any()
    private var debounceDue: Long? = null
    private var debounceTicket = 0

    /** Published under one lock, so the set the page shows is the queue's latest, whichever thread changed it. */
    private val busyLock = Any()
    private var saveJob: Job? = null
    private var started = false

    /** The filters changed since they were last kept. */
    private var dirty = false
    private var openingId: String? = null

    /** The tap that is being waited for: when it was made ([nowMs]), and the wait that gives up on it. */
    private var openingStartedMs = 0L
    private var openingDeadline: Job? = null
    private var onOpenReady: ((OpenPlan) -> Unit)? = null

    /** Counts the country changes, so a country that was left behind cannot fill the page after the one that came next. */
    @Volatile
    private var generation = 0

    /** Reads the catalogue and chooses the country; once. Then the catalogue is refreshed in the background. */
    fun start() {
        if (started) return
        started = true
        val mine = ++generation
        env.scope.launch(Dispatchers.Default) {
            val list = try { env.catalog.countries() } catch (_: Throwable) { emptyList() }
            val position = try { env.host.places.lastKnownPosition() } catch (_: Throwable) { null }
            val last = try { env.filterStore.lastCountry() } catch (_: Throwable) { null }
            val chosen = ExperiencesLogic.initialCountry(position, list.map { it.code }, last)
            val loaded = loadCountry(chosen)
            withContext(Dispatchers.Main) {
                if (mine != generation) return@withContext
                countries = list
                apply(chosen, loaded)
                loading = false
            }
            // The download blocks on the network: off the compute threads.
            withContext(Dispatchers.IO) { updateCatalog() }
        }
    }

    /** What a country's page starts from: its pack and the filters the rider left there. */
    private class Loaded(val pack: CountryPack?, val filters: ExperienceFilters)

    private fun loadCountry(code: String?): Loaded {
        if (code == null) return Loaded(null, ExperienceFilters.DEFAULT)
        val pack = try { env.catalog.pack(code) } catch (_: Throwable) { null }
        val saved = try { env.filterStore.load(code) } catch (_: Throwable) { ExperienceFilters.DEFAULT }
        return Loaded(pack, saved)
    }

    /** Puts a loaded country on the page. Main thread. */
    private fun apply(code: String?, loaded: Loaded) {
        country = code.orEmpty()
        pack = loaded.pack
        regions = loaded.pack?.let { ExperienceRanker.regions(it) } ?: emptyList()
        filters = loaded.filters
        rerank()
        scheduleRoutes(PlanReason.FILTERS)
    }

    // ---- country and filters

    fun selectCountry(code: String) {
        val from = country
        if (code == from || openingName != null) return
        val oldFilters = filters
        val mine = ++generation
        saveJob?.cancel()
        dirty = false
        alts = emptyMap()
        queue.clear()
        publishBusy()
        env.scope.launch(Dispatchers.Default) {
            if (from.isNotEmpty()) try { env.filterStore.save(from, oldFilters) } catch (_: Throwable) { }
            try { env.filterStore.saveLastCountry(code) } catch (_: Throwable) { }
            val loaded = loadCountry(code)
            withContext(Dispatchers.Main) {
                if (mine != generation) return@withContext
                apply(code, loaded)
            }
        }
    }

    /** The filters change: the cards re-rank at once, the routes follow after a pause, the filters are kept soon. */
    fun changeFilters(next: ExperienceFilters) {
        if (next == filters) return
        filters = next
        dirty = true
        rerank()
        scheduleSave()
        scheduleRoutes(PlanReason.FILTERS)
    }

    fun resetFilters() {
        val c = country
        saveJob?.cancel()
        dirty = false
        env.scope.launch(Dispatchers.Default) { try { env.filterStore.reset(c) } catch (_: Throwable) { } }
        filters = ExperienceFilters.DEFAULT
        rerank()
        scheduleRoutes(PlanReason.FILTERS)
    }

    fun toggleFilters() {
        filtersOpen = !filtersOpen
    }

    private fun rerank() {
        val p = pack
        ranked = if (p == null) emptyList() else ExperienceRanker.rank(p, filters, ExperiencesLogic.CARD_LIMIT)
    }

    private fun scheduleSave() {
        val c = country
        saveJob?.cancel()
        saveJob = env.scope.launch(Dispatchers.Default) {
            delay(ExperiencesLogic.SAVE_DEBOUNCE_MS)
            dirty = false
            try { env.filterStore.save(c, filters) } catch (_: Throwable) { }
        }
    }

    // ---- routes of the cards

    /** A card came on screen, or left it. */
    fun markVisible(id: String, on: Boolean) {
        val changed = synchronized(visible) { if (on) visible.add(id) else visible.remove(id) }
        if (changed) scheduleRoutes(PlanReason.VISIBILITY)
    }

    /** Asks for another set of detours for one card. */
    fun otherIdeas(id: String) {
        val card = ranked.firstOrNull { it.experience.id == id } ?: return
        if (!ExperiencesLogic.canOtherIdeas(filters)) return
        val next = ExperiencesLogic.nextAlt(alts[id] ?: 0)
        alts = alts + (id to next)
        if (queue.pin(RouteRequest(card.experience, filters, next))) startWorker()
        publishBusy()
        scheduleRoutes(PlanReason.OTHER_IDEAS)
    }

    /** The routes failed for want of a connection: ask for them again. */
    fun retry() {
        // The rider asks again: the sights search is no longer left alone after its last failure.
        try { env.experienceRoutes.resetBreaker() } catch (_: Throwable) { }
        synchronized(resultsLock) { results = ExperiencesLogic.withoutFailures(results) }
        scheduleRoutes(PlanReason.OTHER_IDEAS)
    }

    private fun scheduleRoutes(reason: PlanReason) {
        val wait = ExperiencesLogic.debounceMs(reason)
        val now = nowMs()
        var immediate = false
        synchronized(debounceLock) {
            val pending = debounceDue
            val due = ExperiencesLogic.dueAt(pending, now, wait)
            when {
                // A wait that is already pending and not shorter than this one stays as it is.
                pending != null && due == pending -> Unit
                due <= now -> {
                    debounce?.cancel()
                    debounce = null
                    debounceDue = null
                    immediate = true
                }
                else -> {
                    debounce?.cancel()
                    val ticket = ++debounceTicket
                    debounceDue = due
                    debounce = env.scope.launch(Dispatchers.Default) {
                        delay(due - now)
                        synchronized(debounceLock) {
                            if (ticket != debounceTicket) return@launch
                            debounceDue = null
                            debounce = null
                        }
                        refreshQueue()
                    }
                }
            }
        }
        if (immediate) refreshQueue()
    }

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L

    /** Puts the cards that need a route in the queue, nearest the top first. */
    private fun refreshQueue() {
        val ids = synchronized(visible) { HashSet(visible) }
        val wanted = ExperiencesLogic.wanted(ranked, alts, filters, results, ids)
        if (queue.replace(wanted)) startWorker()
        publishBusy()
    }

    /** The one place the busy set is read from the queue and published. */
    private fun publishBusy() {
        synchronized(busyLock) { busyIds = queue.busyIds() }
    }

    private fun startWorker() {
        env.scope.launch(Dispatchers.Default) {
            queue.drain(
                { isActive },
                { request -> env.experienceRoutes.build(request.experience, request.filters, request.alt) },
                { request, route -> deliver(request, route) },
                { publishBusy() },
            )
        }
    }

    private fun deliver(request: RouteRequest, route: ExperienceRoute) {
        synchronized(resultsLock) { results = results + (request.id to CardResult(request.key, route)) }
        if (openingId != request.id) return
        env.scope.launch(Dispatchers.Main) {
            // The rider may have cancelled between the answer and now.
            if (openingId != request.id) return@launch
            // An answer for filters (or an idea) that have changed since is no answer: ask again for the current ones.
            val alt = alts[request.id] ?: 0
            if (!ExperiencesLogic.answerIsCurrent(request.key, filters, alt)) {
                if (queue.pin(RouteRequest(request.experience, filters, alt))) startWorker()
                publishBusy()
                return@launch
            }
            val ready = onOpenReady
            clearOpening()
            ready?.invoke(ExperiencesLogic.planToOpen(request.experience, route))
        }
    }

    // ---- opening a card

    /**
     * Opens [card] in the planner through [onReady] (called on the main thread): at once when its
     * route is known, otherwise once it has been computed, which the page shows and [cancelOpen] stops.
     * A route that could not be had opens the catalogue's own stops, with the reason.
     */
    fun open(card: RankedExperience, onReady: (OpenPlan) -> Unit) {
        if (openingName != null) return
        val e = card.experience
        val alt = alts[e.id] ?: 0
        val key = ExperiencesLogic.routeKey(filters, alt)
        val known = results[e.id]?.takeIf { it.key == key && !it.route.error }
        if (known != null) {
            onReady(ExperiencesLogic.planToOpen(e, known.route))
            return
        }
        openingId = e.id
        onOpenReady = onReady
        openingName = e.name
        openingStartedMs = nowMs()
        if (queue.pin(RouteRequest(e, filters, alt))) startWorker()
        publishBusy()
        startOpeningDeadline(e, alt)
    }

    /**
     * The wait for a tapped card has a limit: when it passes without an answer the planner opens with
     * the catalogue's own stops and a notice, and the card is marked for a retry. The build that is
     * still running cannot be stopped; it is no longer waited for, and its result is kept for later.
     */
    private fun startOpeningDeadline(e: Experience, alt: Int) {
        openingDeadline?.cancel()
        val started = openingStartedMs
        val key = ExperiencesLogic.routeKey(filters, alt)
        openingDeadline = env.scope.launch(Dispatchers.Default) {
            delay(ExperiencesLogic.openRemainingMs(started, nowMs()))
            withContext(Dispatchers.Main) {
                // Answered, cancelled or replaced by another tap in the meantime.
                if (openingId != e.id || openingStartedMs != started) return@withContext
                if (!ExperiencesLogic.openTimedOut(started, nowMs())) return@withContext
                val ready = onOpenReady
                clearOpening()
                queue.unpin()
                synchronized(resultsLock) {
                    // A build that already answered for this card is the better result: keep it.
                    if (results[e.id]?.key != key) results = results + (e.id to CardResult(key, ExperiencesLogic.slowRoute(e)))
                }
                publishBusy()
                ready?.invoke(ExperiencesLogic.slowPlan(e))
            }
        }
    }

    /** Nothing is being opened any more. */
    private fun clearOpening() {
        openingDeadline?.cancel()
        openingDeadline = null
        openingId = null
        onOpenReady = null
        openingName = null
    }

    fun cancelOpen() {
        clearOpening()
        queue.unpin()
        publishBusy()
        scheduleRoutes(PlanReason.VISIBILITY)
    }

    // ---- the catalogue, refreshed once per module load

    /** Fetches newer packs off the main thread. Failures are a line in the log and nothing else. */
    private fun updateCatalog() {
        if (!env.claimCatalogUpdate()) return
        try {
            val installed = HashMap<String, Int>()
            for (info in env.catalog.countries()) env.catalog.pack(info.code)?.let { installed[info.code] = it.version }
            val report = CatalogUpdater(env.host.storage).updateAll(installed)
            if (report.offline || report.failed.isNotEmpty()) {
                env.host.log.log("routesim: catalogue update: " + (if (report.offline) "no connection" else report.failed.toString()))
            }
            // An attempt that never reached the server does not count: the next page open tries again.
            if (report.offline) env.releaseCatalogUpdate()
            if (report.updated.isEmpty()) return
            env.catalog.reload()
            val list = env.catalog.countries()
            env.scope.launch(Dispatchers.Main) { catalogReloaded(list, report.updated) }
        } catch (error: Throwable) {
            try { env.host.log.log("routesim: catalogue update failed: ${error.message}") } catch (_: Throwable) { }
        }
    }

    /** Main thread: the packs of [updated] are new; the page follows when its own country is one of them. */
    private fun catalogReloaded(list: List<CountryInfo>, updated: List<String>) {
        countries = list
        if (country.isEmpty() || country !in updated.map { it.uppercase() }) return
        val p = try { env.catalog.pack(country) } catch (_: Throwable) { null }
        pack = p
        regions = p?.let { ExperienceRanker.regions(it) } ?: emptyList()
        rerank()
        scheduleRoutes(PlanReason.FILTERS)
    }

    /** The page is left for good: what is wanted is kept, and nothing more is asked for. */
    fun dispose() {
        synchronized(debounceLock) {
            debounce?.cancel()
            debounce = null
            debounceDue = null
            debounceTicket++
        }
        saveJob?.cancel()
        val c = country
        val f = filters
        if (c.isNotEmpty() && dirty) {
            dirty = false
            env.scope.launch(Dispatchers.Default) { try { env.filterStore.save(c, f) } catch (_: Throwable) { } }
        }
        queue.clear()
        clearOpening()
    }
}
