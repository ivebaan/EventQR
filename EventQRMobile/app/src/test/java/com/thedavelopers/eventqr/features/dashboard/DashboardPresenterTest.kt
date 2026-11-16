package com.thedavelopers.eventqr.features.dashboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thedavelopers.eventqr.core.api.NetworkResult
import com.thedavelopers.eventqr.core.api.dto.RedemptionStatus
import com.thedavelopers.eventqr.core.api.dto.RegistrationStatus
import com.thedavelopers.eventqr.core.session.SessionManager
import com.thedavelopers.eventqr.core.util.UiStrings
import com.thedavelopers.eventqr.features.ShadowEncryptedSharedPreferences
import com.thedavelopers.eventqr.features.ShadowMasterKeys
import com.thedavelopers.eventqr.features.attendee.AttendeeRepository
import com.thedavelopers.eventqr.features.dashboard.model.dto.DashboardSummary
import com.thedavelopers.eventqr.features.events.model.dto.AttendeeEventResponse
import com.thedavelopers.eventqr.features.registrations.RegistrationsCache
import com.thedavelopers.eventqr.features.registrations.model.dto.RegistrationResponse
import com.thedavelopers.eventqr.features.rewards.model.dto.RewardRedemptionResponse
import com.thedavelopers.eventqr.features.users.model.dto.UserResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [ShadowMasterKeys::class, ShadowEncryptedSharedPreferences::class])
class DashboardPresenterTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        // A previous test's registrations/answer must not decide this class's request assertions.
        RegistrationsCache.clear()
        SessionManager(context).clearSession()
    }

    @After
    fun tearDown() {
        RegistrationsCache.clear()
    }

    private class RecordingView : DashboardContract.View {
        var summary: DashboardSummary? = null
        val loading = mutableListOf<Boolean>()
        var rewardsCount: Long? = null
        val partialErrors = mutableListOf<String>()
        var error: String? = null

        override fun showLoading(isLoading: Boolean) {
            loading += isLoading
        }

        override fun showSummary(summary: DashboardSummary) {
            this.summary = summary
        }

        override fun showRewardsCount(count: Long) {
            rewardsCount = count
        }

        override fun showError(message: String) {
            error = message
        }

        override fun showMessage(message: String) = Unit
        override fun showPartialError(source: String, message: String) {
            partialErrors += source
        }

        override fun openSection(title: String, message: String) = Unit
        override fun updateHeader(role: String?, name: String?) = Unit
    }

    private inner class FakeDashboardRepo(
        private val summary: NetworkResult<DashboardSummary>,
    ) : DashboardRepository(context) {
        override suspend fun getSummary() = summary
        override suspend fun getCurrentUser(): NetworkResult<UserResponse> = NetworkResult.Error("skip")
    }

    private inner class FakeAttendeeRepo(
        private val registrations: NetworkResult<List<RegistrationResponse>>,
    ) : AttendeeRepository(context) {
        // Non-empty so the presenter never falls back to the real getEvents() call.
        override suspend fun getBrowseEvents(): NetworkResult<List<AttendeeEventResponse>> =
            NetworkResult.Success(listOf(AttendeeEventResponse(eventId = UUID.randomUUID(), title = "Event")))

        override suspend fun getMyRegistrations() = registrations
    }

    /** The redemptions fan-out stays suspended until the test releases it. */
    private inner class GatedRedemptionsRepo(
        private val registration: RegistrationResponse,
        private val redemption: NetworkResult<List<RewardRedemptionResponse>> = NetworkResult.Success(
            listOf(redemption(registration.eventId, RedemptionStatus.REDEEMED), redemption(registration.eventId, RedemptionStatus.PENDING)),
        ),
        val gate: CompletableDeferred<Unit>? = CompletableDeferred(),
    ) : AttendeeRepository(context) {
        var redemptionCalls = 0

        override suspend fun getBrowseEvents(): NetworkResult<List<AttendeeEventResponse>> =
            NetworkResult.Success(listOf(AttendeeEventResponse(eventId = UUID.randomUUID(), title = "Event")))

        override suspend fun getMyRegistrations() = NetworkResult.Success(listOf(registration))

        override suspend fun getMyRewardRedemptions(eventId: String): NetworkResult<List<RewardRedemptionResponse>> {
            redemptionCalls++
            gate?.await()
            return redemption
        }
    }

    private fun summary() = DashboardSummary(
        totalEvents = 0,
        totalRegistrations = 7,
        totalTransactions = 0,
        totalPoints = 0,
        completedEventsCount = 3,
        totalNotifications = 0,
    )

    private fun attendedRegistration() = RegistrationResponse(
        registrationId = UUID.randomUUID(),
        eventId = UUID.randomUUID(),
        attendeeUserId = UUID.randomUUID(),
        attendeeEmail = "a@test.com",
        attendeeName = "A",
        status = RegistrationStatus.REGISTERED,
        attendedAt = Instant.now(),
        eventTitle = "Checked in",
    )

    private fun redemption(eventId: UUID, status: RedemptionStatus) = RewardRedemptionResponse(
        redemptionId = UUID.randomUUID(),
        eventId = eventId,
        attendeeUserId = UUID.randomUUID(),
        rewardId = UUID.randomUUID(),
        pointsSpent = 10,
        status = status,
    )

    /** Runs presenter work eagerly on the calling thread; the fakes never suspend. */
    private fun presenter(view: RecordingView, registrations: NetworkResult<List<RegistrationResponse>>) =
        DashboardPresenter(
            view,
            FakeDashboardRepo(NetworkResult.Success(summary())),
            FakeAttendeeRepo(registrations),
            SessionManager(context),
            UiStrings(context),
            CoroutineScope(Dispatchers.Unconfined),
        )

    private fun presenterFor(view: RecordingView, repo: AttendeeRepository) =
        DashboardPresenter(
            view,
            FakeDashboardRepo(NetworkResult.Success(summary())),
            repo,
            SessionManager(context),
            UiStrings(context),
            CoroutineScope(Dispatchers.Unconfined),
        )

    @Test
    fun registrationsFailure_withSummarySuccess_leavesEveryCellUnknown() {
        val view = RecordingView()
        presenter(view, NetworkResult.Error("boom")).loadDashboard()

        val summary = view.summary
        assertNotNull(summary)
        // The summary's own totals must not be copied into the stub: they are not the same definition.
        assertNull(summary?.totalRegistrations)
        assertNull(summary?.completedEventsCount)
        assertNull(summary?.checkInsCount)
        assertNull(summary?.lifetimeRegistrationsCount)
        // Nothing to fan out over, so the rewards cell stays unknown and no count is reported.
        assertNull(view.rewardsCount)
    }

    @Test
    fun registrationsSuccess_fillsAllThreeCells() {
        val view = RecordingView()
        presenter(view, NetworkResult.Success(emptyList())).loadDashboard()

        val summary = view.summary
        assertEquals(0L, summary?.totalRegistrations)
        assertEquals(0L, summary?.checkInsCount)
        assertEquals(0L, summary?.lifetimeRegistrationsCount)
    }

    @Test
    fun theScreenPaintsBeforeTheRedemptionFanOut_andTheRewardsCellUpdatesWhenItLands() {
        val view = RecordingView()
        val repo = GatedRedemptionsRepo(attendedRegistration())
        val gate = repo.gate!!
        presenterFor(view, repo).loadDashboard()

        // The four screen calls answered and the skeleton is off while the per-event redemptions
        // are still suspended.
        assertEquals(listOf(true, false), view.loading)
        assertNotNull("the summary is on screen before the fan-out", view.summary)
        assertNull("unknown keeps the pass head on --", view.summary?.rewardsClaimedCount)
        assertNull(view.rewardsCount)
        assertEquals(1, repo.redemptionCalls)

        // Release the fan-out: only the rewards cell changes, and PENDING does not count.
        gate.complete(Unit)

        assertEquals(1L, view.rewardsCount)
        assertEquals(1, repo.redemptionCalls)
        // A second summary render would reflow the other numbers; there is none.
        assertEquals(listOf(true, false), view.loading)
    }

    @Test
    fun theRewardsCellEndsOnTheSameValueAsTheUnblockedPath() {
        val view = RecordingView()
        val repo = GatedRedemptionsRepo(attendedRegistration(), gate = null)
        presenterFor(view, repo).loadDashboard()

        assertNull("the summary itself never carries it", view.summary?.rewardsClaimedCount)
        assertEquals(1L, view.rewardsCount)
        assertEquals(1, repo.redemptionCalls)
    }

    @Test
    fun aFailedFanOutKeepsTheScreen_andReportsNoError() {
        val view = RecordingView()
        val repo = GatedRedemptionsRepo(
            attendedRegistration(),
            redemption = NetworkResult.Error("no claim data"),
            gate = null,
        )
        presenterFor(view, repo).loadDashboard()

        assertNotNull(view.summary)
        assertNull(view.rewardsCount)
        assertNull(view.error)
        // Only the faked users/me leg fails; the redemptions failure adds nothing.
        assertEquals(listOf("account"), view.partialErrors)
    }
}
