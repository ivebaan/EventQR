package com.thedavelopers.eventqr.features.staff

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thedavelopers.eventqr.R
import com.thedavelopers.eventqr.core.api.ApiClient
import com.thedavelopers.eventqr.core.api.NetworkResult
import com.thedavelopers.eventqr.core.api.dto.AccountRole
import com.thedavelopers.eventqr.core.api.dto.EventStatus
import com.thedavelopers.eventqr.core.api.dto.ScanPurposeCode
import com.thedavelopers.eventqr.core.api.dto.TransactionResult
import com.thedavelopers.eventqr.core.api.dto.TransactionType
import com.thedavelopers.eventqr.core.api.sharedGson
import com.thedavelopers.eventqr.core.cache.LastKnownCache
import com.thedavelopers.eventqr.core.cache.OrganizerSnapshots
import com.thedavelopers.eventqr.core.cache.StaffSnapshots
import com.thedavelopers.eventqr.core.session.SessionManager
import com.thedavelopers.eventqr.core.util.UiStrings
import com.thedavelopers.eventqr.features.ShadowEncryptedSharedPreferences
import com.thedavelopers.eventqr.features.ShadowMasterKeys
import com.thedavelopers.eventqr.features.auth.model.dto.LoginResponse
import com.thedavelopers.eventqr.features.notifications.model.dto.NotificationResponse
import com.thedavelopers.eventqr.features.staff.model.dto.StaffAssignedEventResponse
import com.thedavelopers.eventqr.features.staff.model.dto.StaffTodaySummary
import com.thedavelopers.eventqr.features.transactions.model.dto.TransactionRequest
import com.thedavelopers.eventqr.features.transactions.model.dto.TransactionResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.time.Instant
import java.util.UUID

