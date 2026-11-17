package com.thedavelopers.eventqr.features.admin

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.thedavelopers.eventqr.R
import com.thedavelopers.eventqr.core.api.NetworkResult
import com.thedavelopers.eventqr.core.api.dto.AccountRole
import com.thedavelopers.eventqr.core.api.dto.EventRequestStatus
import com.thedavelopers.eventqr.features.events.model.dto.EventRequestResponse
import com.thedavelopers.eventqr.ui.components.CountLabel
import com.thedavelopers.eventqr.ui.components.EventQrEmptyState
import com.thedavelopers.eventqr.ui.components.EventQrSwipeRefreshLayout
import com.thedavelopers.eventqr.ui.components.PassHeadBinder
import com.thedavelopers.eventqr.ui.components.asFilterChips
import com.thedavelopers.eventqr.ui.components.showInlineError
import com.thedavelopers.eventqr.ui.icons.AppIcons
import com.thedavelopers.eventqr.ui.theme.applyEventQrSystemBarAppearance
import com.thedavelopers.eventqr.ui.theme.applyEventQrTopInsetPadding
import java.time.Instant
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Admin event requests list (§6.5). Compact pass head, one shared chip row, flat 88dp page rows.
 * The filter chips carry their state in the fill (§5.6), so no state drawables and no per-chip
 * elevation are set from code. Pending is the default filter, so there is no `All` chip: the chip
 * row is exactly the queue an admin works.
 */
class AdminEventApprovalBackendActivity : AppCompatActivity() {

    /** Tests set this before the first load to replace the network repository. */
    @VisibleForTesting
    internal var repositoryOverride: AdminRepository? = null

    private val repository: AdminRepository by lazy {
        repositoryOverride ?: AdminRepository(this)
    }

    private lateinit var adapter: AdminEventRequestAdapter

    private lateinit var swipeRefresh: EventQrSwipeRefreshLayout
    private lateinit var chipPending: TextView
    private lateinit var chipApproved: TextView
    private lateinit var chipRejected: TextView
    private lateinit var textCount: TextView

    private lateinit var skeletonRequests: View
    private lateinit var textEmpty: EventQrEmptyState
    private lateinit var recyclerRequests: RecyclerView
    private lateinit var errorStrip: View
    private lateinit var progressRefresh: LinearProgressIndicator

    private var allRequests = emptyList<EventRequestResponse>()
    private var currentFilter: EventRequestFilter = EventRequestFilter.PENDING
    private var loadFailed = false
    // A chip flip before the first successful load must not paint the filter's empty copy over a
    // load that is still running (§6.8 #1), so rendering waits for the first answer.
    private var loaded = false
    // The newest list load. Retry, pull to refresh and a detail return all funnel here, so only the
    // last one may write `allRequests`.
    private var loadJob: Job? = null

    private val detailLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        loadRequests(showLoading = false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_event_requests)
        applyEventQrSystemBarAppearance(lightStatusBars = false)

        bindViews()
        bindHeader()
        bindNavigation()
        bindFilters()
        restoreFilter(savedInstanceState)

        adapter = AdminEventRequestAdapter { request ->
            val detailIntent = Intent(this, AdminEventRequestDetailActivity::class.java)
                .putExtra(AdminEventRequestDetailActivity.EXTRA_REQUEST_ID, request.eventRequestId.toString())
            detailLauncher.launch(detailIntent)
        }

        recyclerRequests.layoutManager = LinearLayoutManager(this)
        recyclerRequests.adapter = adapter

