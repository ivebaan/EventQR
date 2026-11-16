package com.thedavelopers.eventqr.features.staff

import com.thedavelopers.eventqr.R
import com.thedavelopers.eventqr.core.api.NetworkResult
import com.thedavelopers.eventqr.core.cache.LastKnownCache
import com.thedavelopers.eventqr.core.cache.StaffSnapshots
import com.thedavelopers.eventqr.core.util.UiStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import com.thedavelopers.eventqr.core.api.dto.NotificationStatus
import com.thedavelopers.eventqr.features.staff.model.dto.StaffAssignedEventResponse
import com.thedavelopers.eventqr.features.staff.model.dto.StaffTodaySummary
import com.thedavelopers.eventqr.features.transactions.model.dto.TransactionResponse
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.time.Instant

class StaffDashboardPresenter(
    private var view: StaffDashboardContract.View?,
    private val repository: StaffRepository,
    private val strings: UiStrings? = null,
    private val scope: CoroutineScope = MainScope(),
    private val cache: LastKnownCache? = null,
) {
    private var job: Job? = null

    /**
     * What is already on screen. It gates the snapshot read (a reload must never repaint an older
     * snapshot over fresher numbers, and the dashboard reloads on every resume) and tells a failed
     * refresh whether it may take a section over with its error.
     */
    private var onScreen = Painted()

    fun detach() {
        job?.cancel()
        view = null
    }

    /** @param forceNetwork a pull-to-refresh bypasses the persisted snapshots and paints only the network answer. */
    fun loadData(forceNetwork: Boolean = false) {
        job?.cancel()
        job = scope.launch {
            // The persisted day numbers, scans and assigned events paint first: a cold open shows the
            // last known screen, and the network refresh lands underneath it with no skeleton.
            if (!forceNetwork && !onScreen.any) paintSnapshot()
            view?.showLoading(!onScreen.any)

            // Captured before the requests: an answer that lands after an account switch stays filed
            // under the session it was fetched for.
            val owner = cache?.currentOwner().orEmpty()

            val (eventsResult, todayResult, summaryResult) = coroutineScope {
                val eventsDeferred = async { repository.getEvents() }
                val todayDeferred = async { repository.getMyTodayTransactions() }
                val summaryDeferred = async { repository.getMyTodaySummary() }
                Triple(eventsDeferred.await(), todayDeferred.await(), summaryDeferred.await())
            }

            // The assigned-event list only gates this screen: a persisted copy means that part is
            // known, so a failed refresh keeps the painted dashboard instead of the error state.
            val eventsUsable = eventsResult !is NetworkResult.Error || onScreen.events
            if (!eventsUsable) {
                view?.showError((eventsResult as NetworkResult.Error).message)
            }

            if (eventsUsable) {
                when (todayResult) {
                    is NetworkResult.Success -> {
                        view?.renderRecentScans(todayResult.data.newestFirst())
                        onScreen = onScreen.copy(scans = true)
                        // The full list is stored, the screen shows five of it.
                        cache?.write(StaffSnapshots.TODAY_TRANSACTIONS, todayResult.data, owner)
                    }
                    // A failed load is not "no scans": keep the painted rows, else the shared error
                    // state with Retry.
                    is NetworkResult.Error -> if (!onScreen.scans) view?.showRecentScansError(todayResult.message)
                    NetworkResult.Loading -> Unit
                }
            }

            // Tile counts are server-side, scoped to the caller's own scans today (check-ins = distinct
            // attendees). Unknown is not zero: show "--" and say why.
            when (summaryResult) {
                is NetworkResult.Success -> {
                    view?.updateStats(summaryResult.data.scannedToday.toInt(), summaryResult.data.successfulCheckIns.toInt())
                    onScreen = onScreen.copy(summary = true)
                    cache?.write(StaffSnapshots.TODAY_SUMMARY, summaryResult.data, owner)
                }
                is NetworkResult.Error -> if (!onScreen.summary) {
                    view?.updateStats(null, null)
                    view?.showMessage(strings?.get(R.string.staff_dashboard_stats_unavailable) ?: summaryResult.message)
                }
                NetworkResult.Loading -> Unit
            }

            when (val notifResult = repository.getMyNotifications()) {
                is NetworkResult.Success -> {
                    val unreadCount = notifResult.data.count { it.status != NotificationStatus.READ && it.readAt == null }
                    view?.showNotificationBadge(unreadCount)
                }
                is NetworkResult.Error -> view?.showNotificationBadge(0)
                NetworkResult.Loading -> Unit
            }
            view?.showLoading(false)
        }
    }

    /** One flag per part, so "keep what is painted" and "the error may take this section" stay separate. */
    private data class Painted(
        val scans: Boolean = false,
        val summary: Boolean = false,
        val events: Boolean = false,
    ) {
        val any: Boolean get() = scans || summary || events
    }

    private suspend fun paintSnapshot() {
        val cache = cache ?: return

        cache.read<StaffTodaySummary>(
            StaffSnapshots.TODAY_SUMMARY,
            StaffSnapshots.SUMMARY_TYPE,
            StaffSnapshots.MAX_AGE_MS,
        )?.data?.let {
            view?.updateStats(it.scannedToday.toInt(), it.successfulCheckIns.toInt())
            onScreen = onScreen.copy(summary = true)
        }
        cache.read<List<TransactionResponse>>(
            StaffSnapshots.TODAY_TRANSACTIONS,
            StaffSnapshots.TRANSACTIONS_TYPE,
            StaffSnapshots.MAX_AGE_MS,
        )?.data?.let {
            view?.renderRecentScans(it.newestFirst())
            onScreen = onScreen.copy(scans = true)
        }
        // The dashboard has no events section of its own; the list is the gate for the whole load.
        if (cache.read<List<StaffAssignedEventResponse>>(
                StaffSnapshots.ASSIGNMENTS,
                StaffSnapshots.ASSIGNMENTS_TYPE,
                StaffSnapshots.MAX_AGE_MS,
            ) != null
        ) {
            onScreen = onScreen.copy(events = true)
        }
    }

    private fun List<TransactionResponse>.newestFirst(): List<TransactionResponse> =
        sortedByDescending { it.scannedAt ?: Instant.EPOCH }.take(5)
}
