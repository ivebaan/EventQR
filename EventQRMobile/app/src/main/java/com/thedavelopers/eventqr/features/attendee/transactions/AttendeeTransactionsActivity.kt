package com.thedavelopers.eventqr.features.attendee

import com.thedavelopers.eventqr.ui.components.EventQrEmptyState
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.thedavelopers.eventqr.R
import com.thedavelopers.eventqr.ui.components.EventPickerField
import com.thedavelopers.eventqr.ui.components.EventPickerOption
import com.thedavelopers.eventqr.ui.icons.AppIcons
import com.thedavelopers.eventqr.features.transactions.TransactionHistoryAdapter
import com.thedavelopers.eventqr.features.transactions.TransactionHistoryRow

open class AttendeeTransactionsActivity : AppCompatActivity(), TransactionHistoryContract.View {
    /** Tests set this before the first load to replace the network repository. */
    internal var repositoryOverride: AttendeeRepository? = null

    private lateinit var presenter: TransactionHistoryPresenter
    private val repository: AttendeeRepository by lazy {
        repositoryOverride ?: AttendeeRepository(this)
    }
    private lateinit var adapter: TransactionHistoryAdapter
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var skeletonLoading: View
    private lateinit var emptyText: EventQrEmptyState
    private lateinit var errorStrip: View
    private lateinit var retryButton: TextView
    private lateinit var eventPicker: EventPickerField
    private lateinit var summaryCountText: TextView
    private lateinit var recyclerView: RecyclerView

