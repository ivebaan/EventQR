package com.thedavelopers.eventqr.features.organizer.attendees

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.thedavelopers.eventqr.R
import com.thedavelopers.eventqr.core.cache.LastKnownCache
import com.thedavelopers.eventqr.core.cache.OrganizerSnapshots
import com.thedavelopers.eventqr.features.organizer.AttendeeManagementAdapter
import com.thedavelopers.eventqr.features.organizer.EXTRA_EVENT_ID
import com.thedavelopers.eventqr.features.organizer.EXTRA_EVENT_TITLE
import com.thedavelopers.eventqr.features.organizer.NAV_ATTENDEES
import com.thedavelopers.eventqr.features.organizer.OrganizerMvpAttendee
import com.thedavelopers.eventqr.features.organizer.OrganizerMvpDataSource
import com.thedavelopers.eventqr.features.organizer.OrganizerMvpEvent
import com.thedavelopers.eventqr.features.organizer.OrganizerMvpLoad
import com.thedavelopers.eventqr.features.organizer.OrganizerRepository
import com.thedavelopers.eventqr.features.organizer.bottomNav
import com.thedavelopers.eventqr.features.organizer.intentEventId
import com.thedavelopers.eventqr.features.organizer.pickerDateLabel
import com.thedavelopers.eventqr.features.organizer.pickerOrdered
import com.thedavelopers.eventqr.features.organizer.resolveSelectedEvent
import com.thedavelopers.eventqr.features.organizer.saveSelectedEventId
import com.thedavelopers.eventqr.features.organizer.selectedEventId
import com.thedavelopers.eventqr.ui.components.EventPickerField
import com.thedavelopers.eventqr.ui.components.EventPickerOption
import com.thedavelopers.eventqr.ui.components.EventQrEmptyState
import com.thedavelopers.eventqr.ui.components.PassHeadBinder
import com.thedavelopers.eventqr.ui.theme.applyEventQrSystemBarAppearance
import com.thedavelopers.eventqr.ui.theme.applyEventQrTopInsetPadding
import kotlinx.coroutines.launch

/**
 * Organizer attendees list (§6.4).
 *
 * The head is the compact pass head: title only, no eyebrow and no stat strip. The counts live on
 * the Reports page. The event selector is a 56dp field row. Rows are grouped list rows with one
 * status badge.
 */
open class AttendeeManagementActivity : AppCompatActivity() {
    private var repositoryFactory: (Context) -> OrganizerRepository = { OrganizerRepository(it) }

    /** Tests set this before the first load to replace the network repository and the cache. */
    @VisibleForTesting
    internal fun installRepositoryFactory(factory: (Context) -> OrganizerRepository) {
        repositoryFactory = factory
    }

    @VisibleForTesting
    internal var cacheOverride: LastKnownCache? = null

    private val cache: LastKnownCache
        get() = cacheOverride ?: LastKnownCache.get(this)

    private lateinit var repository: OrganizerRepository
    private var selectedEvent: OrganizerMvpEvent? = null
    private var eventsLoadFailed = false
    private var eventsLoadJob: kotlinx.coroutines.Job? = null
    private lateinit var adapter: AttendeeManagementAdapter
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var skeletonLoading: View
    private lateinit var emptyState: EventQrEmptyState
    private lateinit var eventPicker: EventPickerField
    private lateinit var currentEventLabel: TextView
    private lateinit var passHead: View

