package com.thedavelopers.eventqr.features.attendee

import com.thedavelopers.eventqr.core.api.NetworkResult
import com.thedavelopers.eventqr.core.cache.AttendeeSnapshots
import com.thedavelopers.eventqr.core.cache.LastKnownCache
import com.thedavelopers.eventqr.core.session.SessionManager
import com.thedavelopers.eventqr.features.dashboard.DashboardStats
import com.thedavelopers.eventqr.features.registrations.RegistrationsCache
import com.thedavelopers.eventqr.features.registrations.model.dto.RegistrationResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class RegisteredEventsPresenter(
    private var view: RegisteredEventsContract.View?,
    private val repository: AttendeeRepository,
    private val sessionManager: SessionManager,
    private val scope: CoroutineScope = kotlinx.coroutines.MainScope(),
    /** The persisted last-known layer; null keeps the screen network-only (unit tests). */
    private val cache: LastKnownCache? = null,
) {
    private var job: Job? = null

    /** True once rows are on screen: from then on a snapshot would be older than the content. */
    private var painted = false

    fun detach() {
        job?.cancel()
        scope.cancel()
        view = null
    }

    /**
     * @param forceNetwork a pull-to-refresh or an explicit Retry always asks the network; an open or
     *   resume serves a fresh full answer already on hand instead of re-requesting it.
     *
     * Three steps, cheapest first: the process cache (30 s), the persisted list (24 h), then the
     * network. The network answer is the only thing written back, and a failure never replaces rows
     * that a snapshot already painted.
     */
    fun load(forceNetwork: Boolean = false) {
        // A refresh can be asked for again while it is still running (a resume over an in-flight
        // swipe refresh). Without this the older response could land last and overwrite the newer
        // list, and its `showLoading(false)` would end the indicator for a request still in flight.
        job?.cancel()
        job = scope.launch {
            val fresh = if (forceNetwork) null else RegistrationsCache.fresh(sessionManager.getUserId())
            if (fresh != null) {
                paint(fresh)
                return@launch
            }
            // A snapshot is a first paint only: over rows already on screen it would be a downgrade.
            val snapshot = if (forceNetwork || painted) null else persistedSnapshot()
            if (snapshot != null) paint(snapshot) else view?.showLoading(true)

            when (val regsResult = repository.getMyRegistrations()) {
                is NetworkResult.Success -> paint(regsResult.data)
                is NetworkResult.Error -> {
                    view?.showLoading(false)
                    view?.showMessage(regsResult.message)
                }
                NetworkResult.Loading -> Unit
            }
        }
    }

    private suspend fun persistedSnapshot(): List<RegistrationResponse>? {
        val cache = cache ?: return null
        val items = cache.read<List<RegistrationResponse>>(
            AttendeeSnapshots.REGISTRATIONS,
            AttendeeSnapshots.REGISTRATIONS_TYPE,
            AttendeeSnapshots.MAX_AGE_MS,
        )?.data
        // An empty persisted list is a miss, not a painted screen: the empty state belongs to the
        // network answer, and painting it early would claim there are no passes while rows may come.
        return items?.takeIf { it.isNotEmpty() }
    }

    private fun paint(items: List<RegistrationResponse>) {
        painted = true
        view?.showLoading(false)
        view?.showRegisteredEvents(items.filter { DashboardStats.isLive(it.status) })
    }
}
