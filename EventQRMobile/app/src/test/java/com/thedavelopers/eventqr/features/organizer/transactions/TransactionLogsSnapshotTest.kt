package com.thedavelopers.eventqr.features.organizer.transactions

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thedavelopers.eventqr.R
import com.thedavelopers.eventqr.core.api.NetworkResult
import com.thedavelopers.eventqr.core.api.dto.AccountRole
import com.thedavelopers.eventqr.core.api.dto.TransactionResult
import com.thedavelopers.eventqr.core.api.dto.TransactionType
import com.thedavelopers.eventqr.core.cache.LastKnownCache
import com.thedavelopers.eventqr.core.cache.OrganizerSnapshots
import com.thedavelopers.eventqr.core.session.SessionManager
import com.thedavelopers.eventqr.features.ShadowEncryptedSharedPreferences
import com.thedavelopers.eventqr.features.ShadowMasterKeys
import com.thedavelopers.eventqr.features.auth.model.dto.LoginResponse
import com.thedavelopers.eventqr.features.organizer.EXTRA_EVENT_ID
import com.thedavelopers.eventqr.features.organizer.OrganizerEventsCache
import com.thedavelopers.eventqr.features.organizer.OrganizerMvpDataSource
import com.thedavelopers.eventqr.features.organizer.OrganizerMvpEvent
import com.thedavelopers.eventqr.features.organizer.OrganizerMvpLoad
import com.thedavelopers.eventqr.features.organizer.OrganizerMvpTransaction
import com.thedavelopers.eventqr.features.organizer.OrganizerRepository
import com.thedavelopers.eventqr.features.organizer.allTexts
import com.thedavelopers.eventqr.features.organizer.model.dto.OrganizerTransactionDto
import com.thedavelopers.eventqr.features.organizer.testOrganizerEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * The transaction log against the persisted `organizer/logs/{eventId}` rows: the rows paint before
 * the network replies, the fresh answer replaces them, a failure keeps them and a force bypasses the
 * snapshot. The screen has no pull-to-refresh, so a forced load is the bypass handle.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [ShadowMasterKeys::class, ShadowEncryptedSharedPreferences::class])
class TransactionLogsSnapshotTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val userId = UUID.fromString("bbbbbbbb-2222-2222-2222-222222222222")

    private lateinit var session: SessionManager
    private lateinit var cache: LastKnownCache
    private lateinit var event: OrganizerMvpEvent

    @Before
    fun setUp() {
        OrganizerEventsCache.shared.clear()
        session = SessionManager(context).apply { clearSession() }
        session.saveLoginResponse(
            LoginResponse(
                accessToken = "access",
                userId = userId,
                email = "org@test.com",
                fullName = "Org Owner",
                role = AccountRole.ORGANIZER,
                refreshToken = "refresh",
            ),
        )
        cache = LastKnownCache(context)
        cache.clearAll()
        event = testOrganizerEvent("Cached fest")
    }

    @After
    fun tearDown() {
        cache.clearAll()
        OrganizerEventsCache.shared.clear()
        session.clearSession()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            idle()
            Thread.sleep(10)
        }
        idle()
    }

    private fun dto(name: String) = OrganizerTransactionDto(
        transactionId = UUID.randomUUID(),
        eventId = UUID.fromString(event.id),
        eventTitle = event.title,
        attendeeName = name,
        transactionType = TransactionType.ENTRY,
        resultStatus = TransactionResult.APPROVED,
    )

    private fun transaction(name: String) = OrganizerMvpTransaction(
        id = UUID.randomUUID().toString(),
        eventId = event.id,
        eventTitle = event.title,
        attendeeId = UUID.randomUUID().toString(),
        attendeeName = name,
        qrId = "ref",
        staffId = "-",
        staffName = "-",
        scanPurpose = "Entry",
        type = "Entry",
        timestamp = "-",
        status = "Approved",
        message = "-",
        reason = "-",
        deviceSource = "-",
        pointsDelta = 0,
        relatedItem = "-",
    )

    private fun seed(names: List<String>) = runBlocking {
        cache.write(OrganizerSnapshots.logs(event.id), names.map(::transaction))
    }

    private inner class GatedRepository : OrganizerRepository(
        context,
        eventsCache = OrganizerEventsCache(),
        lastKnownCache = cache,
    ) {
        var result: NetworkResult<List<OrganizerTransactionDto>> = NetworkResult.Error("unset")
        val gate = CompletableDeferred<Unit>()
        var calls = 0

        override suspend fun loadApprovedOrganizerEvents(): OrganizerMvpLoad<List<OrganizerMvpEvent>> =
            OrganizerMvpLoad(listOf(event), OrganizerMvpDataSource.BACKEND)

        override suspend fun fetchOrganizerTransactions(eventId: String): NetworkResult<List<OrganizerTransactionDto>> {
            calls++
            gate.await()
            return result
        }
    }

    private fun buildActivity(repository: OrganizerRepository): TransactionLogsActivity {
        val intent = Intent(context, TransactionLogsActivity::class.java).putExtra(EXTRA_EVENT_ID, event.id)
        val controller = Robolectric.buildActivity(TransactionLogsActivity::class.java, intent)
        val activity = controller.get().apply {
            installRepositoryFactory { repository }
            cacheOverride = cache
        }
        controller.create()
        return activity
    }

    private fun TransactionLogsActivity.listRows(): Int =
        findViewById<LinearLayout>(R.id.tlg_list)?.childCount ?: 0

    private fun TransactionLogsActivity.texts(): List<String> = window.decorView.allTexts()

    @Test
    fun theSnapshotPaintsTheRowsBeforeTheApiAnswers() {
        seed(listOf("Cached Ana"))
        val repository = GatedRepository()
        val activity = buildActivity(repository)

        awaitUntil { activity.texts().contains("Cached Ana") }

        assertEquals(1, repository.calls)
        assertTrue(activity.texts().contains("Cached Ana"))
        assertFalse(
            "the loading card was drawn over the painted rows",
            activity.texts().contains(activity.getString(R.string.transaction_logs_loading)),
        )
    }

    @Test
    fun theFreshAnswerReplacesTheSnapshot() {
        seed(listOf("Cached Ana"))
        val repository = GatedRepository().apply {
            result = NetworkResult.Success(listOf(dto("Fresh one"), dto("Fresh two")))
        }
        val activity = buildActivity(repository)
        awaitUntil { activity.texts().contains("Cached Ana") }

        repository.gate.complete(Unit)
        awaitUntil { activity.listRows() == 2 }

        assertEquals(2, activity.listRows())
        assertFalse(activity.texts().contains("Cached Ana"))
    }

    @Test
    fun aFailedRefreshKeepsThePaintedRows() {
        seed(listOf("Cached Ana"))
        val repository = GatedRepository().apply { result = NetworkResult.Error("offline") }
        val activity = buildActivity(repository)
        awaitUntil { activity.texts().contains("Cached Ana") }

        repository.gate.complete(Unit)
        idle()
        idle()

        assertEquals(1, activity.listRows())
        assertTrue(activity.texts().contains("Cached Ana"))
    }

    @Test
    fun aFailedLoadWithoutASnapshotShowsTheErrorState() {
        val repository = GatedRepository().apply {
            result = NetworkResult.Error("offline")
            gate.complete(Unit)
        }
        val activity = buildActivity(repository)

        awaitUntil { activity.texts().contains(activity.getString(R.string.organizer_dashboard_events_load_failed)) }

        assertTrue(
            activity.texts().contains(activity.getString(R.string.organizer_dashboard_events_load_failed)),
        )
        assertTrue(activity.texts().contains("offline"))
    }

    @Test
    fun aForcedLoadBypassesTheSnapshot() {
        val repository = GatedRepository()
        val activity = buildActivity(repository)
        awaitUntil { repository.calls == 1 }

        seed(listOf("Cached Ana"))
        activity.loadLogs(forceNetwork = true)
        idle()

        assertEquals(2, repository.calls)
        assertFalse("a forced load never paints the persisted rows", activity.texts().contains("Cached Ana"))
    }
}
