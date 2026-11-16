package com.thedavelopers.eventqr.features.attendee

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.BaseAdapter
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thedavelopers.eventqr.R
import com.thedavelopers.eventqr.core.api.NetworkResult
import com.thedavelopers.eventqr.core.api.dto.RegistrationStatus
import com.thedavelopers.eventqr.core.api.dto.TransactionResult
import com.thedavelopers.eventqr.core.api.dto.TransactionType
import com.thedavelopers.eventqr.features.ShadowEncryptedSharedPreferences
import com.thedavelopers.eventqr.features.ShadowMasterKeys
import com.thedavelopers.eventqr.features.registrations.RegistrationsCache
import com.thedavelopers.eventqr.features.registrations.model.dto.RegistrationResponse
import com.thedavelopers.eventqr.features.rewards.model.dto.PointTransactionResponse
import com.thedavelopers.eventqr.features.transactions.model.dto.TransactionResponse
import com.thedavelopers.eventqr.ui.components.EventPickerField
import com.thedavelopers.eventqr.ui.components.EventPickerInput
import com.thedavelopers.eventqr.ui.components.EventPickerOption
import com.thedavelopers.eventqr.ui.components.EventQrEmptyState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.UUID

/**
 * The in-flow error strip and the zero-row empty-state error on transaction history (§5.7 line 642),
 * plus the shared event picker that now owns the filter (plan 5.2): only events with transactions,
 * an `All events` default, and a choice that survives a refresh.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [ShadowMasterKeys::class, ShadowEncryptedSharedPreferences::class])
class AttendeeTransactionsActivityTest {

    private val eventA: UUID = UUID.randomUUID()
    private val eventB: UUID = UUID.randomUUID()

    @Before
    fun setUp() {
        // Another test's cached answer must not decide this class's retry/request assertions.
        RegistrationsCache.clear()
    }

    private fun transaction(
        eventId: UUID = UUID.randomUUID(),
        eventTitle: String? = "DevFest Manila",
    ) = TransactionResponse(
        transactionId = UUID.randomUUID(),
        eventId = eventId,
        eventTitle = eventTitle,
        attendeeUserId = UUID.randomUUID(),
        registrationId = UUID.randomUUID(),
        qrCredentialId = UUID.randomUUID(),
        scanPurposeId = UUID.randomUUID(),
        transactionType = TransactionType.ENTRY,
        transactionResult = TransactionResult.APPROVED,
        pointsDelta = 10,
        scannedAt = Instant.now(),
    )

    /** A manual ledger row: the only trace an event's history has when no scan happened there. */
    private fun pointTransaction(eventId: UUID) = PointTransactionResponse(
        eventId = eventId,
        attendeeUserId = UUID.randomUUID(),
        sourceTransactionId = UUID.randomUUID(),
        pointsChanged = 25,
        occurredAt = Instant.now(),
    )

    private fun registration(eventId: UUID, title: String) = RegistrationResponse(
        registrationId = UUID.randomUUID(),
        eventId = eventId,
        attendeeUserId = UUID.randomUUID(),
        attendeeEmail = "a@test.com",
        attendeeName = "A",
        status = RegistrationStatus.REGISTERED,
        eventTitle = title,
    )

    private class FakeRepo(context: Context) : AttendeeRepository(context) {
        var transactions: NetworkResult<List<TransactionResponse>> = NetworkResult.Success(emptyList())
        var registrations: NetworkResult<List<RegistrationResponse>> = NetworkResult.Success(emptyList())
        var registrationReads = 0
        val points = mutableMapOf<String, NetworkResult<List<PointTransactionResponse>>>()
        /** Holds every ledger read open, so a test decides when the enrichment repaint lands. */
        var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        override suspend fun getMyTransactions() = transactions
        override suspend fun getMyRegistrations(): NetworkResult<List<RegistrationResponse>> {
            registrationReads++
            return registrations
        }
        override suspend fun getMyPointTransactions(eventId: String): NetworkResult<List<PointTransactionResponse>> {
            gate?.await()
            return points[eventId] ?: NetworkResult.Success(emptyList())
        }
    }

    private fun build(repo: FakeRepo, requestedEventId: String? = null): AttendeeTransactionsActivity {
        val intent = Intent(ApplicationProvider.getApplicationContext(), AttendeeTransactionsActivity::class.java)
        if (requestedEventId != null) intent.putExtra(EXTRA_EVENT_ID, requestedEventId)
        val controller = Robolectric.buildActivity(AttendeeTransactionsActivity::class.java, intent)
        controller.get().repositoryOverride = repo
        controller.create().start().resume().visible()
        shadowOf(Looper.getMainLooper()).idle()
        return controller.get()
    }

    private fun AttendeeTransactionsActivity.recycler() =
        findViewById<RecyclerView>(R.id.recyclerTransactions)

    private fun AttendeeTransactionsActivity.empty() =
        findViewById<EventQrEmptyState>(R.id.txtTransactionsEmpty)

    private fun input(activity: AttendeeTransactionsActivity): EventPickerInput =
        activity.findViewById<EventPickerField>(R.id.event_picker_field)
            .findViewById(R.id.eventPickerInput)

    private fun options(activity: AttendeeTransactionsActivity): List<EventPickerOption> {
        val adapter = input(activity).adapter as BaseAdapter
        return (0 until adapter.count).map { adapter.getItem(it) as EventPickerOption }
    }

    private fun shownCount(activity: AttendeeTransactionsActivity): Int =
        (input(activity).adapter as BaseAdapter).count

    /** The row tap a user makes: open the list, then the popup's own item click. */
    private fun choose(activity: AttendeeTransactionsActivity, position: Int) {
        val field = input(activity)
        field.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        field.onItemClickListener.onItemClick(null, null, position, 0)
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Two events with transactions, A two rows and B one, titled so A sorts first. */
    private fun twoEventRepository() = FakeRepo(ApplicationProvider.getApplicationContext()).apply {
        transactions = NetworkResult.Success(
            listOf(
                transaction(eventA, "Day Fair"),
                transaction(eventA, "Day Fair"),
                transaction(eventB, "Night Market"),
            ),
        )
    }

    @Test
    fun refreshFailureWithRows_keepsTheRowsAndShowsTheStrip_thenSuccessHidesIt() {
        val repo = FakeRepo(ApplicationProvider.getApplicationContext()).apply {
            transactions = NetworkResult.Success(listOf(transaction()))
        }
        val activity = build(repo)
        val strip = activity.findViewById<View>(R.id.errorTransactions)
        assertEquals(1, activity.recycler().adapter!!.itemCount)
        assertEquals(View.GONE, strip.visibility)

        activity.showError("offline")
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, activity.recycler().adapter!!.itemCount)
        assertEquals(View.VISIBLE, activity.recycler().visibility)
        assertEquals(View.GONE, activity.empty().visibility)
        assertEquals(View.VISIBLE, strip.visibility)
        assertEquals(
            activity.getString(R.string.user_transaction_history_unable_to_load_transactions),
            activity.findViewById<TextView>(R.id.txtTransactionsError).text.toString(),
        )

        repo.transactions = NetworkResult.Success(listOf(transaction(), transaction()))
        activity.findViewById<View>(R.id.btnTransactionsRetry).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(2, activity.recycler().adapter!!.itemCount)
        assertEquals(View.GONE, strip.visibility)
    }

    @Test
    fun failedRefreshWithNoRows_leavesThePickerOffItsLoadingCopy() {
        val repo = FakeRepo(ApplicationProvider.getApplicationContext()).apply {
            transactions = NetworkResult.Success(listOf(transaction()))
        }
        val activity = build(repo)
        assertEquals(1, activity.recycler().adapter!!.itemCount)

        // The presenter's real order: loading on, no render (the failed load brought no rows), error.
        activity.showLoading(true)
        activity.showError("offline")
        shadowOf(Looper.getMainLooper()).idle()

        // The rows on screen and the strip are unchanged; the picker is back at rest with its options.
        assertEquals(1, activity.recycler().adapter!!.itemCount)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.errorTransactions).visibility)
        assertTrue(input(activity).isEnabled)
        assertEquals(
            activity.getString(R.string.user_transaction_history_all_events),
            input(activity).hint.toString(),
        )
        assertEquals(2, options(activity).size)
        assertEquals("__all_events__", options(activity).first().id)
    }

    @Test
    fun firstLoadFailureWithNoRows_showsTheFixedTitleInTheEmptyStateWithRetry_thenSuccessRenders() {
        val repo = FakeRepo(ApplicationProvider.getApplicationContext()).apply {
            transactions = NetworkResult.Error("offline")
        }
        val activity = build(repo)

        assertEquals(View.VISIBLE, activity.empty().visibility)
        assertEquals(
            activity.getString(R.string.user_transaction_history_unable_to_load_transactions),
            activity.empty().text.toString(),
        )
        assertEquals("offline", activity.empty().subtext.toString())
        val retry = activity.empty().findViewById<TextView>(R.id.emptyAction)
        assertEquals(View.VISIBLE, retry.visibility)
        assertEquals(activity.getString(R.string.ui_attendee_retry), retry.text.toString())
        assertEquals(View.GONE, activity.recycler().visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.errorTransactions).visibility)

        repo.transactions = NetworkResult.Success(listOf(transaction()))
        retry.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, activity.recycler().adapter!!.itemCount)
        assertEquals(View.VISIBLE, activity.recycler().visibility)
        assertEquals(View.GONE, activity.empty().visibility)
    }

    @Test
    fun thePickerListsOnlyTheEventsWithTransactions_plusAllEvents() {
        val activity = build(twoEventRepository())

        val loaded = options(activity)
        assertEquals(
            listOf("__all_events__", eventA.toString(), eventB.toString()),
            loaded.map { it.id },
        )
        assertEquals(
            listOf(
                activity.getString(R.string.user_transaction_history_all_events),
                "Day Fair",
                "Night Market",
            ),
            loaded.map { it.title },
        )
        // An untouched field can legitimately sit in its open-search state (empty text, hint naming
        // the choice), so the resting selection is asserted through the hint, not the text.
        assertEquals(
            activity.getString(R.string.user_transaction_history_all_events),
            input(activity).hint.toString(),
        )
        assertEquals(3, activity.recycler().adapter!!.itemCount)
    }

    @Test
    fun typingNarrowsThePickerToMatchingTitles() {
        val activity = build(twoEventRepository())
        val field = input(activity)

        field.setText("night")
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, shownCount(activity))
        val match = (field.adapter as BaseAdapter).getItem(0) as EventPickerOption
        assertEquals(eventB.toString(), match.id)

        field.setText("")
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(3, shownCount(activity))
    }

    @Test
    fun choosingAnEvent_filtersTheRowsAndTheCount() {
        val activity = build(twoEventRepository())

        choose(activity, 2)

        assertEquals("Night Market", input(activity).text.toString())
        assertEquals(1, activity.recycler().adapter!!.itemCount)
        assertEquals(
            "1 transaction",
            activity.findViewById<TextView>(R.id.txtHistoryTransactionCount).text.toString(),
        )

        choose(activity, 1)

        assertEquals("Day Fair", input(activity).text.toString())
        assertEquals(2, activity.recycler().adapter!!.itemCount)
        assertEquals(
            "2 transactions",
            activity.findViewById<TextView>(R.id.txtHistoryTransactionCount).text.toString(),
        )

        // All events brings every row back.
        choose(activity, 0)

        assertEquals(activity.getString(R.string.user_transaction_history_all_events), input(activity).text.toString())
        assertEquals(3, activity.recycler().adapter!!.itemCount)
        assertEquals(
            "3 transactions",
            activity.findViewById<TextView>(R.id.txtHistoryTransactionCount).text.toString(),
        )
    }

    @Test
    fun aRefreshKeepsTheChosenEvent() {
        val repo = twoEventRepository()
        val activity = build(repo)

        choose(activity, 2)
        assertEquals(1, activity.recycler().adapter!!.itemCount)

        repo.transactions = NetworkResult.Success(
            listOf(
                transaction(eventA, "Day Fair"),
                transaction(eventB, "Night Market"),
                transaction(eventB, "Night Market"),
            ),
        )
        activity.findViewById<View>(R.id.btnTransactionsRetry).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("Night Market", input(activity).text.toString())
        assertEquals(2, activity.recycler().adapter!!.itemCount)
    }

    @Test
    fun aRefreshThatDropsTheChosenEvent_fallsBackToAllEvents() {
        val repo = twoEventRepository()
        val activity = build(repo)

        choose(activity, 2)
        assertEquals("Night Market", input(activity).text.toString())
        assertEquals(1, activity.recycler().adapter!!.itemCount)

        // The chosen event has no rows left in the new payload: the filter widens back to All events.
        repo.transactions = NetworkResult.Success(
            listOf(
                transaction(eventA, "Day Fair"),
                transaction(eventA, "Day Fair"),
            ),
        )
        activity.findViewById<View>(R.id.btnTransactionsRetry).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            activity.getString(R.string.user_transaction_history_all_events),
            input(activity).hint.toString(),
        )
        assertEquals(2, activity.recycler().adapter!!.itemCount)
        assertEquals(listOf("__all_events__", eventA.toString()), options(activity).map { it.id })
    }

    @Test
    fun theRequestedEventIsPreselected_onItsFirstLoad() {
        val activity = build(twoEventRepository(), requestedEventId = eventB.toString())

        assertEquals("Night Market", input(activity).hint.toString())
        assertEquals(1, activity.recycler().adapter!!.itemCount)

        // A requested event with no transactions is not an option: the filter falls back to All.
        val other = build(twoEventRepository(), requestedEventId = UUID.randomUUID().toString())

        assertEquals(
            other.getString(R.string.user_transaction_history_all_events),
            input(other).hint.toString(),
        )
        assertEquals(3, other.recycler().adapter!!.itemCount)
    }

    @Test
    fun theRequestedEventIsPreselectedEvenWhenOnlyItsLedgerRowArrivesLate() {
        val repo = FakeRepo(ApplicationProvider.getApplicationContext()).apply {
            // eventB is registered with no scan, so its only row is the per-event ledger row that a
            // deferred read brings: the first paint cannot offer it yet.
            registrations = NetworkResult.Success(listOf(registration(eventB, "Night Market")))
            transactions = NetworkResult.Success(listOf(transaction(eventA, "Day Fair")))
            points[eventB.toString()] = NetworkResult.Success(listOf(pointTransaction(eventB)))
        }
        val activity = build(repo, requestedEventId = eventB.toString())

        // Same visible outcome as one all-at-once render: eventB chosen, its one row shown.
        assertEquals("Night Market", input(activity).hint.toString())
        assertEquals(1, activity.recycler().adapter!!.itemCount)
    }

    @Test
    fun ledgerOnlyHistory_keepsTheSkeletonInsteadOfFlashingTheEmptyState() {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val repo = FakeRepo(ApplicationProvider.getApplicationContext()).apply {
            // Registered, never scanned: the only row is the ledger row the fan-out still owes.
            registrations = NetworkResult.Success(listOf(registration(eventB, "Night Market")))
            transactions = NetworkResult.Success(emptyList())
            points[eventB.toString()] = NetworkResult.Success(listOf(pointTransaction(eventB)))
            this.gate = gate
        }
        val activity = build(repo)

        // First-load state: no empty list and no count printed over rows that are still coming.
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.skeletonLoading).visibility)
        assertEquals(View.GONE, activity.empty().visibility)
        assertEquals(View.GONE, activity.recycler().visibility)

        gate.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(View.GONE, activity.findViewById<View>(R.id.skeletonLoading).visibility)
        assertEquals(1, activity.recycler().adapter!!.itemCount)
        assertEquals(View.VISIBLE, activity.recycler().visibility)
        assertEquals(View.GONE, activity.empty().visibility)
        assertEquals(
            "1 transaction",
            activity.findViewById<TextView>(R.id.txtHistoryTransactionCount).text.toString(),
        )
    }

    @Test
    fun anEnrichmentRepaintLeavesTheFilterTheAttendeeIsUsingAlone() {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val repo = twoEventRepository().apply { this.gate = gate }
        val activity = build(repo)
        val field = input(activity)
        assertEquals(3, activity.recycler().adapter!!.itemCount)

        // The attendee is mid-filter while the ledger read is still in flight.
        field.setText("night")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, shownCount(activity))

        // The fan-out lands on the same set of events: the rows and the count update, the live
        // filter (and the list under it) is left untouched.
        repo.points[eventA.toString()] = NetworkResult.Success(listOf(pointTransaction(eventA)))
        gate.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("night", field.text.toString())
        assertEquals(1, shownCount(activity))
        assertEquals(4, activity.recycler().adapter!!.itemCount)
        assertEquals(
            "4 transactions",
            activity.findViewById<TextView>(R.id.txtHistoryTransactionCount).text.toString(),
        )
    }

    @Test
    fun zeroTransactions_keepsThePickerUsableOnAllEvents() {
        val repo = FakeRepo(ApplicationProvider.getApplicationContext())
        val activity = build(repo)

        val loaded = options(activity)
        assertEquals(1, loaded.size)
        assertEquals("__all_events__", loaded.single().id)
        assertEquals(activity.getString(R.string.user_transaction_history_all_events), input(activity).hint.toString())
        assertTrue(input(activity).isEnabled)
        assertEquals(View.VISIBLE, activity.empty().visibility)
    }

    @Test
    fun theRetryButtonForcesTheRegistrationsRead() {
        val repo = FakeRepo(ApplicationProvider.getApplicationContext()).apply {
            transactions = NetworkResult.Success(listOf(transaction()))
        }
        val activity = build(repo)
        assertEquals(1, repo.registrationReads)

        activity.findViewById<View>(R.id.btnTransactionsRetry).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("an explicit retry asks the network for both legs", 2, repo.registrationReads)
        assertEquals(1, activity.recycler().adapter!!.itemCount)
    }

    @Test
    fun failedFirstLoad_leavesThePickerOffItsLoadingCopy() {
        val repo = FakeRepo(ApplicationProvider.getApplicationContext()).apply {
            transactions = NetworkResult.Error("offline")
        }
        val activity = build(repo)

        assertEquals(activity.getString(R.string.user_transaction_history_all_events), input(activity).hint.toString())
        assertTrue(input(activity).isEnabled)
        assertEquals(1, options(activity).size)
    }
}
