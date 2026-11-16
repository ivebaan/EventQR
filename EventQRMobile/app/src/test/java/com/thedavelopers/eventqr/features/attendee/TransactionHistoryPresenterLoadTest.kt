package com.thedavelopers.eventqr.features.attendee

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thedavelopers.eventqr.core.api.NetworkResult
import com.thedavelopers.eventqr.core.api.dto.AccountRole
import com.thedavelopers.eventqr.core.api.dto.RegistrationStatus
import com.thedavelopers.eventqr.core.api.dto.RedemptionStatus
import com.thedavelopers.eventqr.core.api.dto.TransactionResult
import com.thedavelopers.eventqr.core.api.dto.TransactionType
import com.thedavelopers.eventqr.core.session.SessionManager
import com.thedavelopers.eventqr.features.ShadowEncryptedSharedPreferences
import com.thedavelopers.eventqr.features.ShadowMasterKeys
import com.thedavelopers.eventqr.features.auth.model.dto.LoginResponse
import com.thedavelopers.eventqr.features.registrations.RegistrationsCache
import com.thedavelopers.eventqr.features.registrations.model.dto.RegistrationResponse
import com.thedavelopers.eventqr.features.rewards.model.dto.PointTransactionResponse
import com.thedavelopers.eventqr.features.rewards.model.dto.RewardRedemptionResponse
import com.thedavelopers.eventqr.features.transactions.TransactionHistoryRow
import com.thedavelopers.eventqr.features.transactions.model.dto.TransactionResponse
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.UUID

