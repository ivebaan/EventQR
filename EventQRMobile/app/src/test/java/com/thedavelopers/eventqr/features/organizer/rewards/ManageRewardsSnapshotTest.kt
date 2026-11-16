package com.thedavelopers.eventqr.features.organizer.rewards

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thedavelopers.eventqr.R
import com.thedavelopers.eventqr.core.api.dto.AccountRole
import com.thedavelopers.eventqr.core.api.dto.ApiResponse
import com.thedavelopers.eventqr.core.api.dto.RewardStatus
import com.thedavelopers.eventqr.core.cache.LastKnownCache
import com.thedavelopers.eventqr.core.cache.OrganizerSnapshots
import com.thedavelopers.eventqr.core.session.SessionManager
import com.thedavelopers.eventqr.features.ShadowEncryptedSharedPreferences
import com.thedavelopers.eventqr.features.ShadowMasterKeys
import com.thedavelopers.eventqr.features.auth.model.dto.LoginResponse
import com.thedavelopers.eventqr.features.events.model.dto.EventResponse
import com.thedavelopers.eventqr.features.organizer.OrganizerEventsCache
import com.thedavelopers.eventqr.features.organizer.OrganizerMvpDataSource
import com.thedavelopers.eventqr.features.organizer.OrganizerMvpEvent
import com.thedavelopers.eventqr.features.organizer.OrganizerMvpLoad
import com.thedavelopers.eventqr.features.organizer.OrganizerRepository
import com.thedavelopers.eventqr.features.organizer.allTexts
import com.thedavelopers.eventqr.features.organizer.testOrganizerEvent
import com.thedavelopers.eventqr.features.rewards.model.dto.RewardRedemptionResponse
import com.thedavelopers.eventqr.features.rewards.model.dto.RewardRequest
import com.thedavelopers.eventqr.features.rewards.model.dto.RewardResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * The rewards screen against the persisted `organizer/rewards/{eventId}` answers: the cards paint
 * before the network replies, the fresh answer replaces them, a failure keeps them, a pull bypasses
 * the snapshot and the user's own change drops every reward key.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [ShadowMasterKeys::class, ShadowEncryptedSharedPreferences::class])
class ManageRewardsSnapshotTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val userId = UUID.fromString("ffffffff-1111-1111-1111-111111111111")

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

    private fun reward(eventId: String, name: String) = RewardResponse(
        rewardId = UUID.randomUUID(),
        eventId = UUID.fromString(eventId),
        name = name,
        pointsRequired = 10,
        status = RewardStatus.ACTIVE,
        claimedCount = 0,
    )

    private fun seed(eventId: String, name: String) = runBlocking {
        cache.write(OrganizerSnapshots.rewards(eventId), listOf(reward(eventId, name)))
        cache.write(OrganizerSnapshots.claimedRewards(eventId), emptyList<RewardRedemptionResponse>())
        cache.write(OrganizerSnapshots.rewardSettings(eventId), true)
    }

    private class FakeRewardsService : OrganizerRewardsService {
        val gate = CompletableDeferred<Unit>()
        var rewardsCalls = 0
        var rewardsResult: ApiResponse<List<RewardResponse>> = ApiResponse(success = true, data = emptyList())
        var failWith: String? = null

        override suspend fun getRewards(eventId: String): ApiResponse<List<RewardResponse>> {
            rewardsCalls++
            gate.await()
            failWith?.let { throw IllegalStateException(it) }
            return rewardsResult
        }

        override suspend fun getRewardSettings(eventId: String): ApiResponse<Boolean> = ApiResponse(success = true, data = true)

        override suspend fun updateRewardSettings(
            eventId: String,
            request: RewardSettingsRequest,
        ): ApiResponse<EventResponse> = ApiResponse(success = true, data = null)

        override suspend fun createReward(eventId: String, request: RewardRequest): ApiResponse<RewardResponse> =
            ApiResponse(success = true)

        override suspend fun updateReward(
            eventId: String,
            rewardId: String,
            request: RewardRequest,
        ): ApiResponse<RewardResponse> = ApiResponse(success = true)

        override suspend fun deleteReward(eventId: String, rewardId: String): ApiResponse<Unit> = ApiResponse(success = true)

        override suspend fun getClaimedRewards(eventId: String): ApiResponse<List<RewardRedemptionResponse>> =
            ApiResponse(success = true, data = emptyList())
    }

    private inner class FakeEventsRepository : OrganizerRepository(
        context,
        eventsCache = OrganizerEventsCache(),
        lastKnownCache = cache,
    ) {
        override suspend fun loadApprovedOrganizerEvents(): OrganizerMvpLoad<List<OrganizerMvpEvent>> =
            OrganizerMvpLoad(listOf(event), OrganizerMvpDataSource.BACKEND)
    }

    private fun buildActivity(service: FakeRewardsService): ManageRewardsActivity {
        val controller = Robolectric.buildActivity(ManageRewardsActivity::class.java)
        val activity = controller.get().apply {
            rewardsServiceOverride = service
            cacheOverride = cache
            installRepositoryFactory { FakeEventsRepository() }
        }
        controller.create()
        awaitUntil { activity.hasEvent() }
        return activity
    }

    private fun ManageRewardsActivity.hasEvent(): Boolean = runCatching { selectedEvent }.isSuccess

    private fun ManageRewardsActivity.texts(): List<String> = window.decorView.allTexts()

    @Test
    fun theSnapshotPaintsTheRewardsBeforeTheApiAnswers() {
        seed(event.id, "Cached reward")
        val service = FakeRewardsService()
        val activity = buildActivity(service)

        awaitUntil { activity.texts().contains("Cached reward") }

        assertEquals("the screen must ask the network once", 1, service.rewardsCalls)
        assertTrue(activity.texts().contains("Cached reward"))
        assertFalse(
            "the loading line was drawn over the painted cards",
            activity.texts().contains(activity.getString(R.string.manage_rewards_loading)),
        )
    }

    @Test
    fun theFreshAnswerReplacesTheSnapshot() {
        seed(event.id, "Cached reward")
        val service = FakeRewardsService()
        val activity = buildActivity(service)
        awaitUntil { activity.texts().contains("Cached reward") }

        service.rewardsResult = ApiResponse(success = true, data = listOf(reward(event.id, "Fresh reward")))
        service.gate.complete(Unit)
        awaitUntil { activity.texts().contains("Fresh reward") }

        assertTrue(activity.texts().contains("Fresh reward"))
        assertFalse(activity.texts().contains("Cached reward"))
    }

    @Test
    fun aFailedRefreshKeepsThePaintedRewards() {
        seed(event.id, "Cached reward")
        val service = FakeRewardsService().apply { failWith = "offline" }
        val activity = buildActivity(service)
        awaitUntil { activity.texts().contains("Cached reward") }

        service.gate.complete(Unit)
        idle()
        idle()

        assertTrue("the painted cards were wiped", activity.texts().contains("Cached reward"))
    }

    @Test
    fun aFailedLoadWithoutASnapshotShowsTheErrorState() {
        val service = FakeRewardsService().apply {
            failWith = "offline"
            gate.complete(Unit)
        }
        val activity = buildActivity(service)

        awaitUntil { activity.texts().contains("offline") }

        assertTrue(activity.texts().contains("offline"))
    }

    @Test
    fun pullToRefreshBypassesTheSnapshot() {
        val service = FakeRewardsService()
        val activity = buildActivity(service)
        awaitUntil { service.rewardsCalls == 1 }

        seed(event.id, "Cached reward")
        activity.loadRewards(showInitialLoading = false, forceNetwork = true)
        idle()

        assertEquals(2, service.rewardsCalls)
        assertFalse("a pull never paints the persisted cards", activity.texts().contains("Cached reward"))
    }

    @Test
    fun aSettingsToggleDropsEveryRewardKey() {
        val service = FakeRewardsService()
        val activity = buildActivity(service)
        seed(event.id, "Cached reward")

        activity.setRewardsEnabled(true)
        awaitUntil { runBlocking { cache.read<Boolean>(OrganizerSnapshots.rewardSettings(event.id), OrganizerSnapshots.REWARD_SETTINGS_TYPE, OrganizerSnapshots.MAX_AGE_MS) } == null }

        assertNull(
            runBlocking { cache.read<List<RewardResponse>>(OrganizerSnapshots.rewards(event.id), OrganizerSnapshots.REWARDS_TYPE, OrganizerSnapshots.MAX_AGE_MS) },
        )
        assertNull(
            runBlocking { cache.read<List<RewardRedemptionResponse>>(OrganizerSnapshots.claimedRewards(event.id), OrganizerSnapshots.CLAIMED_REWARDS_TYPE, OrganizerSnapshots.MAX_AGE_MS) },
        )
        assertNull(
            runBlocking { cache.read<Boolean>(OrganizerSnapshots.rewardSettings(event.id), OrganizerSnapshots.REWARD_SETTINGS_TYPE, OrganizerSnapshots.MAX_AGE_MS) },
        )
    }
}