    private var allTransactions: List<TransactionHistoryRow> = emptyList()
    private var eventFilterOptions: List<EventPickerOption> = emptyList()
    private var selectedEventId: String? = null
    private var pendingInitialEventId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_user_transaction_history)
        configureAttendeeBottomNav(AttendeeBottomNavItem.PROFILE)

        presenter = TransactionHistoryPresenter(this, repository)

        swipeRefresh = findViewById(R.id.swipeRefreshTransactions)
        skeletonLoading = findViewById(R.id.skeletonLoading)
        emptyText = findViewById(R.id.txtTransactionsEmpty)
        errorStrip = findViewById(R.id.errorTransactions)
        retryButton = findViewById(R.id.btnTransactionsRetry)
        eventPicker = findViewById(R.id.event_picker_field)
        summaryCountText = findViewById(R.id.txtHistoryTransactionCount)
        recyclerView = findViewById(R.id.recyclerTransactions)

        findViewById<View>(R.id.nav_header_back).setOnClickListener { finish() }
        retryButton.setOnClickListener { presenter.load() }
        swipeRefresh.setOnRefreshListener { presenter.load() }

        // The picker's ids are all Strings: "All events" is the reserved sentinel, mapped back to
        // null (no filter) so the row filtering keeps its existing meaning.
        eventPicker.setOnEventSelectedListener { id ->
            selectedEventId = id.takeUnless { it == ALL_EVENTS_OPTION_ID }
            applySelectedFilter()
        }

        pendingInitialEventId = intent.getStringExtra(EXTRA_EVENT_ID).orEmpty().ifBlank { null }

        emptyText.setIcon(AppIcons.Logs)

        adapter = TransactionHistoryAdapter()
        recyclerView.apply {
            layoutManager = LinearLayoutManager(this@AttendeeTransactionsActivity)
            adapter = this@AttendeeTransactionsActivity.adapter
        }

        presenter.load()
    }

    override fun onDestroy() {
        presenter.detach()
        eventPicker.dismissPopup()
        super.onDestroy()
    }

    override fun showLoading(isLoading: Boolean) {
        val hasRows = adapter.itemCount > 0
        // The picker shows its loading copy on a first load only: a refresh over readable rows keeps
        // the chosen event's title on screen and the field usable.
        if (isLoading && !hasRows) eventPicker.setLoading(true)
        if (!swipeRefresh.isRefreshing) {
            // A refresh over readable rows keeps them on screen; the skeleton is a first-load state.
            skeletonLoading.visibility = if (isLoading && !hasRows) View.VISIBLE else View.GONE
        }
        if (isLoading) {
            errorStrip.visibility = View.GONE
            if (!hasRows) {
                emptyText.visibility = View.GONE
                recyclerView.visibility = View.GONE
            }
        } else {
            swipeRefresh.isRefreshing = false
        }
    }

    override fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun showError(message: String) {
        swipeRefresh.isRefreshing = false
        skeletonLoading.visibility = View.GONE
        // A failed fetch must not leave the field on its loading copy: the presenter skips
        // renderTransactions() when a failed load brings no rows, so the picker is restored here
        // for both branches.
        bindPickerOptions()

        if (adapter.itemCount > 0) {
            // The rows are still readable: keep them and report the failed refresh in the flow,
            // under the filter, with Retry beside the sentence (§5.7 line 642).
            errorStrip.visibility = View.VISIBLE
            emptyText.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        } else {
            showErrorState(message)
        }
    }

    /** A zero-row failure replaces the list with the empty-state slot. */
    private fun showErrorState(message: String) {
        errorStrip.visibility = View.GONE
        summaryCountText.text = getString(R.string.user_transaction_history_0_transactions)
        emptyText.setIcon(AppIcons.Warning)
        emptyText.setContent(
            getString(R.string.user_transaction_history_unable_to_load_transactions),
            message.ifBlank { getString(R.string.ui_attendee_load_error_body) },
        )
        emptyText.setAction(getString(R.string.ui_attendee_retry)) { presenter.load() }
        recyclerView.visibility = View.GONE
        emptyText.visibility = View.VISIBLE
        emptyText.announceForAccessibility(
            getString(R.string.user_transaction_history_unable_to_load_transactions),
        )
    }

    override fun renderTransactions(items: List<TransactionHistoryRow>) {
        swipeRefresh.isRefreshing = false
        skeletonLoading.visibility = View.GONE
        errorStrip.visibility = View.GONE
        allTransactions = items
        updateFilterOptions(items)
        applySelectedFilter()
    }

    private fun updateFilterOptions(items: List<TransactionHistoryRow>) {
        val groupedEvents = items
            .groupBy { it.eventId }
            .entries
            .sortedBy { (_, rows) ->
                rows.firstNotNullOfOrNull { it.eventTitle?.takeIf(String::isNotBlank) }.orEmpty().lowercase()
            }

        val options = mutableListOf(allEventsOption())
        groupedEvents.forEach { (eventId, eventItems) ->
            val title = eventItems.firstNotNullOfOrNull { it.eventTitle?.takeIf(String::isNotBlank) }
                ?: getString(R.string.ui_attendee_untitled_event)
            options += EventPickerOption(id = eventId, title = title)
        }
        eventFilterOptions = options

        // The first load honours the requested event, when its transactions are present; after that
        // the attendee's own choice wins, so a refresh never silently widens back to All events.
        pendingInitialEventId?.let { initial ->
            selectedEventId = initial.takeIf { id -> options.any { it.id == id } }
            pendingInitialEventId = null
        }
        // A refresh can drop the selected event's last row: the filter falls back to All events.
        if (selectedEventId != null && options.none { it.id == selectedEventId }) selectedEventId = null

        bindPickerOptions()
    }

    private fun allEventsOption() =
        EventPickerOption(ALL_EVENTS_OPTION_ID, getString(R.string.user_transaction_history_all_events))

    private fun bindPickerOptions() {
        // Before the first load (and after a failed one) the field still offers All events, so it
        // never sits on its loading copy or on an empty list.
        val options = eventFilterOptions.ifEmpty { listOf(allEventsOption()) }
        // A reload landing under a live list closes it first, so text and popup cannot disagree.
        eventPicker.dismissPopup()
        eventPicker.setOptions(options, selectedEventId ?: ALL_EVENTS_OPTION_ID)
    }

    private fun applySelectedFilter() {
        val filtered = if (selectedEventId.isNullOrBlank()) {
            allTransactions
        } else {
            allTransactions.filter { it.eventId == selectedEventId }
        }

        adapter.submitItems(filtered)
        emptyText.setIcon(AppIcons.Logs)
        emptyText.setAction(null)
        emptyText.setContent(
            getString(R.string.attendee_transactions_no_transactions_found_for_the_select),
            getString(R.string.ui_attendee_no_transactions_body),
        )
        emptyText.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE

        summaryCountText.text = if (filtered.size == 1) "1 transaction" else "${filtered.size} transactions"
    }

    private companion object {
        /**
         * The picker's ids are always Strings, so its "All events" row is a reserved sentinel the
         * activity maps back to `null` (no filter), as it did with the old spinner's null id.
         */
        const val ALL_EVENTS_OPTION_ID = "__all_events__"
    }
}
