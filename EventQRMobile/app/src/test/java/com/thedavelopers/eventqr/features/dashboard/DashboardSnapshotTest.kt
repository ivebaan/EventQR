package com.thedavelopers.eventqr.features.dashboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thedavelopers.eventqr.core.api.NetworkResult
import com.thedavelopers.eventqr.core.api.dto.AccountRole
import com.thedavelopers.eventqr.core.api.dto.RegistrationStatus
import com.thedavelopers.eventqr.core.cache.AttendeeSnapshots
import com.thedavelopers.eventqr.core.cache.LastKnownCache
import com.thedavelopers.eventqr.core.session.SessionManager
import com.thedavelopers.eventqr.core.util.UiStrings
import com.thedavelopers.eventqr.features.ShadowEncryptedSharedPreferences
import com.thedavelopers.eventqr.features.ShadowMasterKeys
import com.thedavelopers.eventqr.features.attendee.AttendeeRepository
import com.thedavelopers.eventqr.features.auth.model.dto.LoginResponse
import com.thedavelopers.eventqr.features.dashboard.model.dto.DashboardSummary
import com.thedavelopers.eventqr.features.events.model.dto.AttendeeEventResponse
import com.thedavelopers.eventqr.features.registrations.RegistrationsCache
import com.thedavelopers.eventqr.features.registrations.model.dto.RegistrationResponse
import com.thedavelopers.eventqr.features.users.model.dto.UserResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.UUID

