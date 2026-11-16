package com.thedavelopers.eventqr.features.attendee

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thedavelopers.eventqr.core.api.NetworkResult
import com.thedavelopers.eventqr.core.api.dto.AccountRole
import com.thedavelopers.eventqr.core.api.dto.TransactionResult
import com.thedavelopers.eventqr.core.api.dto.TransactionType
import com.thedavelopers.eventqr.core.cache.AttendeeSnapshots
import com.thedavelopers.eventqr.core.cache.LastKnownCache
import com.thedavelopers.eventqr.core.session.SessionManager
import com.thedavelopers.eventqr.features.ShadowEncryptedSharedPreferences
import com.thedavelopers.eventqr.features.ShadowMasterKeys
import com.thedavelopers.eventqr.features.auth.model.dto.LoginResponse
import com.thedavelopers.eventqr.features.registrations.RegistrationsCache
import com.thedavelopers.eventqr.features.registrations.model.dto.RegistrationResponse
import com.thedavelopers.eventqr.features.rewards.model.dto.PointTransactionResponse
import com.thedavelopers.eventqr.features.transactions.TransactionHistoryRow
import com.thedavelopers.eventqr.features.transactions.model.dto.TransactionResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.UUID

/**
 * The persisted transaction history: a mixed Scan/Point list survives the round trip through the
 * cache unchanged, the snapshot paints before the network answers, and only a complete answer is
 * ever written back.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [ShadowMasterKeys::class, ShadowEncryptedSharedPreferences::class])
class TransactionHistorySnapshotTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val userId = UUID.fromString("aaaaaaaa-4444-4444-4444-444444444444")
    private val eventId = UUID.fromString("bbbbbbbb-4444-4444-4444-444444444444")

    private lateinit var session: SessionManager
    private lateinit var cache: LastKnownCache

    @Before
    fun setUp() {
        RegistrationsCache.clear()
        LastKnownCache.resetForTests()
        session = SessionManager(context).apply { clearSession() }
        session.saveLoginResponse(
            LoginResponse(
                accessToken = "access",
                userId = userId,
                email = "a@test.com",
                fullName = "Ada Lovelace",
                role = AccountRole.ATTENDEE,
                refreshToken = "refresh",
            ),
        )
        cache = LastKnownCache(context)
        cache.clearAll()
    }

    @After
    fun tearDown() {
        cache.clearAll()
        RegistrationsCache.clear()
        session.clearSession()
    }

    private class RecordingView : TransactionHistoryContract.View {
        val rendered = mutableListOf<List<TransactionHistoryRow>>()
        val errors = mutableListOf<String>()
        val loading = mutableListOf<Boolean>()

        override fun showLoading(isLoading: Boolean) {
            loading += isLoading
        }

        override fun showError(message: String) {
            errors += message
        }

        override fun renderTransactions(items: List<TransactionHistoryRow>) {
            rendered += items
        }

        override fun showMessage(message: String) = Unit
    }

    private fun scan(points: Int, at: String = "2026-11-01T09:00:00Z") = TransactionResponse(
        transactionId = UUID.randomUUID(),
        eventId = eventId,
        eventTitle = "DevFest",
        attendeeUserId = userId,
        registrationId = UUID.randomUUID(),
        qrCredentialId = UUID.randomUUID(),
        scanPurposeId = UUID.randomUUID(),
        transactionType = TransactionType.ENTRY,
        transactionResult = TransactionResult.APPROVED,
        pointsDelta = points,
        scannedAt = Instant.parse(at),
    )

    private fun point(at: String = "2026-11-02T09:00:00Z") = TransactionHistoryRow.Point(
        eventId = eventId.toString(),
        eventTitle = "DevFest",
        occurredAt = Instant.parse(at),
        pointsDelta = -5,
        reason = "Manual deduction",
    )

    private fun scanRow(points: Int = 10, at: String = "2026-11-01T09:00:00Z") =
        TransactionHistoryRow.Scan(scan(points, at))

    /** The transactions read stays suspended until the test releases it. */
    private inner class GatedRepo(private val scans: NetworkResult<List<TransactionResponse>>) :
        AttendeeRepository(context) {
        var calls = 0
        val gate = CompletableDeferred<Unit>()

        override suspend fun getMyTransactions(): NetworkResult<List<TransactionResponse>> {
            calls++
            gate.await()
            return scans
        }

        override suspend fun getMyRegistrations(): NetworkResult<List<RegistrationResponse>> =
            NetworkResult.Success(emptyList())

        override suspend fun getMyPointTransactions(eventId: String): NetworkResult<List<PointTransactionResponse>> =
            NetworkResult.Success(emptyList())
    }

    private fun seedSnapshot(rows: List<TransactionHistoryRow>) = runBlocking {
        cache.write(CachedTransactionRow.KEY, rows.map(CachedTransactionRow::of))
    }

    private fun presenter(view: RecordingView, repo: AttendeeRepository) =
        TransactionHistoryPresenter(view, repo, session, cache)

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            // The presenter runs on the main looper; the snapshot read hops to an IO thread.
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    @Test
    fun aMixedScanAndPointListRoundTripsThroughTheCacheUnchanged() = runBlocking {
        val rows: List<TransactionHistoryRow> = listOf(scanRow(), point())

        cache.write(CachedTransactionRow.KEY, rows.map(CachedTransactionRow::of))
        val read = cache.read<List<CachedTransactionRow>>(
            CachedTransactionRow.KEY,
            CachedTransactionRow.LIST_TYPE,
            AttendeeSnapshots.MAX_AGE_MS,
        )?.data?.mapNotNull { it.toRow() }

        assertEquals(rows, read)
    }

    @Test
    fun theSnapshotPaintsBeforeTheApiAnswers_withoutASkeleton() {
        seedSnapshot(listOf(scanRow()))
        val view = RecordingView()
        val repo = GatedRepo(NetworkResult.Success(listOf(scan(25))))

        presenter(view, repo).load()
        awaitUntil { view.rendered.isNotEmpty() }

        assertEquals(1, view.rendered.single().size)
        assertEquals(10, view.rendered.single().single().pointsDelta)
        // The paint stops the skeleton instead of raising one over readable rows.
        assertEquals(listOf(false), view.loading)
        // The refresh runs underneath the painted rows, exactly once.
        assertEquals(1, repo.calls)
    }

    @Test
    fun theFreshAnswerReplacesTheSnapshot() {
        seedSnapshot(listOf(scanRow(points = 10), point()))
        val view = RecordingView()
        val repo = GatedRepo(NetworkResult.Success(listOf(scan(25))))

        presenter(view, repo).load()
        awaitUntil { view.rendered.isNotEmpty() }
        assertEquals(2, view.rendered.last().size)

        repo.gate.complete(Unit)
        awaitUntil { view.rendered.last().size == 1 }

        assertEquals(25, view.rendered.last().single().pointsDelta)
    }

    @Test
    fun aFailedRefreshKeepsThePaintedSnapshot() {
        seedSnapshot(listOf(scanRow()))
        val view = RecordingView()
        val repo = GatedRepo(NetworkResult.Error("offline"))
        repo.gate.complete(Unit)

        presenter(view, repo).load()
        awaitUntil { view.errors.isNotEmpty() }

        // The snapshot rows are still on screen; the failure only rides the in-flow strip.
        assertEquals(10, view.rendered.single().single().pointsDelta)
        assertEquals(listOf("offline"), view.errors)
    }

    @Test
    fun aFailedLoadWithoutASnapshotShowsTheErrorState() {
        val view = RecordingView()
        val repo = GatedRepo(NetworkResult.Error("offline"))
        repo.gate.complete(Unit)

        presenter(view, repo).load()
        awaitUntil { view.errors.isNotEmpty() }

        assertEquals(emptyList<List<TransactionHistoryRow>>(), view.rendered)
        assertEquals(listOf(true, false), view.loading)
    }

    @Test
    fun pullToRefreshBypassesTheSnapshot() {
        seedSnapshot(listOf(scanRow()))
        val view = RecordingView()
        val repo = GatedRepo(NetworkResult.Success(listOf(scan(25))))

        presenter(view, repo).load(forceNetwork = true)
        awaitUntil { repo.calls == 1 }

        assertEquals(emptyList<List<TransactionHistoryRow>>(), view.rendered)
        assertEquals(listOf(true), view.loading)
    }

    @Test
    fun aCompleteLoadWritesTheSnapshot_andARedemptionDropsIt() {
        val view = RecordingView()
        val repo = GatedRepo(NetworkResult.Success(listOf(scan(25))))
        repo.gate.complete(Unit)

        presenter(view, repo).load()
        awaitUntil { view.rendered.isNotEmpty() }
        awaitUntil { persistedRows() != null }

        assertEquals(25, persistedRows()?.single()?.transaction?.pointsDelta)

        runBlocking { AttendeeRepository(context).invalidateRewardSnapshots() }

        assertNull(persistedRows())
    }

    private fun persistedRows(): List<CachedTransactionRow>? = runBlocking {
        cache.read<List<CachedTransactionRow>>(
            CachedTransactionRow.KEY,
            CachedTransactionRow.LIST_TYPE,
            AttendeeSnapshots.MAX_AGE_MS,
        )?.data
    }
}