    private var attendees: List<OrganizerMvpAttendee> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_attendee_management)
        applyEventQrSystemBarAppearance(lightStatusBars = false)
        findViewById<View>(R.id.passHeadIdentity).applyEventQrTopInsetPadding()

        repository = repositoryFactory(this)

        passHead = findViewById(R.id.passHead)
        swipeRefresh = findViewById(R.id.swipeRefreshAttendeeManagement)
        skeletonLoading = findViewById(R.id.skeletonLoading)
        emptyState = findViewById(R.id.layoutAttendeesEmpty)
        eventPicker = findViewById(R.id.event_picker_field)
        currentEventLabel = findViewById(R.id.txtCurrentEventLabel)

        bindPassHead()
        findViewById<LinearLayout>(R.id.layoutBottomNavHost).addView(bottomNav(NAV_ATTENDEES))

        swipeRefresh.setOnRefreshListener {
            // Pull-to-refresh bypasses the shared snapshot: the user asked for fresh rows.
            repository.invalidateEvents()
            // No event yet means the event list failed to load: a pull retries it.
            if (selectedEvent == null) loadEvents() else refreshSelectedEventAttendees()
        }

        findViewById<View>(R.id.btnFilter).setOnClickListener {
            val current = selectedEvent ?: return@setOnClickListener
            startActivity(
                Intent(this@AttendeeManagementActivity, SearchAttendeesActivity::class.java)
                    .putExtra(EXTRA_EVENT_ID, current.id)
                    .putExtra(EXTRA_EVENT_TITLE, current.title),
            )
        }

        emptyState.setAction(getString(R.string.common_retry)) { loadEvents() }

        loadEvents()
    }

    private fun bindPassHead() {
        PassHeadBinder.bindCompact(
            root = passHead,
            title = getString(R.string.ui_organizer_attendees),
        )
    }

    private fun loadEvents() {
        // A newer reload (pull, retry) replaces the in-flight one: a read that started before the
        // snapshot was invalidated must not repaint stale rows when it lands.
        eventsLoadJob?.cancel()
        eventsLoadJob = lifecycleScope.launch {
            val eventsLoad = repository.loadApprovedOrganizerEvents()
            if (eventsLoad.source == OrganizerMvpDataSource.ERROR) {
                showEventsLoadFailedState(eventsLoad.message)
                return@launch
            }
            val events = eventsLoad.data
            restoreAfterEventsLoadFailure()

            if (events.isEmpty()) {
                showNoEventsAvailableState()
                return@launch
            }

            adapter = AttendeeManagementAdapter { attendee -> openDetails(attendee) }
            findViewById<RecyclerView>(R.id.recyclerAttendees).apply {
                layoutManager = LinearLayoutManager(this@AttendeeManagementActivity)
                adapter = this@AttendeeManagementActivity.adapter
            }

            val eventId = intentEventId() ?: selectedEventId().takeIf { it.isNotBlank() }
            val resolvedEvent = resolveSelectedEvent(events, eventId)

            if (resolvedEvent != null) {
                selectedEvent = resolvedEvent
                bindEventPicker(events, resolvedEvent)
                loadAttendees()
            } else {
                showNoEventEmptyState()
            }
        }
    }

    /**
     * Feeds the shared picker: the manageable set (Approved / Active / Completed) in nearest-date
     * order, the current selection and the selection callback. `events` is already the set the
     * server accepts for attendee management - do not narrow it to Approved only.
     */
    private fun bindEventPicker(events: List<OrganizerMvpEvent>, selected: OrganizerMvpEvent?) {
        eventPicker.setOnEventSelectedListener { id ->
            val event = events.firstOrNull { it.id == id } ?: return@setOnEventSelectedListener
            if (event.id == selectedEvent?.id) return@setOnEventSelectedListener
            selectedEvent = event
            repository.saveSelectedEventId(event.id)
            saveSelectedEventId(event.id)
            restoreNormalUi()
            // The previous event's rows must not survive into this one, and must not block its paint.
            attendees = emptyList()
            loadAttendees()
        }
        eventPicker.setOptions(
            events.pickerOrdered().map {
                EventPickerOption(
                    id = it.id,
                    title = it.title.ifBlank { getString(R.string.common_untitled_event) },
                    meta = it.pickerDateLabel(),
                )
            },
            selected?.id,
        )
    }
    private fun refreshSelectedEventAttendees() {
        val event = selectedEvent ?: return
        // Same replacement rule as loadEvents: the newest pull owns the render.
        eventsLoadJob?.cancel()
        eventsLoadJob = lifecycleScope.launch {
            val currentEventId = event.id
            val eventsLoad = repository.loadApprovedOrganizerEvents()
            // A failed fetch must not run resolveSelectedEvent on an empty list: that would save "no selection"
            // and wipe the persisted event. Keep the current event and just reload its attendees.
            val latestSelectedEvent = if (eventsLoad.source == OrganizerMvpDataSource.ERROR) {
                null
            } else {
                resolveSelectedEvent(eventsLoad.data, currentEventId)
            }
            if (latestSelectedEvent != null) {
                selectedEvent = latestSelectedEvent
                repository.saveSelectedEventId(latestSelectedEvent.id)
                saveSelectedEventId(latestSelectedEvent.id)
                bindEventPicker(eventsLoad.data, latestSelectedEvent)
            }
            // A pull asks the network for the first frame: the persisted rows are skipped.
            loadAttendees(forceNetwork = true)
        }
    }

    /** Test handle: a pull (forceNetwork) bypasses the persisted snapshot. */
    @VisibleForTesting
    internal fun loadAttendees(forceNetwork: Boolean = false) {
        val event = selectedEvent ?: return
        lifecycleScope.launch {
            val eventIdAtRequestTime = event.id
            // The persisted list paints first: a cold open shows the rows with no skeleton, and the
            // refresh lands under them.
            val painted = !forceNetwork && !swipeRefresh.isRefreshing && attendees.isEmpty() && paintSnapshot(eventIdAtRequestTime)
            if (!painted && !swipeRefresh.isRefreshing) {
                skeletonLoading.visibility = View.VISIBLE
            }
            val load = repository.loadAttendeesForMvp(eventIdAtRequestTime)
            if (eventIdAtRequestTime != selectedEvent?.id) {
                swipeRefresh.isRefreshing = false
                skeletonLoading.visibility = View.GONE
                return@launch
            }
            if (load.source == OrganizerMvpDataSource.ERROR && painted) {
                // A failed refresh keeps the painted rows instead of replacing them with the error state.
                swipeRefresh.isRefreshing = false
                return@launch
            }
            attendees = load.data
            swipeRefresh.isRefreshing = false
            render(load)
        }
    }

    /** Paints the persisted `organizer/attendees/{eventId}` answer; false when there is none. */
    private suspend fun paintSnapshot(eventId: String): Boolean {
        val snapshot = cache.read<List<OrganizerMvpAttendee>>(
            OrganizerSnapshots.attendees(eventId),
            OrganizerSnapshots.ATTENDEES_TYPE,
            OrganizerSnapshots.MAX_AGE_MS,
        )?.data ?: return false
        attendees = snapshot
        skeletonLoading.visibility = View.GONE
        render(OrganizerMvpLoad(snapshot, OrganizerMvpDataSource.BACKEND))
        return true
    }

    private fun showNoEventEmptyState() {
        skeletonLoading.visibility = View.GONE
        swipeRefresh.isEnabled = false
        currentEventLabel.visibility = View.GONE
        emptyState.visibility = View.VISIBLE
        emptyState.show(
            getString(R.string.attendee_management_no_event_selected),
            getString(R.string.attendee_management_select_an_event_from_the_dropdown_ab),
        )
    }

    /** The event list failed to load (not "no events"): say so and let a pull-to-refresh retry. */
    @VisibleForTesting
    internal fun showEventsLoadFailedState(message: String?) {
        eventsLoadFailed = true
        skeletonLoading.visibility = View.GONE
        swipeRefresh.isRefreshing = false
        swipeRefresh.isEnabled = true
        currentEventLabel.visibility = View.GONE
        eventPicker.visibility = View.GONE
        emptyState.visibility = View.VISIBLE
        emptyState.show(
            getString(R.string.organizer_dashboard_events_load_failed),
            getString(
                R.string.organizer_events_load_failed_pull_to_retry,
                message?.takeIf { it.isNotBlank() } ?: getString(R.string.organizer_try_again_later),
            ),
        )
    }

    private fun restoreAfterEventsLoadFailure() {
        if (!eventsLoadFailed) return
        eventsLoadFailed = false
        swipeRefresh.isRefreshing = false
        currentEventLabel.visibility = View.VISIBLE
        eventPicker.visibility = View.VISIBLE
        emptyState.visibility = View.GONE
    }

    private fun showNoEventsAvailableState() {
        skeletonLoading.visibility = View.GONE
        swipeRefresh.isEnabled = false
        currentEventLabel.visibility = View.GONE
        eventPicker.visibility = View.GONE
        emptyState.visibility = View.VISIBLE
        emptyState.show(
            getString(R.string.attendee_management_no_events_available),
            getString(R.string.attendee_management_create_an_event_in_the_events_tab_to),
        )
    }

    private fun restoreNormalUi() {
        currentEventLabel.visibility = View.VISIBLE
        swipeRefresh.isEnabled = true
        emptyState.visibility = View.GONE
    }

    private fun render(load: OrganizerMvpLoad<List<OrganizerMvpAttendee>>) {
        skeletonLoading.visibility = View.GONE

        adapter.submitItems(attendees)
        emptyState.visibility = if (attendees.isEmpty()) View.VISIBLE else View.GONE
        when {
            load.source == OrganizerMvpDataSource.ERROR -> {
                emptyState.show(
                    getString(R.string.attendee_management_unable_to_load_attendees),
                    load.message ?: getString(R.string.organizer_try_again_later),
                )
            }
            attendees.isEmpty() -> {
                emptyState.show(
                    getString(R.string.attendee_management_no_attendees_registered_yet),
                    getString(R.string.attendee_management_attendees_will_appear_here_once_they),
                )
                // Nothing failed: the Retry action belongs to the error state only.
                emptyState.setAction(null, primary = false, onClick = null)
            }
        }
    }

    private fun openDetails(attendee: OrganizerMvpAttendee) {
        val event = selectedEvent ?: return
        startActivity(
            Intent(this, AttendeeDetailsActivity::class.java)
                .putExtra(EXTRA_EVENT_ID, event.id)
                .putExtra(EXTRA_EVENT_TITLE, event.title)
                .putExtra(SearchAttendeesActivity.EXTRA_ATTENDEE_ID, attendee.id),
        )
    }
}
