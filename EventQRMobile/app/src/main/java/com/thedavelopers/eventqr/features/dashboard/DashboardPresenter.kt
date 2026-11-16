package com.thedavelopers.eventqr.features.dashboard

import com.thedavelopers.eventqr.R
import android.util.Log
import com.thedavelopers.eventqr.core.cache.AttendeeSnapshots
import com.thedavelopers.eventqr.core.cache.LastKnownCache
import com.thedavelopers.eventqr.core.util.UiStrings
import com.thedavelopers.eventqr.core.api.NetworkResult
import com.thedavelopers.eventqr.core.api.dto.RedemptionStatus
import com.thedavelopers.eventqr.core.api.dto.RegistrationStatus
import com.thedavelopers.eventqr.core.session.SessionManager
import com.thedavelopers.eventqr.features.attendee.AttendeeRepository
import com.thedavelopers.eventqr.features.dashboard.model.dto.DashboardSummary
import com.thedavelopers.eventqr.features.dashboard.model.dto.DashboardUpcomingEvent
import com.thedavelopers.eventqr.features.events.EventStatusBadgeStyler
import com.thedavelopers.eventqr.features.events.model.dto.AttendeeEventResponse
import com.thedavelopers.eventqr.features.registrations.RegistrationsCache
import com.thedavelopers.eventqr.features.registrations.model.dto.RegistrationResponse
import com.thedavelopers.eventqr.ui.components.PassStat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.Instant