/**
 * The staff dashboard against the persisted today snapshots: the day numbers, the recent scans and
 * the assigned-event gate paint before the network replies with no skeleton, the refresh replaces
 * them, a failure keeps them, a pull bypasses them, and a successful scan drops the today keys.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [ShadowMasterKeys::class, ShadowEncryptedSharedPreferences::class])
class StaffDashboardSnapshotTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val userId = UUID.fromString("dddddddd-1111-1111-1111-111111111111")

    private lateinit var session: SessionManager
    private lateinit var cache: LastKnownCache

    @Before
    fun setUp() {
        AssignedEventsCache.shared.clear()
        session = SessionManager(context).apply { clearSession() }
        session.saveLoginResponse(
            LoginResponse(
                accessToken = "access",
                userId = userId,
                email = "staff@test.com",
                fullName = "Sam Staff",
                role = AccountRole.STAFF,
                refreshToken = "refresh",
            ),
        )
        cache = LastKnownCache(context)
        cache.clearAll()
    }

    @After
    fun tearDown() {
        cache.clearAll()
        AssignedEventsCache.shared.clear()
        session.clearSession()
    }

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
    }

    private fun tx(minutesAgo: Long, id: UUID = UUID.randomUUID()) = TransactionResponse(
        transactionId = id,
        eventId = UUID.randomUUID(),
        attendeeUserId = UUID.randomUUID(),
        registrationId = UUID.randomUUID(),
        qrCredentialId = UUID.randomUUID(),
        scanPurposeId = UUID.randomUUID(),
        transactionType = TransactionType.ENTRY,
        transactionResult = TransactionResult.APPROVED,
        pointsDelta = 0,
        scannedAt = Instant.now().minusSeconds(minutesAgo * 60),
    )

    private fun assignment(title: String) = StaffAssignedEventResponse(
        assignmentId = UUID.randomUUID(),
        eventId = UUID.randomUUID(),
        title = title,
        status = EventStatus.ACTIVE,
        canScan = true,
    )

    private fun seedSnapshot() = runBlocking {
        cache.write(StaffSnapshots.TODAY_SUMMARY, StaffTodaySummary(scannedToday = 42, successfulCheckIns = 17))
        cache.write(StaffSnapshots.TODAY_TRANSACTIONS, listOf(tx(5)))
        cache.write(StaffSnapshots.ASSIGNMENTS, listOf(assignment("Cached meet")))
    }

    private class RecordingView : StaffDashboardContract.View {
        val loading = mutableListOf<Boolean>()
        var scans: Int? = -1
        var checkins: Int? = -1
        val recent = mutableListOf<List<TransactionResponse>>()
        val messages = mutableListOf<String>()

        override fun renderRecentScans(items: List<TransactionResponse>) {
            recent += items
        }

        override fun updateStats(scans: Int?, checkins: Int?) {
            this.scans = scans
            this.checkins = checkins
        }

        override fun showMessage(message: String) {
            messages += message
        }

        override fun showError(message: String) {
            messages += message
        }

        override fun showRecentScansError(message: String) {
            messages += message
        }

        override fun showLoading(isLoading: Boolean) {
            loading += isLoading
        }

        override fun showNotificationBadge(unreadCount: Int) = Unit
    }

    /** Every read stays suspended until the test releases it. */
    private inner class GatedRepository : StaffRepository(
        ApiClient.getService(context),
        assignedEventsCache = AssignedEventsCache(),
        lastKnownCache = cache,
    ) {
        var events: NetworkResult<List<StaffAssignedEventResponse>> = NetworkResult.Success(emptyList())
        var today: NetworkResult<List<TransactionResponse>> = NetworkResult.Success(emptyList())
        var summary: NetworkResult<StaffTodaySummary> = NetworkResult.Success(StaffTodaySummary())
        var gate = CompletableDeferred<Unit>()

        override suspend fun getEvents(): NetworkResult<List<StaffAssignedEventResponse>> {
            gate.await()
            return events
        }

        override suspend fun getMyTodayTransactions(): NetworkResult<List<TransactionResponse>> {
            gate.await()
            return today
        }

        override suspend fun getMyTodaySummary(): NetworkResult<StaffTodaySummary> {
            gate.await()
            return summary
        }

        override suspend fun getMyNotifications(): NetworkResult<List<NotificationResponse>> =
            NetworkResult.Success(emptyList())
    }

    private fun presenter(view: RecordingView, repository: StaffRepository) = StaffDashboardPresenter(
        view,
        repository,
        UiStrings(context),
        CoroutineScope(Dispatchers.Unconfined),
        cache,
    )

    @Test
    fun theSnapshotsPaintTheDayNumbersAndScansBeforeTheApiAnswers_withoutASkeleton() {
        seedSnapshot()
        val view = RecordingView()
        val repository = GatedRepository().apply {
            summary = NetworkResult.Success(StaffTodaySummary(scannedToday = 7, successfulCheckIns = 3))
            today = NetworkResult.Success(listOf(tx(1), tx(2)))
        }

        presenter(view, repository).loadData()
        awaitUntil { view.recent.isNotEmpty() }

        assertEquals(42, view.scans)
        assertEquals(17, view.checkins)
        assertEquals(1, view.recent.single().size)
        // A painted snapshot never raises the skeleton: the only signal is "not loading".
        assertFalse(view.loading.contains(true))
    }

    @Test
    fun theFreshAnswerReplacesTheSnapshots() {
        seedSnapshot()
        val view = RecordingView()
        val repository = GatedRepository().apply {
            summary = NetworkResult.Success(StaffTodaySummary(scannedToday = 7, successfulCheckIns = 3))
            today = NetworkResult.Success(listOf(tx(1), tx(2)))
        }

        presenter(view, repository).loadData()
        awaitUntil { view.recent.isNotEmpty() }

        repository.gate.complete(Unit)
        awaitUntil { view.scans == 7 }

        assertEquals(7, view.scans)
        assertEquals(3, view.checkins)
        assertEquals(2, view.recent.last().size)
    }

    @Test
    fun aFailedRefreshKeepsThePaintedSnapshot() {
        seedSnapshot()
        val view = RecordingView()
        val repository = GatedRepository().apply {
            events = NetworkResult.Error("offline")
            today = NetworkResult.Error("offline")
            summary = NetworkResult.Error("offline")
            gate.complete(Unit)
        }

        presenter(view, repository).loadData()
        awaitUntil { view.loading.contains(false) }
        awaitUntil { view.loading.count { it == false } > 1 }

        assertEquals(42, view.scans)
        assertEquals(17, view.checkins)
        assertEquals(1, view.recent.last().size)
        assertTrue("a failed refresh never replaces painted content", view.messages.isEmpty())
    }

    @Test
    fun aFailedLoadWithoutASnapshotStillShowsTodayErrorState() {
        val view = RecordingView()
        val repository = GatedRepository().apply {
            events = NetworkResult.Error("offline")
            today = NetworkResult.Error("offline")
            summary = NetworkResult.Error("offline")
            gate.complete(Unit)
        }

        presenter(view, repository).loadData()
        awaitUntil { view.messages.isNotEmpty() }

        assertNull(view.scans)
        assertNull(view.checkins)
        assertEquals(listOf(true), view.loading.filter { it })
        assertTrue(view.messages.contains(context.getString(R.string.staff_dashboard_stats_unavailable)))
    }

    @Test
    fun pullToRefreshBypassesTheSnapshots() {
        seedSnapshot()
        val view = RecordingView()
        val repository = GatedRepository()

        presenter(view, repository).loadData(forceNetwork = true)
        awaitUntil { view.loading.isNotEmpty() }

        assertEquals(-1, view.scans)
        assertTrue(view.recent.isEmpty())
        assertEquals(listOf(true), view.loading)
    }

    @Test
    fun aResumeReloadNeverRepaintsTheSnapshotOverFresherNumbers() {
        seedSnapshot()
        val view = RecordingView()
        val repository = GatedRepository().apply {
            summary = NetworkResult.Success(StaffTodaySummary(scannedToday = 7, successfulCheckIns = 3))
            today = NetworkResult.Success(listOf(tx(1), tx(2)))
            // The first load answers immediately, so the screen holds fresh values.
            gate.complete(Unit)
        }
        val dashboard = presenter(view, repository)
        dashboard.loadData()
        awaitUntil { view.scans == 7 }
        assertEquals(2, view.recent.last().size)

        // The second load is still in flight when the check runs, with an older snapshot on disk.
        repository.gate = CompletableDeferred()
        dashboard.loadData()
        awaitUntil { view.loading.size >= 3 }

        assertEquals("a reload must not repaint the snapshot over fresher numbers", 7, view.scans)
        assertEquals(3, view.checkins)
        assertEquals(2, view.recent.last().size)
    }

    @Test
    fun aSummaryThatLandsAfterAnAccountSwitchStaysUnderTheOriginalUser() {
        val view = RecordingView()
        val otherUserId = UUID.fromString("dddddddd-2222-2222-2222-222222222222")
        val repository = GatedRepository().apply {
            summary = NetworkResult.Success(StaffTodaySummary(scannedToday = 7, successfulCheckIns = 3))
            today = NetworkResult.Success(listOf(tx(1)))
            // The account switch lands while the answer is on its way back.
            gate.complete(Unit)
        }
        val switching = object : StaffRepository(
            ApiClient.getService(context),
            assignedEventsCache = AssignedEventsCache(),
            lastKnownCache = cache,
        ) {
            override suspend fun getEvents() = NetworkResult.Error("skip")
            override suspend fun getMyTodayTransactions() = NetworkResult.Error("skip")
            override suspend fun getMyTodaySummary(): NetworkResult<StaffTodaySummary> {
                val result = repository.getMyTodaySummary()
                SessionManager(context).saveLoginResponse(
                    LoginResponse(
                        accessToken = "other",
                        userId = otherUserId,
                        email = "other@test.com",
                        fullName = "Other Staff",
                        role = AccountRole.STAFF,
                    ),
                )
                return result
            }

            override suspend fun getMyNotifications(): NetworkResult<List<NotificationResponse>> =
                NetworkResult.Success(emptyList())
        }

        presenter(view, switching).loadData()
        awaitUntil { view.scans == 7 }

        assertTrue(
            "the answer is filed under the session it was fetched for",
            cache.fileFor(userId.toString(), StaffSnapshots.TODAY_SUMMARY).isFile,
        )
        assertFalse(
            "a switched-in account must not receive the previous session's answer",
            cache.fileFor(otherUserId.toString(), StaffSnapshots.TODAY_SUMMARY).isFile,
        )
    }

    @Test
    fun aSuccessfulScanDropsTheTodayKeys() = runBlocking {
        seedSnapshot()
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(
                MockResponse().setBody(
                    """{"success":true,"data":{"transactionId":"${UUID.randomUUID()}","eventId":"${UUID.randomUUID()}",""" +
                        """"attendeeUserId":"${UUID.randomUUID()}","registrationId":"${UUID.randomUUID()}",""" +
                        """"qrCredentialId":"${UUID.randomUUID()}","scanPurposeId":"${UUID.randomUUID()}",""" +
                        """"transactionType":"ENTRY","transactionResult":"APPROVED","pointsDelta":0}}""",
                ).setHeader("Content-Type", "application/json"),
            )
            val repository = repositoryOver(server)
            val request = TransactionRequest(
                eventId = UUID.randomUUID(),
                scanPurposeId = UUID.randomUUID(),
            )

            val result = repository.createTransaction(request, ScanPurposeCode.ENTRY)
            assertTrue(result is NetworkResult.Success)

            assertNull(cache.read<StaffTodaySummary>(StaffSnapshots.TODAY_SUMMARY, StaffSnapshots.SUMMARY_TYPE, StaffSnapshots.MAX_AGE_MS))
            assertNull(cache.read<List<TransactionResponse>>(StaffSnapshots.TODAY_TRANSACTIONS, StaffSnapshots.TRANSACTIONS_TYPE, StaffSnapshots.MAX_AGE_MS))
            // The organizer screens embed the counts a scan moves, so their list goes too.
            assertNull(cache.read<List<com.thedavelopers.eventqr.features.organizer.OrganizerMvpEvent>>(OrganizerSnapshots.EVENTS, OrganizerSnapshots.EVENTS_TYPE, OrganizerSnapshots.MAX_AGE_MS))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun aFailedScanKeepsTheTodayKeys() = runBlocking {
        seedSnapshot()
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(
                MockResponse().setResponseCode(500).setBody("""{"success":false,"message":"boom"}""")
                    .setHeader("Content-Type", "application/json"),
            )
            val repository = repositoryOver(server)

            val result = repository.createTransaction(
                TransactionRequest(eventId = UUID.randomUUID(), scanPurposeId = UUID.randomUUID()),
                ScanPurposeCode.ENTRY,
            )
            assertTrue(result is NetworkResult.Error)

            assertNotNull(cache.read<StaffTodaySummary>(StaffSnapshots.TODAY_SUMMARY, StaffSnapshots.SUMMARY_TYPE, StaffSnapshots.MAX_AGE_MS))
            assertNotNull(cache.read<List<TransactionResponse>>(StaffSnapshots.TODAY_TRANSACTIONS, StaffSnapshots.TRANSACTIONS_TYPE, StaffSnapshots.MAX_AGE_MS))
        } finally {
            server.shutdown()
        }
    }

    private fun repositoryOver(server: MockWebServer): StaffRepository {
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(GsonConverterFactory.create(sharedGson()))
            .build()
            .create(com.thedavelopers.eventqr.core.api.ApiService::class.java)
        return StaffRepository(api, assignedEventsCache = AssignedEventsCache(), lastKnownCache = cache)
    }
}