/**
 * The two global reads paint the list; the per-event fan-out is enrichment that lands after that
 * paint. One failed per-event read keeps the rows that loaded and never reaches the error strip;
 * only a failure with no rows replaces them.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [ShadowMasterKeys::class, ShadowEncryptedSharedPreferences::class])
class TransactionHistoryPresenterLoadTest {

    private val eventA = UUID.randomUUID()
    private val eventB = UUID.randomUUID()
    private val userId = UUID.randomUUID()
    private lateinit var session: SessionManager

    @org.junit.Before
    fun setUp() {
        RegistrationsCache.clear()
        session = SessionManager(ApplicationProvider.getApplicationContext())
        session.clearSession()
        // A per-test id, so a leftover entry from another test class can never answer for this one.
        session.saveLoginResponse(
            LoginResponse(
                accessToken = "access",
                userId = userId,
                email = "a@test.com",
                fullName = "A",
                role = AccountRole.ATTENDEE,
                refreshToken = "refresh",
            ),
        )
    }

    @After
    fun tearDown() {
        RegistrationsCache.clear()
        session.clearSession()
    }

    private fun registration(eventId: UUID) = RegistrationResponse(
        registrationId = UUID.randomUUID(),
        eventId = eventId,
        attendeeUserId = UUID.randomUUID(),
        attendeeEmail = "a@test.com",
        attendeeName = "A",
        status = RegistrationStatus.REGISTERED,
        eventTitle = "Event ${eventId.toString().take(4)}",
    )

    private fun scan(
        eventId: UUID,
        type: TransactionType = TransactionType.ENTRY,
        points: Int = 10,
    ) = TransactionResponse(
        transactionId = UUID.randomUUID(),
        eventId = eventId,
        eventTitle = "Event ${eventId.toString().take(4)}",
        attendeeUserId = UUID.randomUUID(),
        registrationId = UUID.randomUUID(),
        qrCredentialId = UUID.randomUUID(),
        scanPurposeId = UUID.randomUUID(),
        transactionType = type,
        transactionResult = TransactionResult.APPROVED,
        pointsDelta = points,
        scannedAt = Instant.parse("2026-11-01T09:00:00Z"),
    )

    private fun point(
        eventId: UUID,
        source: UUID = UUID.randomUUID(),
        points: Int = -5,
        reason: String = "Manual point deduction",
    ) = PointTransactionResponse(
        eventId = eventId,
        attendeeUserId = UUID.randomUUID(),
        sourceTransactionId = source,
        pointsChanged = points,
        occurredAt = Instant.parse("2026-11-02T09:00:00Z"),
        reason = reason,
    )

    private fun redemption(eventId: UUID, redemptionId: UUID, scanLogId: UUID? = null) = RewardRedemptionResponse(
        redemptionId = redemptionId,
        eventId = eventId,
        attendeeUserId = UUID.randomUUID(),
        rewardId = UUID.randomUUID(),
        pointsSpent = 40,
        status = RedemptionStatus.REDEEMED,
        redemptionScanLogId = scanLogId,
    )

    @Test
    fun oneFailedEventRead_keepsTheLoadedRowsWithoutAnErrorStrip() {
        val repo = FakeRepo().apply {
            registrations = NetworkResult.Success(listOf(registration(eventA), registration(eventB)))
            transactions = NetworkResult.Success(listOf(scan(eventA)))
            points[eventA.toString()] = NetworkResult.Success(listOf(point(eventA)))
            points[eventB.toString()] = NetworkResult.Error("offline")
        }
        val view = RecordingView()

        presenter(view, repo).load()
        shadowOf(Looper.getMainLooper()).idle()

        // eventA: its scan row plus the manual deduction; eventB's failed point read is logged, not shown.
        assertEquals(2, view.rendered.last().size)
        assertEquals(1, view.rendered.last().count { it is TransactionHistoryRow.Point })
        assertEquals(emptyList<String>(), view.errors)
        assertEquals(listOf(true, false), view.loading)
    }

    @Test
    fun failedRegistrationsRead_stillRendersTheScans() {
        val repo = FakeRepo().apply {
            registrations = NetworkResult.Error("offline")
            transactions = NetworkResult.Success(listOf(scan(eventA)))
            points[eventA.toString()] = NetworkResult.Success(emptyList())
        }
        val view = RecordingView()

        presenter(view, repo).load()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, view.rendered.single().size)
        assertEquals(listOf("offline"), view.errors)
    }

    @Test
    fun everyReadFailed_showsOnlyTheFullError() {
        val repo = FakeRepo().apply {
            registrations = NetworkResult.Error("offline")
            transactions = NetworkResult.Error("offline")
        }
        val view = RecordingView()

        presenter(view, repo).load()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(emptyList<List<TransactionHistoryRow>>(), view.rendered)
        assertEquals(listOf("offline"), view.errors)
        assertEquals(listOf(true, false), view.loading)
    }

    @Test
    fun claimedRedemption_ledgerRowIsDroppedByItsRedemptionId() {
        val redemptionId = UUID.randomUUID()
        val repo = FakeRepo().apply {
            registrations = NetworkResult.Success(listOf(registration(eventA)))
            transactions = NetworkResult.Success(
                listOf(
                    scan(eventA, type = TransactionType.REWARD_REDEMPTION_SCAN, points = 0),
                    scan(eventA, type = TransactionType.REWARD_REDEMPTION, points = -40),
                ),
            )
            points[eventA.toString()] = NetworkResult.Success(
                listOf(point(eventA, source = redemptionId, points = -40, reason = "Reward redemption")),
            )
            claims[eventA.toString()] = NetworkResult.Success(
                listOf(redemption(eventA, redemptionId, scanLogId = UUID.randomUUID())),
            )
        }
        val view = RecordingView()

        presenter(view, repo).load()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(2, view.rendered.single().size)
        assertEquals(0, view.rendered.single().count { it is TransactionHistoryRow.Point })
        assertEquals(emptyList<String>(), view.errors)
    }

    @Test
    fun failedClaimRead_keepsTheLedgerRowWithoutAnErrorStrip() {
        val repo = FakeRepo().apply {
            registrations = NetworkResult.Success(listOf(registration(eventA)))
            transactions = NetworkResult.Success(
                listOf(
                    scan(eventA, type = TransactionType.REWARD_REDEMPTION_SCAN, points = 0),
                    scan(eventA, type = TransactionType.REWARD_REDEMPTION, points = -40),
                ),
            )
            points[eventA.toString()] = NetworkResult.Success(
                listOf(point(eventA, points = -40, reason = "Reward redemption")),
            )
            claims[eventA.toString()] = NetworkResult.Error("offline")
        }
        val view = RecordingView()

        presenter(view, repo).load()
        shadowOf(Looper.getMainLooper()).idle()

        // The link is unknown, so the ledger row is kept rather than guessed away.
        assertEquals(1, view.rendered.last().count { it is TransactionHistoryRow.Point })
        assertEquals(emptyList<String>(), view.errors)
    }

    @Test
    fun rowsAndLoadingSettleBeforeThePerEventEnrichmentLands() {
        val gate = CompletableDeferred<Unit>()
        val repo = FakeRepo().apply {
            registrations = NetworkResult.Success(listOf(registration(eventA)))
            transactions = NetworkResult.Success(listOf(scan(eventA)))
            points[eventA.toString()] = NetworkResult.Success(listOf(point(eventA)))
            this.gate = gate
        }
        val view = RecordingView()

        presenter(view, repo).load()
        shadowOf(Looper.getMainLooper()).idle()

        // The scans list is on screen and the skeleton is off while the per-event read still hangs.
        assertEquals(1, view.rendered.size)
        assertEquals(1, view.rendered.single().size)
        assertEquals(listOf(true, false), view.loading)

        gate.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()

        // The same list the all-at-once load produced: newest first, the ledger row included.
        assertEquals(2, view.rendered.size)
        assertEquals(2, view.rendered.last().size)
        assertEquals(1, view.rendered.last().count { it is TransactionHistoryRow.Point })
        assertFalse(view.rendered.last().first() is TransactionHistoryRow.Scan)
    }

    @Test
    fun anEmptyBaseOverEventsThatOweLedgerRowsKeepsTheSkeletonUntilTheyLand() {
        val gate = CompletableDeferred<Unit>()
        val repo = FakeRepo().apply {
            registrations = NetworkResult.Success(listOf(registration(eventA)))
            // No scan rows at all: every row this attendee has is a manual ledger row.
            transactions = NetworkResult.Success(emptyList())
            points[eventA.toString()] = NetworkResult.Success(listOf(point(eventA)))
            this.gate = gate
        }
        val view = RecordingView()

        presenter(view, repo).load()
        shadowOf(Looper.getMainLooper()).idle()

        // Nothing is painted yet: no empty list, no "0 transactions" over rows that are still coming.
        assertEquals(emptyList<List<TransactionHistoryRow>>(), view.rendered)
        assertEquals(listOf(true), view.loading)

        gate.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, view.rendered.size)
        assertEquals(1, view.rendered.single().size)
        assertEquals(listOf(true, false), view.loading)
    }

    @Test
    fun zeroEventsAndZeroRowsStillShowTheEmptyListImmediately() {
        val view = RecordingView()

        presenter(view, FakeRepo()).load()
        shadowOf(Looper.getMainLooper()).idle()

        // Nothing to enrich, so the settled empty list paints at once.
        assertEquals(emptyList<TransactionHistoryRow>(), view.rendered.single())
        assertEquals(listOf(true, false), view.loading)
    }

    @Test
    fun aFreshCacheHitServesTheRegistrationsLegWithoutARead() {
        RegistrationsCache.set(listOf(registration(eventA)), userId.toString())
        val repo = FakeRepo().apply {
            // A read here would surface the failure the cache exists to avoid.
            registrations = NetworkResult.Error("must not be read")
            transactions = NetworkResult.Success(listOf(scan(eventA)))
            points[eventA.toString()] = NetworkResult.Success(listOf(point(eventA)))
        }
        val view = RecordingView()

        presenter(view, repo).load()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(0, repo.registrationReads)
        assertEquals(emptyList<String>(), view.errors)
        // The cached rows still supply the event the ledger read needs.
        assertEquals(1, repo.pointReads[eventA.toString()])
        assertEquals(2, view.rendered.last().size)
    }

    @Test
    fun aForcedRefreshReadsTheRegistrationsEvenOnAFreshCacheHit() {
        RegistrationsCache.set(listOf(registration(eventA)), userId.toString())
        val repo = FakeRepo()

        presenter(view = RecordingView(), repo = repo).load(forceNetwork = true)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, repo.registrationReads)
    }

    private fun presenter(view: RecordingView, repo: FakeRepo) =
        TransactionHistoryPresenter(view, repo, session)

    private class FakeRepo : AttendeeRepository(ApplicationProvider.getApplicationContext()) {
        var registrations: NetworkResult<List<RegistrationResponse>> = NetworkResult.Success(emptyList())
        var transactions: NetworkResult<List<TransactionResponse>> = NetworkResult.Success(emptyList())
        val points = mutableMapOf<String, NetworkResult<List<PointTransactionResponse>>>()
        val claims = mutableMapOf<String, NetworkResult<List<RewardRedemptionResponse>>>()
        var registrationReads = 0
        val pointReads = mutableMapOf<String, Int>()
        /** Holds every per-event read open, so a test decides when the enrichment lands. */
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun getMyRegistrations(): NetworkResult<List<RegistrationResponse>> {
            registrationReads++
            return registrations
        }

        override suspend fun getMyTransactions() = transactions

        override suspend fun getMyPointTransactions(eventId: String): NetworkResult<List<PointTransactionResponse>> {
            pointReads[eventId] = (pointReads[eventId] ?: 0) + 1
            gate?.await()
            return points[eventId] ?: NetworkResult.Success(emptyList())
        }

        override suspend fun getMyRewardRedemptions(eventId: String): NetworkResult<List<RewardRedemptionResponse>> {
            gate?.await()
            return claims[eventId] ?: NetworkResult.Success(emptyList())
        }
    }

    private class RecordingView : TransactionHistoryContract.View {
        val rendered = mutableListOf<List<TransactionHistoryRow>>()
        val errors = mutableListOf<String>()
        val loading = mutableListOf<Boolean>()
        override fun showLoading(isLoading: Boolean) { loading += isLoading }
        override fun showError(message: String) { errors += message }
        override fun renderTransactions(items: List<TransactionHistoryRow>) { rendered += items }
        override fun showMessage(message: String) = Unit
    }
}