class DashboardPresenter(
    private var view: DashboardContract.View?,
    private val repository: DashboardRepository,
    private val attendeeRepository: AttendeeRepository,
    private val sessionManager: SessionManager,
    private val strings: UiStrings,
    private val scope: CoroutineScope = MainScope(),
    /** The persisted last-known layer; null keeps the screen network-only (unit tests). */
    private val cache: LastKnownCache? = null,
) {
    private var dashboardJob: Job? = null

    /** True once a summary is on screen: from then on a snapshot would be older than the content. */
    private var painted = false

    fun attach(view: DashboardContract.View) {
        this.view = view
    }

    fun detach() {
        dashboardJob?.cancel()
        view = null
    }

    /**
     * Rewards the attendee has claimed, over the events they were checked in at (a reward can only
     * be claimed after check-in). One read covers every event. Null = unknown: a failed call keeps
     * the cell on `--`.
     */
    private suspend fun claimedRewardsCount(
        registrations: List<RegistrationResponse>,
    ): Long? {
        val eventIds = registrations
            .filter { it.status != RegistrationStatus.CANCELLED && (it.attendedAt != null || it.enteredAt != null) }
            .map { it.eventId.toString() }
            .distinct()
        if (eventIds.isEmpty()) return 0L
        // The repository falls back to the per-event fan-out only when the backend lacks the
        // all-redemptions endpoint (404).
        return when (val result = attendeeRepository.loadMyRedemptions(eventIds)) {
            is NetworkResult.Success -> result.data.count { it.status == RedemptionStatus.REDEEMED }.toLong()
            is NetworkResult.Error, NetworkResult.Loading -> null
        }
    }

    fun loadDashboard(forceNetwork: Boolean = false) {
        view?.updateHeader(sessionManager.getUserRole(), sessionManager.getFullName())
        // A reload (resume, pull-to-refresh) supersedes the previous one, including its background
        // redemptions fan-out: a late answer must not overwrite the newer numbers.
        dashboardJob?.cancel()
        dashboardJob = scope.launch {
            // Captured before any read: an account switch in flight must not refile this answer under
            // whoever is signed in when it lands.
            val owner = cache?.currentOwner().orEmpty()
            // A cold open paints the last known screen before the network answers. A pull-to-refresh
            // bypasses the snapshot, and a reload over rows already on screen keeps them: painting an
            // older copy over newer content would be a downgrade.
            val fromSnapshot = !forceNetwork && !painted && paintSnapshot()
            // After a snapshot paint this is a no-op on screen: the activity keeps content visible.
            view?.showLoading(true)
            val currentUserDeferred = async { repository.getCurrentUser() }
            val summaryDeferred = async { repository.getSummary() }
            val eventsDeferred = async {
                val browse = attendeeRepository.getBrowseEvents()
                if (browse is NetworkResult.Success && browse.data.isNotEmpty()) {
                    browse
                } else {
                    val visible = attendeeRepository.getEvents()
                    if (visible is NetworkResult.Success && visible.data.isNotEmpty()) {
                        visible
                    } else if (browse is NetworkResult.Success) {
                        browse
                    } else {
                        visible
                    }
                }
            }
            val registrationsDeferred = async {
                // A fresh full answer already on hand (dashboard, my passes, discover, rewards all
                // read `registrations/me`) is served as is: an open must not emit it again.
                if (forceNetwork) {
                    attendeeRepository.getMyRegistrations()
                } else {
                    RegistrationsCache.fresh(sessionManager.getUserId())
                        ?.let { NetworkResult.Success(it) }
                        ?: attendeeRepository.getMyRegistrations()
                }
            }

            val currentUserResult = currentUserDeferred.await()
            if (currentUserResult is NetworkResult.Success) {
                val user = currentUserResult.data
                sessionManager.saveRole(user.role)
                sessionManager.updateProfile(user.fullName, user.phoneNumber, user.email)
                view?.updateHeader(user.role.name, user.fullName)
            }

            val summaryResult = summaryDeferred.await()
            val eventsResult = eventsDeferred.await()
            val registrationsResult = registrationsDeferred.await()

            // The four calls above are everything the screen needs: paint now, then resolve the
            // "Rewards claimed" cell (one all-redemptions read) in the background. Until it lands
            // that cell keeps the unknown "--", so the late answer reflows nothing.
            view?.showLoading(false)

            val now = Instant.now()
            // "Rewards claimed" is unknown until the fan-out at the end of this load answers, so the
            // cell keeps the pass head's "--" placeholder: a late answer reflows no other number.
            val deferredRewardsCount: Long? = null
            val events = if (eventsResult is NetworkResult.Success) eventsResult.data else emptyList()
            val registrations = if (registrationsResult is NetworkResult.Success) registrationsResult.data else emptyList()

            val activeRegistrations = registrations
                .filter { it.status != RegistrationStatus.CANCELLED && it.status != RegistrationStatus.NO_SHOW }
            val registeredEventIds = activeRegistrations.map { it.eventId }.toSet()

            val mappedEvents = events.map { event ->
                val status = computeEventStatus(event, now)
                DashboardUpcomingEvent(
                    eventId = event.eventId,
                    title = event.title,
                    location = event.location,
                    category = event.category,
                    eventStartAt = event.eventStartAt,
                    status = status,
                    description = event.description,
                    eventEndAt = event.eventEndAt,
                    capacity = event.capacity,
                    currentAttendeeCount = event.currentAttendeeCount,
                    isRegistered = registeredEventIds.contains(event.eventId),
                )
            }

            val regEventsNotInMapped = activeRegistrations
                .filter { reg -> mappedEvents.none { it.eventId == reg.eventId } }
                .map { reg ->
                    val statusEnum = EventStatusBadgeStyler.fromDates(reg.eventStartAt, reg.eventEndAt, now)
                    DashboardUpcomingEvent(
                        eventId = reg.eventId,
                        registrationId = reg.registrationId,
                        title = reg.eventTitle?.takeIf { it.isNotBlank() } ?: strings.get(R.string.dashboard_registered_event),
                        location = reg.eventLocation,
                        category = null,
                        eventStartAt = reg.eventStartAt,
                        status = EventStatusBadgeStyler.displayLabel(statusEnum),
                        description = null,
                        eventEndAt = reg.eventEndAt,
                        capacity = 0,
                        currentAttendeeCount = 0,
                        isRegistered = true,
                        capacityUnknown = true,
                    )
                }

            val summaryUpcoming = if (summaryResult is NetworkResult.Success) {
                summaryResult.data.upcomingEvents.orEmpty()
            } else {
                emptyList()
            }
            val summaryEventsNotInMapped = summaryUpcoming
                .filter { sumEvt ->
                    mappedEvents.none { it.eventId == sumEvt.eventId } &&
                        regEventsNotInMapped.none { it.eventId == sumEvt.eventId }
                }

            val allEvents = mappedEvents + regEventsNotInMapped + summaryEventsNotInMapped

            fun isEnded(event: DashboardUpcomingEvent): Boolean {
                if (event.eventEndAt != null && event.eventEndAt.isBefore(now)) return true
                if (event.status.equals("Completed", ignoreCase = true) ||
                    event.status.equals("Ended", ignoreCase = true)
                ) return true
                return false
            }

            fun isActive(event: DashboardUpcomingEvent): Boolean {
                if (isEnded(event)) return false
                if (event.status.equals("Active", ignoreCase = true) ||
                    event.status.equals("Ongoing", ignoreCase = true) ||
                    event.status.equals("In Progress", ignoreCase = true)
                ) return true
                val start = event.eventStartAt
                return start != null && !start.isAfter(now)
            }

            val activeOrUpcomingComparator = Comparator<DashboardUpcomingEvent> { a, b ->
                val aActive = isActive(a)
                val bActive = isActive(b)
                if (aActive && !bActive) return@Comparator -1
                if (!aActive && bActive) return@Comparator 1
                val aStart = a.eventStartAt ?: Instant.MAX
                val bStart = b.eventStartAt ?: Instant.MAX
                aStart.compareTo(bStart)
            }

            // Next Event: only the attendee's own next registered event (ongoing first, then soonest).
            // Never fall back to a public or past event.
            val nextEvent = allEvents
                .filter { it.isRegistered && !isEnded(it) }
                .sortedWith(activeOrUpcomingComparator)
                .firstOrNull()

            val nextEventList = if (nextEvent != null) listOf(nextEvent) else emptyList()

            // Discover Events selection:
            // All candidates excluding nextEvent
            val remainingCandidates = allEvents.filter { it.eventId != nextEvent?.eventId }
            // Discover is for events the attendee can still join: one that has started no longer offers
            // Register, so it would be a dead end here (a registered ongoing event stays as Next Event).
            val upcomingCandidates = remainingCandidates
                .filter { !isEnded(it) && !isActive(it) }
                .sortedWith(activeOrUpcomingComparator)

            // Never show past or running events here; an empty list renders the "No upcoming events" state.
            val discoverEvents = upcomingCandidates.take(5)

            // Same definitions as the Registered tab chips: Registered = live registrations whose event
            // has not ended; Completed = live registrations whose event has ended.
            val tabCounts = DashboardStats.registrationCounts(registrations, now)
            // Every pass-head cell needs the per-registration rows. When /registrations fails the whole
            // stub reads "--": a summary-only count is not comparable with the normal path, because the
            // server's completedEventsCount also counts attended ongoing events.
            // shortcut: counts over the capped /my registrations page walk (MAX_PAGES * PAGE_SIZE), upgrade to a server-side count
            val attendeeCounts: AttendeeCounts? = if (registrationsResult is NetworkResult.Success) {
                DashboardStats.attendeeCounts(registrations, now)
            } else {
                null
            }
            val registeredCount: Long? = attendeeCounts?.registered?.toLong()
            val upcomingCount: Long? = attendeeCounts?.upcoming?.toLong()
            val checkInsCount: Long? = attendeeCounts?.checkIns?.toLong()
            val lifetimeRegistrationsCount: Long? = attendeeCounts?.events?.toLong()

            val completedCount: Long? =
                if (attendeeCounts != null) tabCounts.completed.toLong() else null

            if (summaryResult is NetworkResult.Success) {
                val summary = summaryResult.data.copy(
                    totalRegistrations = registeredCount,
                    completedEventsCount = completedCount,
                    checkInsCount = checkInsCount,
                    lifetimeRegistrationsCount = lifetimeRegistrationsCount,
                    upcomingCount = upcomingCount,
                    rewardsClaimedCount = deferredRewardsCount,
                    upcomingEvents = nextEventList,
                    discoverEvents = discoverEvents,
                )
                painted = true
                view?.showSummary(summary)
                // Only a complete answer is worth painting on the next cold open. A failed
                // registrations leg leaves every pass-head cell unknown, and a failed events leg
                // leaves the row blocks empty: either one would persist "--" counts or a false
                // "no upcoming events" that a later failure then keeps on screen.
                if (eventsResult is NetworkResult.Success && registrationsResult is NetworkResult.Success) {
                    persistSnapshot(summary, owner)
                }
            } else if (!fromSnapshot && (allEvents.isNotEmpty() || registrations.isNotEmpty())) {
                val fallbackSummary = DashboardSummary(
                    totalEvents = events.size.toLong(),
                    totalRegistrations = registeredCount,
                    totalTransactions = 0L,
                    totalPoints = 0L,
                    completedEventsCount = completedCount,
                    checkInsCount = checkInsCount,
                    lifetimeRegistrationsCount = lifetimeRegistrationsCount,
                    upcomingCount = upcomingCount,
                    rewardsClaimedCount = deferredRewardsCount,
                    totalNotifications = 0L,
                    fullName = sessionManager.getFullName(),
                    upcomingEvents = nextEventList,
                    discoverEvents = discoverEvents,
                )
                view?.showSummary(fallbackSummary)
                painted = true
                if (summaryResult is NetworkResult.Error) {
                    view?.showPartialError("stats", summaryResult.message)
                }
            } else if (summaryResult is NetworkResult.Error) {
                view?.showError(summaryResult.message)
            }

            if (currentUserResult is NetworkResult.Error) {
                view?.showPartialError("account", currentUserResult.message)
            }
            if (eventsResult is NetworkResult.Error) {
                view?.showPartialError("events", eventsResult.message)
            }
            if (registrationsResult is NetworkResult.Error) {
                view?.showPartialError("registrations", registrationsResult.message)
            }

            // Last: the redemptions read the cells above do not need. Its failure is not a screen
            // failure - the cell simply keeps "--" and the rest stays as painted.
            if (registrationsResult is NetworkResult.Success) {
                val rewards = claimedRewardsCount(registrationsResult.data)
                if (rewards != null) {
                    view?.showRewardsCount(rewards)
                } else {
                    Log.d(TAG, "claimed rewards unknown; pass-head cell keeps its placeholder")
                }
            }
        }
    }

    fun openSection(title: String, message: String) {
        view?.openSection(title, message)
    }

    /**
     * Paints the persisted dashboard before the network answers: the summary as it was last
     * rendered, its upcoming rows, and the pass-head counts recomputed from the persisted
     * registrations list (that list can be newer than the summary, and the cells must not show a
     * count the attendee has already changed).
     *
     * @return true when something was painted, so a later failed refresh keeps it instead of
     *   replacing it with the partial-data fallback.
     */
    private suspend fun paintSnapshot(): Boolean {
        val cache = cache ?: return false
        val summary = cache.read<DashboardSummary>(
            AttendeeSnapshots.DASHBOARD_SUMMARY,
            AttendeeSnapshots.SUMMARY_TYPE,
            AttendeeSnapshots.MAX_AGE_MS,
        )?.data ?: return false
        val upcoming = cache.read<List<DashboardUpcomingEvent>>(
            AttendeeSnapshots.DASHBOARD_UPCOMING,
            AttendeeSnapshots.UPCOMING_TYPE,
            AttendeeSnapshots.MAX_AGE_MS,
        )?.data
        val registrations = cache.read<List<RegistrationResponse>>(
            AttendeeSnapshots.REGISTRATIONS,
            AttendeeSnapshots.REGISTRATIONS_TYPE,
            AttendeeSnapshots.MAX_AGE_MS,
        )?.data

        val now = Instant.now()
        val counts = registrations?.let { DashboardStats.attendeeCounts(it, now) }
        val tabs = registrations?.let { DashboardStats.registrationCounts(it, now) }
        painted = true
        view?.showSummary(
            summary.copy(
                totalRegistrations = counts?.registered?.toLong() ?: summary.totalRegistrations,
                completedEventsCount = tabs?.completed?.toLong() ?: summary.completedEventsCount,
                checkInsCount = counts?.checkIns?.toLong() ?: summary.checkInsCount,
                lifetimeRegistrationsCount = counts?.events?.toLong() ?: summary.lifetimeRegistrationsCount,
                upcomingCount = counts?.upcoming?.toLong() ?: summary.upcomingCount,
                upcomingEvents = upcoming ?: summary.upcomingEvents,
            ),
        )
        return true
    }

    /** Stores the answer this load painted, so the next cold open starts from it. */
    private suspend fun persistSnapshot(summary: DashboardSummary, owner: String) {
        val cache = cache ?: return
        cache.write(AttendeeSnapshots.DASHBOARD_SUMMARY, summary, owner)
        cache.write(AttendeeSnapshots.DASHBOARD_UPCOMING, summary.upcomingEvents.orEmpty(), owner)
    }

    fun logout() {
        RegistrationsCache.clear()
        sessionManager.clearSession()
    }

    private fun computeEventStatus(event: AttendeeEventResponse, now: Instant): String {
        val status = EventStatusBadgeStyler.resolve(event.status, event.eventStartAt, event.eventEndAt, now)
        return EventStatusBadgeStyler.displayLabel(status)
    }

    private companion object {
        const val TAG = "DashboardPresenter"
    }
}

