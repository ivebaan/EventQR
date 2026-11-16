package com.thedavelopers.eventqr.features.staff

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.thedavelopers.eventqr.R
import com.thedavelopers.eventqr.core.api.NetworkResult
import com.thedavelopers.eventqr.core.api.dto.AccountRole
import com.thedavelopers.eventqr.core.cache.LastKnownCache
import com.thedavelopers.eventqr.core.cache.StaffSnapshots
import com.thedavelopers.eventqr.core.session.SessionManager
import com.thedavelopers.eventqr.core.util.RoleMapper
import com.thedavelopers.eventqr.features.staff.model.dto.StaffAssignedEventResponse
import com.thedavelopers.eventqr.ui.components.CountLabel
import com.thedavelopers.eventqr.ui.components.EventQrEmptyState
import com.thedavelopers.eventqr.ui.components.PassHeadBinder
import com.thedavelopers.eventqr.ui.components.asFilterChips
import com.thedavelopers.eventqr.ui.icons.AppIcons
import com.thedavelopers.eventqr.ui.theme.applyEventQrSystemBarAppearance
import com.thedavelopers.eventqr.ui.theme.applyEventQrTopInsetPadding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Assigned events (§6.3). Compact pass head with a count, a chip row of four filters, and the
 * shared EventCard rows. Filter state is the chip fill only - no check icon, no stroke swap.
 */
open class StaffAssignedEventsActivity : AppCompatActivity() {
    private var repositoryFactory: (Context) -> StaffRepository = { StaffRepository(it) }

    /** Tests set these before the first load to replace the network repository and the cache. */
    @VisibleForTesting
    internal fun installRepositoryFactory(factory: (Context) -> StaffRepository) {
        repositoryFactory = factory
    }

    @VisibleForTesting
    internal var cacheOverride: LastKnownCache? = null

    private val cache: LastKnownCache
        get() = cacheOverride ?: LastKnownCache.get(this)

    private lateinit var repository: StaffRepository
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: StaffAssignedEventAdapter
    private lateinit var emptyState: EventQrEmptyState
    private lateinit var skeletonLoading: View
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var chipAll: TextView
    private lateinit var chipUpcoming: TextView
    private lateinit var chipActive: TextView
    private lateinit var chipCompleted: TextView
    private lateinit var countView: TextView

