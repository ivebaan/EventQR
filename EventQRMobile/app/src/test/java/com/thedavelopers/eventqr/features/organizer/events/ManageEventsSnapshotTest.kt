package com.thedavelopers.eventqr.features.organizer.events

import android.content.Context
import android.os.Looper
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thedavelopers.eventqr.R
import com.thedavelopers.eventqr.core.api.NetworkResult
import com.thedavelopers.eventqr.core.api.dto.AccountRole
import com.thedavelopers.eventqr.core.api.dto.NotificationType
import com.thedavelopers.eventqr.core.cache.LastKnownCache
import com.thedavelopers.eventqr.core.cache.OrganizerSnapshots
import com.thedavelopers.eventqr.core.session.SessionManager
import com.thedavelopers.eventqr.features.ShadowEncryptedSharedPreferences
import com.thedavelopers.eventqr.features.ShadowMasterKeys
import com.thedavelopers.eventqr.features.auth.model.dto.LoginResponse
import com.thedavelopers.eventqr.features.notifications.model.dto.NotificationResponse
import com.thedavelopers.eventqr.features.organizer.OrganizerEventsCache
import com.thedavelopers.eventqr.features.organizer.OrganizerMvpDataSource
import com.thedavelopers.eventqr.features.organizer.OrganizerMvpEvent
import com.thedavelopers.eventqr.features.organizer.OrganizerMvpLoad
import com.thedavelopers.eventqr.features.organizer.OrganizerRepository
import com.thedavelopers.eventqr.features.organizer.testOrganizerEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
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
 * The organizer event list against the persisted `organizer/events` answer: the rows paint before
 * the network replies with no skeleton, the refresh replaces them, a failure keeps them, a pull
 * bypasses the snapshot.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [ShadowMasterKeys::class, ShadowEncryptedSharedPreferences::class])
class ManageEventsSnapshotTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val userId = UUID.fromString("cccccccc-1111-1111-1111-111111111111")

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

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            idle()
            Thread.sleep(10)
        }
        idle()
    }

    private fun upcomingEvent(title: String): OrganizerMvpEvent {
        val now = Instant.now()
        return testOrganizerEvent(title).copy(
            eventStartAt = now.plusSeconds(3_600),
            eventEndAt = now.plusSeconds(7_200),
        )
    }

    private fun seedSnapshot(title: String = "Cached fest") = runBlocking {
        cache.write(OrganizerSnapshots.EVENTS, listOf(upcomingEvent(title)))
    }

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

    private fun buildActivity(repository: OrganizerRepository): ManageEventsActivity {
        val controller = Robolectric.buildActivity(ManageEventsActivity::class.java)
        val activity = controller.get().apply {
            installRepositoryFactory { repository }
            cacheOverride = cache
        }
        controller.create()
        return activity
    }

    private fun ManageEventsActivity.rowCount(): Int =
        findViewById<RecyclerView>(R.id.recyclerEvents).adapter?.itemCount ?: 0

    @Test
    fun theSnapshotPaintsTheRowsBeforeTheApiAnswers_withoutASkeleton() {
        seedSnapshot()
        val repository = GatedRepository().apply {
            result = OrganizerMvpLoad(listOf(upcomingEvent("Fresh fest")), OrganizerMvpDataSource.BACKEND)
        }
        val activity = buildActivity(repository)

        awaitUntil { activity.rowCount() == 1 }

        assertEquals(1, repository.calls)
        assertEquals(1, activity.rowCount())
        assertEquals(View.GONE, activity.findViewById<View>(R.id.skeletonLoading).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.recyclerEvents).visibility)
    }

    @Test
    fun theFreshAnswerReplacesTheSnapshot() {
        seedSnapshot()
        val repository = GatedRepository().apply {
            result = OrganizerMvpLoad(
                listOf(upcomingEvent("Fresh one"), upcomingEvent("Fresh two")),
                OrganizerMvpDataSource.BACKEND,
            )
        }
        val activity = buildActivity(repository)
        awaitUntil { activity.rowCount() == 1 }

        repository.gate.complete(Unit)
        awaitUntil { activity.rowCount() == 2 }

        assertEquals(2, activity.rowCount())
    }

    @Test
    fun aFailedRefreshKeepsThePaintedRows() {
        seedSnapshot()
        val repository = GatedRepository().apply {
            result = OrganizerMvpLoad(emptyList(), OrganizerMvpDataSource.ERROR, "offline")
        }
        val activity = buildActivity(repository)
        awaitUntil { activity.rowCount() == 1 }

        repository.gate.complete(Unit)
        idle()
        idle()

        assertEquals(1, activity.rowCount())
        assertEquals(View.GONE, activity.findViewById<View>(R.id.layoutEventsEmpty).visibility)
    }

    @Test
    fun aFailedLoadWithoutASnapshotStillShowsTheErrorState() {
        val repository = GatedRepository().apply {
            result = OrganizerMvpLoad(emptyList(), OrganizerMvpDataSource.ERROR, "offline")
            gate.complete(Unit)
        }
        val activity = buildActivity(repository)

        awaitUntil { activity.findViewById<View>(R.id.layoutEventsEmpty).visibility == View.VISIBLE }

        assertEquals(0, activity.rowCount())
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
        activity.loadEvents(forceNetwork = true)
        idle()

        assertEquals(1, repository.calls)
        assertEquals("a pull never paints the persisted rows", 0, activity.rowCount())
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.skeletonLoading).visibility)
        assertTrue(activity.rowCount() == 0)
    }
}