        verifyAdminAccess()
    }

    private fun bindViews() {
        swipeRefresh = findViewById(R.id.swipeRefreshRequests)
        chipPending = findViewById(R.id.chipPending)
        chipApproved = findViewById(R.id.chipApproved)
        chipRejected = findViewById(R.id.chipRejected)
        asFilterChips(chipPending, chipApproved, chipRejected)

        skeletonRequests = findViewById(R.id.skeletonRequests)
        textEmpty = findViewById(R.id.textEmpty)
        recyclerRequests = findViewById(R.id.recyclerRequests)
        errorStrip = findViewById(R.id.errorStrip)
        errorStrip.findViewById<View>(R.id.btnErrorStripRetry).setOnClickListener {
            // The strip is the only refresh cue, so a retry tap paints it while it runs. The rows
            // stay put: no skeleton for a retry over readable content.
            swipeRefresh.isRefreshing = true
            loadRequests(showLoading = false)
        }

        // §6.8: the refresh state is a flat in-flow strip, not the swipe layout's raised disc.
        // While a finger pulls it is determinate, so the gesture has an affordance before release;
        // the refresh listener owns the indeterminate state, and both leave it hidden at the end.
        progressRefresh = findViewById(R.id.progressRefresh)
        swipeRefresh.onRefreshStateChanged = { refreshing ->
            if (refreshing) {
                progressRefresh.isIndeterminate = true
                progressRefresh.visibility = View.VISIBLE
            } else {
                hideRefreshStrip()
            }
        }
        swipeRefresh.onPullProgress = { fraction ->
            when (fraction) {
                // A released drag hands the strip over to the refresh state above.
                null -> if (!swipeRefresh.isRefreshing) hideRefreshStrip()
                else -> {
                    progressRefresh.isIndeterminate = false
                    progressRefresh.visibility = View.VISIBLE
                    progressRefresh.setProgressCompat((fraction * 100f).roundToInt(), false)
                }
            }
        }
        swipeRefresh.setOnRefreshListener { loadRequests(showLoading = false) }
    }

    private fun bindHeader() {
        val head = findViewById<View>(R.id.passHead)
        head.findViewById<View>(R.id.passHeadIdentity).applyEventQrTopInsetPadding()
        PassHeadBinder.bindCompact(
            root = head,
            title = getString(R.string.ui_admin_requests_title),
        )
        textCount = head.findViewById<TextView>(R.id.passHeadTitleCount).apply {
            visibility = View.VISIBLE
        }
    }

    private fun bindNavigation() {
        configureAdminBottomNav(AdminBottomNavItem.REQUESTS)
    }

    private fun bindFilters() {
        chipPending.setOnClickListener { applyFilter(EventRequestFilter.PENDING) }
        chipApproved.setOnClickListener { applyFilter(EventRequestFilter.APPROVED) }
        chipRejected.setOnClickListener { applyFilter(EventRequestFilter.REJECTED) }
        updateFilterChips(EventRequestFilter.PENDING)
    }

    /**
     * A rotation must not move the admin off the tab they were reading. The filter is UI state, not
     * data, so the bundle is enough; an unknown stored name keeps the default.
     */
    private fun restoreFilter(savedInstanceState: Bundle?) {
        val saved = savedInstanceState?.getString(STATE_FILTER) ?: return
        val filter = EventRequestFilter.entries.firstOrNull { it.name == saved } ?: return
        currentFilter = filter
        updateFilterChips(filter)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_FILTER, currentFilter.name)
    }

    private fun verifyAdminAccess() {
        setLoadingState(true)
        lifecycleScope.launch {
            when (val result = repository.getCurrentUser()) {
                is NetworkResult.Success -> {
                    if (result.data.role != AccountRole.ADMIN && result.data.role != AccountRole.SUPER_ADMIN) {
                        showAccessDenied()
                    } else {
                        loadRequests(showLoading = true)
                    }
                }
                is NetworkResult.Error -> {
                    setLoadingState(false)
                    showError(toFriendlyError(result.message)) { verifyAdminAccess() }
                }
                NetworkResult.Loading -> Unit
            }
        }
    }

    private fun loadRequests(showLoading: Boolean) {
        errorStrip.visibility = View.GONE
        if (showLoading && !swipeRefresh.isRefreshing) {
            setLoadingState(true)
        }

        // The strip's Retry, pull to refresh and the return from the detail can overlap; the newest
        // request owns the list, so an older answer never repaints rows over it.
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            when (val result = repository.loadAllEventRequests()) {
                is NetworkResult.Success -> {
                    swipeRefresh.isRefreshing = false
                    allRequests = result.data
                    loadFailed = false
                    loaded = true
                    errorStrip.visibility = View.GONE
                    setLoadingState(false)
                    renderList()
                }
                is NetworkResult.Error -> {
                    swipeRefresh.isRefreshing = false
                    setLoadingState(false)
                    val message = toFriendlyError(result.message)
                    // Rows already on screen stay put: the failure is a transient strip, not a
                    // page state. Only a page with no data at all hands the slot to the error state.
                    if (allRequests.isEmpty()) {
                        showError(message) { loadRequests(showLoading = true) }
                    } else {
                        errorStrip.showInlineError(message)
                    }
                }
                NetworkResult.Loading -> Unit
            }
        }
    }

    private fun applyFilter(filter: EventRequestFilter) {
        currentFilter = filter
        updateFilterChips(filter)
        // The first load has no answer yet, so a chip flip must not paint its empty copy over the
        // running request (§6.8 #1). The failure owns the slot until a load succeeds.
        if (!loaded || loadFailed) return
        renderList()
    }

    private fun renderList() {
        // Pending is a work queue: oldest first, so the request that has waited longest is on top.
        // The decided tabs answer "what just happened": newest first. Undated rows sink last in both.
        val comparator = when (currentFilter) {
            EventRequestFilter.PENDING -> compareBy<EventRequestResponse> { it.createdAt ?: Instant.MAX }
            EventRequestFilter.APPROVED, EventRequestFilter.REJECTED ->
                compareByDescending { it.createdAt ?: Instant.MIN }
        }
        val filtered = allRequests
            .filter { it.status == currentFilter.status }
            .sortedWith(comparator)
        adapter.submit(filtered)
        // shortcut: GET /admin/event-requests returns the whole list with no page or total, so the
        // loaded list is the page total. Swap in a server total when the endpoint grows paging.
        textCount.text = CountLabel.format(filtered.size.toLong(), getString(R.string.ui_admin_requests_count))
        if (filtered.isEmpty()) {
            recyclerRequests.visibility = View.GONE
            showFilterEmpty()
        } else {
            recyclerRequests.visibility = View.VISIBLE
            textEmpty.hide()
        }
    }

    /** §6.8: each chip owns its empty copy. With no All chip there is no state to clear. */
    private fun showFilterEmpty() {
        val titleRes: Int
        val bodyRes: Int
        when (currentFilter) {
            EventRequestFilter.PENDING -> {
                titleRes = R.string.ui_admin_requests_empty_pending_title
                bodyRes = R.string.ui_admin_requests_empty_pending_body
            }
            EventRequestFilter.APPROVED -> {
                titleRes = R.string.ui_admin_requests_empty_approved_title
                bodyRes = R.string.ui_admin_requests_empty_approved_body
            }
            EventRequestFilter.REJECTED -> {
                titleRes = R.string.ui_admin_requests_empty_rejected_title
                bodyRes = R.string.ui_admin_requests_empty_rejected_body
            }
        }
        textEmpty.setIcon(AppIcons.Request)
        textEmpty.show(getString(titleRes), getString(bodyRes))
        textEmpty.setAction(null)
    }

    private fun updateFilterChips(filter: EventRequestFilter) {
        chipPending.isSelected = filter == EventRequestFilter.PENDING
        chipApproved.isSelected = filter == EventRequestFilter.APPROVED
        chipRejected.isSelected = filter == EventRequestFilter.REJECTED
    }

    private fun setLoadingState(loading: Boolean) {
        skeletonRequests.visibility = if (loading && !swipeRefresh.isRefreshing) View.VISIBLE else View.GONE
        recyclerRequests.visibility = if (loading) View.GONE else View.VISIBLE
        if (loading) {
            textEmpty.hide()
        }
    }

    /** The strip is one state: a failure or a finished refresh must leave none of it painted. */
    private fun hideRefreshStrip() {
        progressRefresh.visibility = View.GONE
    }

    /** §6.8 state 3: a failed load owns the empty slot, with the recovery as its only action. */
    private fun showError(message: String, retry: () -> Unit) {
        loadFailed = true
        // A failure owns the page, so no refresh may keep the strip (or a restored flag) alive.
        swipeRefresh.isRefreshing = false
        hideRefreshStrip()
        textCount.text = ""
        recyclerRequests.visibility = View.GONE
        textEmpty.setIcon(AppIcons.Warning)
        textEmpty.show(getString(R.string.ui_admin_requests_error_title), message)
        textEmpty.setAction(getString(R.string.common_retry)) { retry() }
    }

    /** Access denied is one in-flow error with one action. No toast duplicates the message. */
    private fun showAccessDenied() {
        loadFailed = true
        setLoadingState(false)
        swipeRefresh.isRefreshing = false
        hideRefreshStrip()
        textCount.text = ""
        recyclerRequests.visibility = View.GONE
        textEmpty.setIcon(AppIcons.Warning)
        textEmpty.show(getString(R.string.ui_admin_error_admin_required))
        textEmpty.setAction(getString(R.string.ui_admin_go_back)) { finish() }
    }

    private fun toFriendlyError(message: String): String {
        val normalized = message.lowercase()
        return when {
            normalized.contains("disabled") || normalized.contains("suspend") -> getString(R.string.ui_admin_error_disabled)
            normalized.contains("401") || normalized.contains("unauthorized") -> getString(R.string.ui_admin_error_session)
            normalized.contains("403") || normalized.contains("forbidden") || normalized.contains("admin access") -> getString(R.string.ui_admin_error_admin_required)
            normalized.contains("404") || normalized.contains("not found") -> getString(R.string.ui_admin_error_not_found)
            normalized.contains("409") || normalized.contains("conflict") -> getString(R.string.ui_admin_error_conflict)
            normalized.contains("400") || normalized.contains("invalid") || normalized.contains("bad request") -> getString(R.string.ui_admin_error_bad_request)
            normalized.contains("500") -> getString(R.string.ui_admin_error_server)
            normalized.contains("unable to resolve host") || normalized.contains("failed to connect") || normalized.contains("timeout") -> getString(R.string.ui_admin_error_network)
            else -> message
        }
    }

    private companion object {
        const val STATE_FILTER = "adminRequestsFilter"
    }

    private enum class EventRequestFilter(val status: EventRequestStatus) {
        PENDING(EventRequestStatus.PENDING),
        APPROVED(EventRequestStatus.APPROVED),
        REJECTED(EventRequestStatus.REJECTED),
    }
}