/**
 * The persisted dashboard: the last known screen paints before the network answers, the refresh
 * then replaces it, and a failure never destroys what is on screen. Pull-to-refresh bypasses the
 * snapshot, and a registration change drops it so the next open refetches.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [ShadowMasterKeys::class, ShadowEncryptedSharedPreferences::class])
class DashboardSnapshotTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val userId = UUID.fromString("aaaaaaaa-1111-1111-1111-111111111111")

    private lateinit var session: SessionManager
    private lateinit var cache: LastKnownCache

    @Before
    fun setUp() {
        RegistrationsCache.clear()
        // The repository's invalidation runs against the process-wide instance: rebuild it so it
        // points at this test's own directory instead of an earlier test's.
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

    /** Records the order of the view calls: the paint must land before any loading signal. */
    private class RecordingView : DashboardContract.View {
        val calls = mutableListOf<String>()
        var summary: DashboardSummary? = null
        var error: String? = null

        override fun showLoading(isLoading: Boolean) {
            calls += "loading:$isLoading"
        }

        override fun showSummary(summary: DashboardSummary) {
            calls += "summary"
            this.summary = summary
        }

        override fun showRewardsCount(count: Long) = Unit
        override fun showError(message: String) {
            calls += "error"
            error = message
        }

        override fun showMessage(message: String) = Unit
        override fun showPartialError(source: String, message: String) = Unit
        override fun openSection(title: String, message: String) = Unit
        override fun updateHeader(role: String?, name: String?) = Unit
    }

    /** The dashboard summary read stays suspended until the test releases it. */
    private inner class GatedDashboardRepo(private val result: NetworkResult<DashboardSummary>) :
        DashboardRepository(context) {
        val gate = CompletableDeferred<Unit>()

        override suspend fun getSummary(): NetworkResult<DashboardSummary> {
            gate.await()
            return result
        }

        override suspend fun getCurrentUser(): NetworkResult<UserResponse> = NetworkResult.Error("skip")
    }

    private inner class AttendeeRepo(
        private val browse: NetworkResult<List<AttendeeEventResponse>>,
        private val registrations: NetworkResult<List<RegistrationResponse>>,
    ) : AttendeeRepository(context) {
        override suspend fun getBrowseEvents(): NetworkResult<List<AttendeeEventResponse>> = browse

        // The presenter falls back to the visible-events read when browse fails or is empty.
        override suspend fun getEvents(): NetworkResult<List<AttendeeEventResponse>> = browse

        override suspend fun getMyRegistrations() = registrations
    }

    private fun upcomingEvent() = AttendeeEventResponse(eventId = UUID.randomUUID(), title = "Event")

    private fun summary(registered: Long, totalEvents: Long = 3) = DashboardSummary(
        totalEvents = totalEvents,
        totalRegistrations = registered,
        totalTransactions = 0,
        totalPoints = 0,
        completedEventsCount = 1,
        totalNotifications = 2,
        fullName = "Ada Lovelace",
    )

    private fun registration() = RegistrationResponse(
        registrationId = UUID.randomUUID(),
        eventId = UUID.randomUUID(),
        attendeeUserId = userId,
        attendeeEmail = "a@test.com",
        attendeeName = "Ada Lovelace",
        status = RegistrationStatus.REGISTERED,
        eventStartAt = Instant.parse("2026-12-01T09:00:00Z"),
        eventEndAt = Instant.parse("2026-12-01T17:00:00Z"),
        eventTitle = "DevFest",
    )

    private fun seedSnapshot(registered: Long = 9) = runBlocking {
        cache.write(AttendeeSnapshots.DASHBOARD_SUMMARY, summary(registered, totalEvents = 7))
        cache.write(AttendeeSnapshots.DASHBOARD_UPCOMING, summary(registered).upcomingEvents.orEmpty())
        cache.write(AttendeeSnapshots.REGISTRATIONS, listOf(registration()))
    }

    private fun presenter(
        view: RecordingView,
        dashboardRepo: DashboardRepository,
        attendeeRepo: AttendeeRepository,
        scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
    ) = DashboardPresenter(
        view,
        dashboardRepo,
        attendeeRepo,
        session,
        UiStrings(context),
        scope,
        cache,
    )

    /** The snapshot read hops to an IO thread, so wait for the paint instead of assuming its timing. */
    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
    }

    private fun awaitCall(view: RecordingView, call: String) = awaitUntil { call in view.calls }

    @Test
    fun theSnapshotPaintsBeforeTheApiAnswers_andNoSkeletonIsRaisedForIt() {
        seedSnapshot()
        val view = RecordingView()
        val repo = GatedDashboardRepo(NetworkResult.Success(summary(4, totalEvents = 5)))

        presenter(view, repo, AttendeeRepo(NetworkResult.Success(listOf(upcomingEvent())), NetworkResult.Success(emptyList())))
            .loadDashboard()
        awaitCall(view, "summary")

        // The paint comes first: no loading signal has been recorded yet.
        assertEquals("summary", view.calls.first())
        // The painted screen is the persisted one (7 events), not the answer still gated (5).
        assertEquals(7L, view.summary?.totalEvents)
        // The pass-head counts come from the persisted registrations list, not the older summary.
        assertEquals(1L, view.summary?.upcomingCount)
        assertEquals(1L, view.summary?.totalRegistrations)
    }

    @Test
    fun theFreshAnswerReplacesTheSnapshot() {
        seedSnapshot()
        val view = RecordingView()
        val repo = GatedDashboardRepo(NetworkResult.Success(summary(4, totalEvents = 5)))

        presenter(view, repo, AttendeeRepo(NetworkResult.Success(listOf(upcomingEvent())), NetworkResult.Success(listOf(registration()))))
            .loadDashboard()
        awaitCall(view, "summary")
        assertEquals(7L, view.summary?.totalEvents)

        repo.gate.complete(Unit)
        awaitUntil { view.summary?.totalEvents == 5L }

        assertEquals(5L, view.summary?.totalEvents)
    }

    @Test
    fun aFailedRefreshKeepsThePaintedSnapshot() {
        seedSnapshot()
        val view = RecordingView()
        val repo = GatedDashboardRepo(NetworkResult.Error("offline"))

        presenter(view, repo, AttendeeRepo(NetworkResult.Success(listOf(upcomingEvent())), NetworkResult.Error("offline")))
            .loadDashboard()
        awaitCall(view, "summary")
        repo.gate.complete(Unit)
        awaitCall(view, "error")

        // The painted screen is never replaced by the partial-data fallback: one paint, the snapshot.
        assertEquals(1, view.calls.count { it == "summary" })
        assertEquals(7L, view.summary?.totalEvents)
        assertEquals("offline", view.error)
    }

    @Test
    fun aFailedLoadWithoutASnapshotStillShowsTheErrorState() {
        val view = RecordingView()
        val repo = GatedDashboardRepo(NetworkResult.Error("offline"))
        repo.gate.complete(Unit)

        presenter(view, repo, AttendeeRepo(NetworkResult.Error("offline"), NetworkResult.Error("offline")))
            .loadDashboard()
        awaitCall(view, "error")

        assertNull(view.summary)
        assertEquals("offline", view.error)
        assertEquals("loading:true", view.calls.first())
    }

    @Test
    fun pullToRefreshBypassesTheSnapshot() {
        seedSnapshot()
        val view = RecordingView()
        val repo = GatedDashboardRepo(NetworkResult.Success(summary(4, totalEvents = 5)))

        presenter(view, repo, AttendeeRepo(NetworkResult.Success(listOf(upcomingEvent())), NetworkResult.Success(emptyList())))
            .loadDashboard(forceNetwork = true)
        awaitCall(view, "loading:true")

        assertNull("a pull always asks the network", view.summary)
    }

    @Test
    fun aFailedEventsLegDoesNotPersistTheEmptyRowBlocks() {
        val view = RecordingView()
        val repo = GatedDashboardRepo(NetworkResult.Success(summary(4, totalEvents = 5)))
        repo.gate.complete(Unit)

        presenter(
            view,
            repo,
            AttendeeRepo(NetworkResult.Error("offline"), NetworkResult.Success(listOf(registration()))),
        ).loadDashboard()
        awaitCall(view, "summary")

        // The summary answered, but the events read did not: a snapshot would persist empty upcoming
        // and discover blocks beside real counts, and a later failure would keep them on screen.
        assertNull(
            runBlocking {
                cache.read<DashboardSummary>(
                    AttendeeSnapshots.DASHBOARD_SUMMARY,
                    AttendeeSnapshots.SUMMARY_TYPE,
                    AttendeeSnapshots.MAX_AGE_MS,
                )
            },
        )
    }

    @Test
    fun aRegistrationChangeDropsTheSnapshot_soTheNextOpenFetches() {
        seedSnapshot()
        runBlocking { AttendeeRepository(context).invalidateRegistrationSnapshots() }

        assertNull(runBlocking { cache.read<DashboardSummary>(AttendeeSnapshots.DASHBOARD_SUMMARY, AttendeeSnapshots.SUMMARY_TYPE, AttendeeSnapshots.MAX_AGE_MS) })
        assertNull(runBlocking { cache.read<List<RegistrationResponse>>(AttendeeSnapshots.REGISTRATIONS, AttendeeSnapshots.REGISTRATIONS_TYPE, AttendeeSnapshots.MAX_AGE_MS) })
        assertNull(runBlocking { cache.read<List<com.thedavelopers.eventqr.features.dashboard.model.dto.DashboardUpcomingEvent>>(AttendeeSnapshots.DASHBOARD_UPCOMING, AttendeeSnapshots.UPCOMING_TYPE, AttendeeSnapshots.MAX_AGE_MS) })

        // The next open has nothing to paint, so the network owns the first frame.
        val view = RecordingView()
        val repo = GatedDashboardRepo(NetworkResult.Success(summary(1)))
        presenter(view, repo, AttendeeRepo(NetworkResult.Success(listOf(upcomingEvent())), NetworkResult.Success(emptyList())))
            .loadDashboard()
        awaitCall(view, "loading:true")
        assertNull(view.summary)
        assertTrue(view.calls.first() == "loading:true")
    }
}