data class RegistrationCounts(val registered: Int, val completed: Int)

data class AttendeeCounts(val registered: Int, val checkIns: Int, val events: Int, val upcoming: Int = 0)

/** Pure dashboard tile math, kept free of Android types so it can be unit tested. */
object DashboardStats {
    fun isLive(status: RegistrationStatus): Boolean =
        status != RegistrationStatus.CANCELLED && status != RegistrationStatus.NO_SHOW

    fun registrationCounts(
        registrations: List<com.thedavelopers.eventqr.features.registrations.model.dto.RegistrationResponse>,
        now: Instant,
    ): RegistrationCounts {
        val live = registrations.filter { isLive(it.status) }
        val completed = live.count { it.eventEndAt?.isBefore(now) == true }
        return RegistrationCounts(registered = live.size - completed, completed = completed)
    }

    /**
     * The three pass-head cells: ahead of me, showed up, lifetime sign-ups.
     *
     * [registered] is live registrations whose event has not ended, [checkIns] is every
     * non-cancelled registration with an attendance timestamp (attendedAt, else the legacy
     * enteredAt; ongoing events included), and [events] is every non-cancelled registration
     * including ended and no-show ones. So checkIns <= events.
     */
    fun attendeeCounts(
        registrations: List<com.thedavelopers.eventqr.features.registrations.model.dto.RegistrationResponse>,
        now: Instant,
    ): AttendeeCounts = AttendeeCounts(
        registered = registrationCounts(registrations, now).registered,
        checkIns = registrations.count {
            it.status != RegistrationStatus.CANCELLED && (it.attendedAt != null || it.enteredAt != null)
        },
        events = registrations.count { it.status != RegistrationStatus.CANCELLED },
        upcoming = registrations.count { isLive(it.status) && it.eventStartAt?.isAfter(now) == true },
    )

    /**
     * The three pass-head cells, in order: registered, check-ins, events.
     *
     * Every value is the attendee's own, never the event catalogue size. A null [summary] means
     * the dashboard failed to load, so every value stays unknown and [format] renders "--"
     * instead of a fake zero.
     */
    fun passHeadStats(
        summary: DashboardSummary?,
        upcomingLabel: String,
        attendedLabel: String,
        rewardsLabel: String,
        format: (Long?) -> String,
    ): List<PassStat> = listOf(
        PassStat(format(summary?.upcomingCount), upcomingLabel),
        PassStat(format(summary?.checkInsCount), attendedLabel),
        PassStat(format(summary?.rewardsClaimedCount), rewardsLabel),
    )
}
