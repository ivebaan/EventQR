package com.thedavelopers.eventqr.features.organizer.dashboard

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thedavelopers.eventqr.R
import com.thedavelopers.eventqr.core.api.ApiService
import com.thedavelopers.eventqr.core.api.NetworkResult
import com.thedavelopers.eventqr.core.api.dto.AccountRole
import com.thedavelopers.eventqr.core.api.dto.NotificationType
import com.thedavelopers.eventqr.core.api.sharedGson
import com.thedavelopers.eventqr.core.cache.LastKnownCache
import com.thedavelopers.eventqr.core.cache.OrganizerSnapshots
import com.thedavelopers.eventqr.core.session.SessionManager
import com.thedavelopers.eventqr.features.ShadowEncryptedSharedPreferences
import com.thedavelopers.eventqr.features.ShadowMasterKeys
import com.thedavelopers.eventqr.features.auth.model.dto.LoginResponse
import com.thedavelopers.eventqr.features.events.model.dto.EventRequest
import com.thedavelopers.eventqr.features.notifications.model.dto.NotificationResponse
import com.thedavelopers.eventqr.features.organizer.OrganizerEventsCache
import com.thedavelopers.eventqr.features.organizer.OrganizerMvpDataSource
import com.thedavelopers.eventqr.features.organizer.OrganizerMvpEvent
import com.thedavelopers.eventqr.features.organizer.OrganizerMvpLoad
import com.thedavelopers.eventqr.features.organizer.OrganizerRepository
import com.thedavelopers.eventqr.features.organizer.model.dto.OrganizerEventDto
import com.thedavelopers.eventqr.features.organizer.testOrganizerEvent
import kotlinx.coroutines.CompletableDeferred
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
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.time.Instant
import java.util.UUID