    private var isFirstResume = true
    private var selectedFilter: StaffEventFilter = StaffEventFilter.ALL
    private var allEvents: List<StaffAssignedEventResponse> = emptyList()
    private var loadFailed = false
    private var loadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sessionManager = SessionManager(this)
        if (!RoleMapper.isAtLeast(sessionManager.getUserRole(), AccountRole.STAFF)) {
            Toast.makeText(this, this.getString(R.string.staff_dashboard_access_denied_staff_or_above), Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setContentView(R.layout.activity_staff_assigned_events)
        applyEventQrSystemBarAppearance(lightStatusBars = false)
        findViewById<View>(R.id.passHeadIdentity).applyEventQrTopInsetPadding()
        repository = repositoryFactory(this)

        countView = findViewById<TextView>(R.id.passHeadTitleCount).apply { visibility = View.VISIBLE }
        PassHeadBinder.bindCompact(
            findViewById(R.id.passHead),
            title = getString(R.string.ui_staff_assigned_events),
            eyebrow = getString(R.string.ui_staff_role),
        )

        recyclerView = findViewById(R.id.recyclerAssignedEvents)
        emptyState = findViewById(R.id.txtAssignedEventsEmpty)
        skeletonLoading = findViewById(R.id.skeletonLoading)
        swipeRefresh = findViewById(R.id.swipeRefreshAssignedEvents)
        swipeRefresh.setOnRefreshListener {
            // A pull bypasses the snapshot: the memory copy goes, the persisted one is kept and rewritten
            // on success, so a failed pull still leaves the next cold open something to paint.
            repository.invalidateEvents()
            loadEvents(showLoading = false, forceNetwork = true)
        }

        chipAll = findViewById(R.id.chipAll)
        chipUpcoming = findViewById(R.id.chipUpcoming)
        chipActive = findViewById(R.id.chipActive)
        chipCompleted = findViewById(R.id.chipCompleted)
        asFilterChips(chipAll, chipUpcoming, chipActive, chipCompleted)

        chipAll.setOnClickListener { selectFilter(StaffEventFilter.ALL) }
        chipUpcoming.setOnClickListener { selectFilter(StaffEventFilter.UPCOMING) }
        chipActive.setOnClickListener { selectFilter(StaffEventFilter.ACTIVE) }
        chipCompleted.setOnClickListener { selectFilter(StaffEventFilter.COMPLETED) }

        adapter = StaffAssignedEventAdapter(
            onClick = { event -> openAttendees(event) },
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        configureStaffBottomNav(StaffBottomNavItem.EVENTS)
        updateFilterUI()
        loadEvents()
    }

    override fun onResume() {
        super.onResume()
        if (isFirstResume) {
            isFirstResume = false
            return
        }
        loadEvents()
    }

    private fun selectFilter(filter: StaffEventFilter) {
        selectedFilter = filter
        updateFilterUI()
        // The error state belongs to loadEvents; a filter tap must not overwrite it with empty copy.
        if (loadFailed) return
        renderFilteredEvents()
    }

    /** The fill is the state (§5.6): selected = primary, unselected = surface + interactive stroke. */
    private fun updateFilterUI() {
        val chips = mapOf(
            StaffEventFilter.ALL to chipAll,
            StaffEventFilter.UPCOMING to chipUpcoming,
            StaffEventFilter.ACTIVE to chipActive,
            StaffEventFilter.COMPLETED to chipCompleted,
        )
        for ((filter, chip) in chips) {
            chip.isSelected = filter == selectedFilter
            chip.contentDescription = chip.text
        }
    }

    /** Test handle: a pull (forceNetwork) bypasses the persisted snapshot. */
    @VisibleForTesting
    internal fun loadEvents(showLoading: Boolean = true, forceNetwork: Boolean = false) {
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            // The persisted list paints first: a cold open shows the rows with no skeleton, and the
            // refresh lands under them.
            if (!forceNetwork && allEvents.isEmpty()) paintSnapshot()
            if (showLoading && !swipeRefresh.isRefreshing && allEvents.isEmpty()) {
                skeletonLoading.visibility = View.VISIBLE
            }
            when (val result = repository.getEvents()) {
                is NetworkResult.Success -> {
                    allEvents = result.data
                    loadFailed = false
                    renderFilteredEvents()
                }
                is NetworkResult.Error -> {
                    // A failed refresh keeps the painted rows; the error state owns the screen only
                    // when there is nothing to show.
                    if (allEvents.isEmpty()) {
                        loadFailed = true
                        adapter.submitItems(emptyList())
                        emptyState.setIcon(AppIcons.Warning)
                        emptyState.show(
                            getString(R.string.ui_staff_events_error_title),
                            result.message.ifBlank { getString(R.string.ui_staff_load_failed_body) },
                        )
                        emptyState.setAction(getString(R.string.common_retry)) { loadEvents() }
                        emptyState.visibility = View.VISIBLE
                        countView.text = ""
                    }
                    skeletonLoading.visibility = View.GONE
                }
                NetworkResult.Loading -> Unit
            }
            swipeRefresh.isRefreshing = false
        }
    }

    /** Paints the persisted `staff/assignments` list so the screen is readable before the network. */
    private suspend fun paintSnapshot() {
        val events = cache.read<List<StaffAssignedEventResponse>>(
            StaffSnapshots.ASSIGNMENTS,
            StaffSnapshots.ASSIGNMENTS_TYPE,
            StaffSnapshots.MAX_AGE_MS,
        )?.data ?: return
        allEvents = events
        loadFailed = false
        renderFilteredEvents()
    }

    private fun renderFilteredEvents() {
        skeletonLoading.visibility = View.GONE
        val now = Instant.now()
        val active = allEvents.filter {
            it.eventStartAt?.isBefore(now) == true && it.eventEndAt?.isBefore(now) != true
        }.sortedBy { it.eventStartAt }
        val upcoming = allEvents.filter {
            it.eventStartAt?.isAfter(now) == true
        }.sortedBy { it.eventStartAt }
        val completed = allEvents.filter {
            it.eventEndAt?.isBefore(now) == true
        }.sortedByDescending { it.eventEndAt ?: it.eventStartAt }

        val ordered = active + upcoming + completed

        val filtered = when (selectedFilter) {
            StaffEventFilter.ALL -> ordered
            StaffEventFilter.ACTIVE -> active
            StaffEventFilter.UPCOMING -> upcoming
            StaffEventFilter.COMPLETED -> completed
        }

        countView.text = if (filtered.isEmpty()) {
            ""
        } else {
            CountLabel.format(filtered.size.toLong(), getString(R.string.ui_staff_events_count))
        }
        emptyState.setIcon(AppIcons.Event)
        // Reset the copy too: a previous load failure must not survive a successful render.
        emptyState.setContent(
            getString(R.string.ui_staff_no_assigned_events),
            getString(R.string.ui_staff_no_assigned_events_body),
        )
        emptyState.setAction(null)
        emptyState.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
        adapter.submitItems(filtered)
    }

    private fun openAttendees(event: StaffAssignedEventResponse) {
        startActivity(Intent(this, EventRegistrationsActivity::class.java).apply {
            putExtra(StaffScreenExtras.EXTRA_EVENT_ID, event.eventId.toString())
            putExtra(StaffScreenExtras.EXTRA_EVENT_TITLE, event.title)
        })
    }

    private enum class StaffEventFilter { ALL, UPCOMING, ACTIVE, COMPLETED }
}
