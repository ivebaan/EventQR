package com.thedavelopers.eventqr.features.attendee

import com.thedavelopers.eventqr.core.api.NetworkResult
import com.thedavelopers.eventqr.core.cache.AttendeeSnapshots
import com.thedavelopers.eventqr.core.cache.LastKnownCache
import com.thedavelopers.eventqr.core.cache.Snapshot
import com.thedavelopers.eventqr.core.cache.cachedThenFresh
import com.thedavelopers.eventqr.features.dashboard.DashboardStats
import com.thedavelopers.eventqr.features.events.model.dto.AttendeeEventResponse
import com.thedavelopers.eventqr.features.registrations.RegistrationsCache
import com.thedavelopers.eventqr.features.registrations.model.dto.RegistrationResponse
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID

class EventsPresenter(
    private var view: EventsContract.View?,
    private val repository: AttendeeRepository,
    /** The persisted last-known layer; null keeps the screen network-only (unit tests). */
    private val cache: LastKnownCache? = null,
) {
    private val scope = kotlinx.coroutines.MainScope()
    private var job: Job? = null

    /** The last usable live-registration id set; null until the first successful answer. */
    private var registeredEventIds: Set<UUID>? = null

    fun detach() {
        job?.cancel()
        scope.cancel()
        view = null
    }

    /**
     * [silent] refreshes the list already on screen (e.g. on return from an event's detail): no skeleton,
     * and a failure keeps the current cards instead of replacing them with the error state.
     * [forceNetwork] is a pull-to-refresh: it bypasses the persisted snapshot and always asks the network.
     *
     * Events and registrations load in parallel so the cards never wait on the (potentially paged)
     * registrations call. A fresh [RegistrationsCache] answer skips the refetch; a failed registration
     * load keeps the last good set, and only the first-ever load reports `null` so the `Registered`
     * chip can render a retry state instead of claiming the attendee has no registrations.
     */
    fun loadEvents(silent: Boolean = false, forceNetwork: Boolean = false) {
        // A visible load already in flight has hidden the list behind the skeleton; it must finish (and show
        // its error state on failure), so a silent refresh never replaces it.
        if (silent && job?.isActive == true) return
        job?.cancel()
        job = scope.launch {
            var paintedSnapshot = false
            val registrationsDeferred = async {
                // Owner-scoped: another account's answer on this device is never served.
                val userId = repository.currentUserId()
                val cached = RegistrationsCache.get(userId)
                if (cached != null && RegistrationsCache.isFresh(userId)) {
                    NetworkResult.Success(cached)
                } else {
                    repository.getMyRegistrations()
                }
            }
            val eventsResult = readBrowseEvents(
                silent = silent,
                forceNetwork = forceNetwork,
                onSnapshot = { snapshot ->
                    // An empty persisted list is a miss, not a painted screen: it would claim there is
                    // nothing on while the load is still coming.
                    if (snapshot.data.isNotEmpty()) {
                        paintedSnapshot = true
                        view?.showEvents(snapshot.data)
                    }
                },
                // The skeleton is the frame of a load with nothing to show; a painted snapshot owns it.
                onFetchStart = { if (!silent && !paintedSnapshot) view?.showLoading(true) },
            )

            when (eventsResult) {
                is NetworkResult.Success -> {
                    view?.showLoading(false)
                    view?.showEvents(eventsResult.data)
                    renderRegisteredEventIds(registrationsDeferred.await())
                }
                is NetworkResult.Error -> {
                    registrationsDeferred.cancel()
                    view?.showLoading(false)
                    // A failed refresh over the painted snapshot keeps it: only a load with nothing
                    // to show may replace the list with the error state.
                    if (!silent && !paintedSnapshot) view?.showError(eventsResult.message)
                }
                NetworkResult.Loading -> Unit
            }
        }
    }

    /**
     * The browse read behind one load: the persisted snapshot paints through [onSnapshot] first, the
     * network answer then replaces it and is written back under the session that asked for it
     * ([cachedThenFresh] captures that owner before it fetches). A silent refresh and a screen with no
     * cache go straight to the network; a pull passes `maxAgeMs = -1`, which serves no snapshot but
     * still rewrites it.
     */
    private suspend fun readBrowseEvents(
        silent: Boolean,
        forceNetwork: Boolean,
        onSnapshot: (Snapshot<List<AttendeeEventResponse>>) -> Unit,
        onFetchStart: () -> Unit,
    ): NetworkResult<List<AttendeeEventResponse>> {
        val cache = cache
        if (silent || cache == null) {
            onFetchStart()
            return repository.getBrowseEvents()
        }
        return cache.cachedThenFresh(
            key = AttendeeSnapshots.BROWSE_EVENTS,
            type = AttendeeSnapshots.EVENTS_TYPE,
            maxAgeMs = if (forceNetwork) -1L else AttendeeSnapshots.MAX_AGE_MS,
            onSnapshot = onSnapshot,
            fetch = {
                onFetchStart()
                repository.getBrowseEvents()
            },
        )
    }

    private fun renderRegisteredEventIds(result: NetworkResult<List<RegistrationResponse>>) {
        when (result) {
            is NetworkResult.Success -> {
                val ids = result.data
                    .filter { DashboardStats.isLive(it.status) }
                    .map { it.eventId }
                    .toSet()
                registeredEventIds = ids
                view?.showRegisteredEventIds(ids)
            }
            is NetworkResult.Error -> {
                // A refresh failure keeps the last good set so rows stay readable; only a
                // first-ever failure reports null for the chip's retry state.
                if (registeredEventIds == null) view?.showRegisteredEventIds(null)
            }
            NetworkResult.Loading -> Unit
        }
    }
}