/**
 * The organizer dashboard against the persisted `organizer/events` answer: the rows and the six
 * derived numbers (plan §6.4) paint before the network replies with no skeleton, the refresh then
 * replaces them, a failure keeps what is painted, a pull bypasses the snapshot, and an event edit
 * drops it so the next open fetches.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [ShadowMasterKeys::class, ShadowEncryptedSharedPreferences::class])
class OrganizerDashboardSnapshotTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val userId = UUID.fromString("bbbbbbbb-1111-1111-1111-111111111111")

    private lateinit var session: SessionManager
    private lateinit var cache: LastKnownCache

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
    }

    @After
    fun tearDown() {
        cache.clearAll()
        OrganizerEventsCache.shared.clear()
        session.clearSession()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Background work lands through the IO dispatcher: idle the main looper until the condition holds. */
    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            idle()
            Thread.sleep(10)
        }
        idle()
    }

    private fun upcomingEvent(title: String, registered: Long = 30, redemptions: Long = 4): OrganizerMvpEvent {
        val now = Instant.now()
        return testOrganizerEvent(title).copy(
            eventStartAt = now.plusSeconds(3_600),
            eventEndAt = now.plusSeconds(7_200),
            registeredCount = registered,
            attendedCount = 0,
            rewardRedemptions = redemptions,
        )
    }

    private fun seedSnapshot(title: String = "Cached fest") = runBlocking {
        cache.write(OrganizerSnapshots.EVENTS, listOf(upcomingEvent(title)))
    }

    /** The events read stays suspended until the test releases it. */
    private inner class GatedRepository : OrganizerRepository(
        context,
        eventsCache = OrganizerEventsCache(),
        lastKnownCache = cache,
    ) {
        var result: OrganizerMvpLoad<List<OrganizerMvpEvent>> =
            OrganizerMvpLoad(emptyList(), OrganizerMvpDataSource.ERROR, "unset")
        val gate = CompletableDeferred<Unit>()
        var calls = 0

        override suspend fun loadEventsForMvp(): OrganizerMvpLoad<List<OrganizerMvpEvent>> {
            calls++
            gate.await()
            return result
        }

        override suspend fun getMyNotifications(
            eventId: UUID?,
            notificationType: NotificationType?,
        ): NetworkResult<List<NotificationResponse>> = NetworkResult.Success(emptyList())
    }

    private fun buildActivity(repository: OrganizerRepository): OrganizerDashboardActivity {
        val controller = Robolectric.buildActivity(OrganizerDashboardActivity::class.java)
        val activity = controller.get().apply {
            installRepositoryFactory { repository }
            sessionRefresh = { }
            cacheOverride = cache
        }
        controller.create()
        return activity
    }

    private fun OrganizerDashboardActivity.nextRowTitle(): String? =
        findViewById<ViewGroup>(R.id.nextEventHost).findViewById<TextView>(R.id.listRowTitle)?.text?.toString()

    /** Each summaryCard is a vertical row: horizontal label/value line, then the hairline. */
    private fun OrganizerDashboardActivity.metricCells(): List<Pair<String, String>> {
        val rows = findViewById<LinearLayout>(R.id.metricRowsContainer)
        return (0 until rows.childCount).map { index ->
            val line = (rows.getChildAt(index) as LinearLayout).getChildAt(0) as LinearLayout
            (line.getChildAt(0) as TextView).text.toString() to
                (line.getChildAt(1) as TextView).text.toString()
        }
    }

    @Test
    fun theSnapshotPaintsRowsAndNumbersBeforeTheApiAnswers_withoutASkeleton() {
        seedSnapshot()
        val repository = GatedRepository().apply {
            result = OrganizerMvpLoad(listOf(upcomingEvent("Fresh fest")), OrganizerMvpDataSource.BACKEND)
        }
        val activity = buildActivity(repository)

        awaitUntil { activity.nextRowTitle() == "Cached fest" }

        // The API is still gated: every cell came from the persisted answer.
        assertEquals(1, repository.calls)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.skeletonLoading).visibility)
        assertEquals(
            listOf("Upcoming" to "1", "Ended" to "0", "Redeemed" to "4"),
            activity.metricCells(),
        )
        // The bell is not a spinner: with no notification snapshot it keeps the absent state.
        assertEquals(View.GONE, activity.findViewById<View>(R.id.passHeadBellBadge).visibility)
    }

    @Test
    fun theFreshAnswerReplacesTheSnapshot() {
        seedSnapshot()
        val repository = GatedRepository().apply {
            result = OrganizerMvpLoad(listOf(upcomingEvent("Fresh fest", registered = 5)), OrganizerMvpDataSource.BACKEND)
        }
        val activity = buildActivity(repository)
        awaitUntil { activity.nextRowTitle() == "Cached fest" }

        repository.gate.complete(Unit)
        awaitUntil { activity.nextRowTitle() == "Fresh fest" }

        assertEquals("Fresh fest", activity.nextRowTitle())
        assertEquals(listOf("Upcoming" to "1", "Ended" to "0", "Redeemed" to "4"), activity.metricCells())
    }

    @Test
    fun aFailedRefreshKeepsThePaintedSnapshot() {
        seedSnapshot()
        val repository = GatedRepository().apply {
            result = OrganizerMvpLoad(emptyList(), OrganizerMvpDataSource.ERROR, "offline")
        }
        val activity = buildActivity(repository)
        awaitUntil { activity.nextRowTitle() == "Cached fest" }

        repository.gate.complete(Unit)
        idle()
        idle()

        // The rows, the numbers and the bell stay; the error card never takes the screen.
        assertEquals("Cached fest", activity.nextRowTitle())
        assertEquals(View.GONE, activity.findViewById<View>(R.id.layoutDashboardError).visibility)
        assertEquals(listOf("Upcoming" to "1", "Ended" to "0", "Redeemed" to "4"), activity.metricCells())
    }

    @Test
    fun aFailedLoadWithoutASnapshotStillShowsTheErrorState() {
        val repository = GatedRepository().apply {
            result = OrganizerMvpLoad(emptyList(), OrganizerMvpDataSource.ERROR, "offline")
            gate.complete(Unit)
        }
        val activity = buildActivity(repository)

        awaitUntil { activity.findViewById<View>(R.id.layoutDashboardError).visibility == View.VISIBLE }

        assertNull(activity.nextRowTitle())
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.layoutDashboardError).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.skeletonLoading).visibility)
    }

    @Test
    fun pullToRefreshBypassesTheSnapshot() {
        seedSnapshot()
        val repository = GatedRepository().apply {
            result = OrganizerMvpLoad(listOf(upcomingEvent("Fresh fest")), OrganizerMvpDataSource.BACKEND)
        }
        val activity = buildActivity(repository)

        // A pull replaces the open's load, so the queued snapshot paint never runs.
        activity.loadDashboard(forceNetwork = true)
        idle()

        assertEquals(1, repository.calls)
        assertNull("a pull never paints the persisted rows", activity.nextRowTitle())
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.skeletonLoading).visibility)
        assertTrue("nothing was painted", activity.metricCells().all { it.second == "--" })
    }

    @Test
    fun anEventEditDropsThePersistedList_soTheNextOpenFetches() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            val eventId = UUID.randomUUID()
            val api: ApiService = Retrofit.Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(GsonConverterFactory.create(sharedGson()))
                .build()
                .create(ApiService::class.java)
            server.enqueue(
                MockResponse().setBody(
                    """{"success":true,"data":[{"eventId":"$eventId","title":"Fest","status":"Approved"}]}""",
                ).setHeader("Content-Type", "application/json"),
            )
            server.enqueue(
                MockResponse().setBody(
                    """{"success":true,"data":{"eventId":"$eventId","title":"Fest","status":"Approved"}}""",
                ).setHeader("Content-Type", "application/json"),
            )
            val repository = OrganizerRepository(
                context,
                apiService = api,
                eventsCache = OrganizerEventsCache(),
                lastKnownCache = cache,
            )

            repository.loadEventsForMvp()
            assertNotNull(
                "the successful read persists the list",
                cache.read<List<OrganizerMvpEvent>>(
                    OrganizerSnapshots.EVENTS,
                    OrganizerSnapshots.EVENTS_TYPE,
                    OrganizerSnapshots.MAX_AGE_MS,
                ),
            )

            repository.updateOrganizerEvent(eventId.toString(), EventRequest(title = "Fest", capacity = 10, rewardsEnabled = false))

            assertNull(
                "the organizer's own edit must not be masked by an older snapshot",
                cache.read<List<OrganizerMvpEvent>>(
                    OrganizerSnapshots.EVENTS,
                    OrganizerSnapshots.EVENTS_TYPE,
                    OrganizerSnapshots.MAX_AGE_MS,
                ),
            )

            // The next open has nothing to paint, so the network owns the first frame again.
            server.enqueue(
                MockResponse().setBody(
                    """{"success":true,"data":[{"eventId":"$eventId","title":"Fest","status":"Approved"}]}""",
                ).setHeader("Content-Type", "application/json"),
            )
            repository.loadEventsForMvp()
            assertEquals(3, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun loggingOutWipesTheRememberedOrganizerSelection() {
        val repository = OrganizerRepository(context, lastKnownCache = cache)
        repository.saveSelectedEventId("event-1")
        assertEquals("event-1", repository.getSelectedEventId())

        // The registry's clearer runs on every session transition: the previous session's pick is gone.
        SessionManager(context).clearSession()

        assertNull(repository.getSelectedEventId())
    }

    @Test
    fun anAnswerThatLandsAfterAnAccountSwitchStaysUnderTheOriginalUser() = runBlocking {
        val otherUserId = UUID.fromString("bbbbbbbb-2222-2222-2222-222222222222")
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(
                MockResponse().setBody(
                    """{"success":true,"data":[{"eventId":"${UUID.randomUUID()}","title":"Fest","status":"Approved"}]}""",
                ).setHeader("Content-Type", "application/json"),
            )
            val api: ApiService = Retrofit.Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(GsonConverterFactory.create(sharedGson()))
                .build()
                .create(ApiService::class.java)
            val repository = object : OrganizerRepository(
                context,
                apiService = api,
                eventsCache = OrganizerEventsCache(),
                lastKnownCache = cache,
            ) {
                override suspend fun fetchOrganizerEvents(): NetworkResult<List<OrganizerEventDto>> {
                    val result = super.fetchOrganizerEvents()
                    // The account switch lands while the answer is on its way back.
                    SessionManager(context).saveLoginResponse(
                        LoginResponse(
                            accessToken = "other",
                            userId = otherUserId,
                            email = "other@test.com",
                            fullName = "Other Org",
                            role = AccountRole.ORGANIZER,
                        ),
                    )
                    return result
                }
            }

            repository.loadEventsForMvp()

            assertTrue(
                "the answer is filed under the session it was fetched for",
                cache.fileFor(userId.toString(), OrganizerSnapshots.EVENTS).isFile,
            )
            assertFalse(
                "a switched-in account must not receive the previous session's answer",
                cache.fileFor(otherUserId.toString(), OrganizerSnapshots.EVENTS).isFile,
            )
        } finally {
            server.shutdown()
        }
    }
}
