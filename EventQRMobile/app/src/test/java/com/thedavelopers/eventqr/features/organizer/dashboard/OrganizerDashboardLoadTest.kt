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
import com.thedavelopers.eventqr.core.api.NetworkResult
import com.thedavelopers.eventqr.core.api.dto.NotificationStatus
import com.thedavelopers.eventqr.core.api.dto.NotificationType
import com.thedavelopers.eventqr.features.ShadowEncryptedSharedPreferences
import com.thedavelopers.eventqr.features.ShadowMasterKeys
import com.thedavelopers.eventqr.features.notifications.model.dto.NotificationResponse
import com.thedavelopers.eventqr.features.organizer.OrganizerEventsCache
import com.thedavelopers.eventqr.features.organizer.OrganizerMvpDataSource
import com.thedavelopers.eventqr.features.organizer.OrganizerMvpEvent
import com.thedavelopers.eventqr.features.organizer.OrganizerMvpLoad
import com.thedavelopers.eventqr.features.organizer.OrganizerRepository
import com.thedavelopers.eventqr.features.organizer.model.dto.OrganizerEventDto
import com.thedavelopers.eventqr.features.organizer.testOrganizerEvent
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * The dashboard's load order: notifications start with the events read (never after it), events alone paint the
 * screen, the resume path reuses the 60 s events snapshot, and the session refresh never blocks the first paint.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [ShadowMasterKeys::class, ShadowEncryptedSharedPreferences::class])
class OrganizerDashboardLoadTest {

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /**
     * The persisted snapshot read hops to an IO thread before the network starts, so wait for the
     * pipeline instead of assuming it settled inside one idling pass.
     */
    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            idle()
            Thread.sleep(10)
        }
        idle()
    }

    /** Lets every queued continuation land when there is no counter to wait on. */
    private fun settle() = repeat(10) {
        idle()
        Thread.sleep(20)
    }

    private fun unread() = NotificationResponse(
        notificationId = UUID.randomUUID(),
        recipientUserId = UUID.randomUUID(),
        title = "Decision",
        message = "Approved",
        status = NotificationStatus.SENT,
    )

    @Test
    fun notificationsStartWithTheEventsRead_andEventsAlonePaintTheScreen() {
        val repository = GatedRepository(ApplicationProvider.getApplicationContext())
        val controller = Robolectric.buildActivity(OrganizerDashboardActivity::class.java)
        val activity = controller.get().apply {
            installRepositoryFactory { repository }
            sessionRefresh = { }
        }
        controller.create()
        awaitUntil { repository.started.contains("events") && repository.started.contains("notifications") }

        // Both reads are in flight at once: the bell's read started before the events answer.
        assertEquals(setOf("notifications", "events"), repository.started.toSet())

        repository.events.complete(
            OrganizerMvpLoad(listOf(testOrganizerEvent()), OrganizerMvpDataSource.BACKEND),
        )
        awaitUntil { activity.findViewById<View>(R.id.skeletonLoading).visibility == View.GONE }

        assertTrue(
            "notifications must start before the events answer",
            repository.started.indexOf("notifications") < repository.started.indexOf("events:done"),
        )
        assertEquals(View.GONE, activity.findViewById<View>(R.id.skeletonLoading).visibility)
        assertEquals(3, activity.findViewById<LinearLayout>(R.id.metricRowsContainer).childCount)
        assertFalse("content must paint without the notifications", repository.started.contains("notifications:done"))

        repository.notifications.complete(NetworkResult.Success(listOf(unread())))
        awaitUntil { activity.findViewById<TextView>(R.id.passHeadBellBadge).visibility == View.VISIBLE }

        val badge = activity.findViewById<TextView>(R.id.passHeadBellBadge)
        assertEquals(View.VISIBLE, badge.visibility)
        assertEquals("1", badge.text.toString())
    }

    @Test
    fun aSecondResumeWithinTheWindow_makesNoEventsCall() {
        val repository = CachedRepository(ApplicationProvider.getApplicationContext(), OrganizerEventsCache())
        val controller = Robolectric.buildActivity(OrganizerDashboardActivity::class.java)
        controller.get().apply {
            installRepositoryFactory { repository }
            sessionRefresh = { }
        }
        val activity = controller.create().start().resume().get()
        awaitUntil { repository.fetches == 1 }
        settle()

        controller.pause().resume()
        settle()
        controller.pause().resume()
        settle()

        assertEquals(2, activity.resumeReloads)
        assertEquals(1, repository.fetches)
    }

    @Test
    fun theFirstPaintNeverWaitsForTheSessionRefresh() {
        val gate = CompletableDeferred<Unit>()
        var refreshes = 0
        val repository = CachedRepository(ApplicationProvider.getApplicationContext(), OrganizerEventsCache())
        val controller = Robolectric.buildActivity(OrganizerDashboardActivity::class.java)
        val activity = controller.get().apply {
            installRepositoryFactory { repository }
            sessionRefresh = { refreshes++; gate.await() }
        }
        controller.create()
        awaitUntil { activity.findViewById<LinearLayout>(R.id.metricRowsContainer).childCount == 3 }

        // The refresh is still in flight, yet the events answered and painted.
        assertEquals(1, refreshes)
        assertEquals(1, repository.fetches)
        assertEquals(3, activity.findViewById<LinearLayout>(R.id.metricRowsContainer).childCount)

        gate.complete(Unit)
        idle()
    }

    @Test
    fun aReloadReplacesTheInFlightOne_soAnOlderReadNeverRepaints() {
        val repository = ReplacingRepository(ApplicationProvider.getApplicationContext())
        val controller = Robolectric.buildActivity(OrganizerDashboardActivity::class.java)
        val activity = controller.get().apply {
            installRepositoryFactory { repository }
            sessionRefresh = { }
        }
        controller.create()
        awaitUntil { repository.gates.size == 1 }

        // Swipe/retry while the first read is still in flight.
        activity.loadDashboard()
        awaitUntil { repository.gates.size == 2 }

        repository.gates[0].complete(upcoming("Stale"))
        idle()
        val host = activity.findViewById<ViewGroup>(R.id.nextEventHost)
        assertNull("the replaced read must not paint", host.findViewById<TextView>(R.id.listRowTitle))

        repository.gates[1].complete(upcoming("Fresh"))
        awaitUntil { host.findViewById<TextView>(R.id.listRowTitle) != null }
        assertEquals("Fresh", host.findViewById<TextView>(R.id.listRowTitle).text.toString())
    }

    private fun upcoming(title: String): OrganizerMvpLoad<List<OrganizerMvpEvent>> {
        val now = java.time.Instant.now()
        return OrganizerMvpLoad(
            listOf(
                testOrganizerEvent(title).copy(
                    eventStartAt = now.plusSeconds(3_600),
                    eventEndAt = now.plusSeconds(7_200),
                ),
            ),
            OrganizerMvpDataSource.BACKEND,
        )
    }

    /** One gate per read, so the test can land an old answer after a newer load replaced it. */
    private class ReplacingRepository(context: Context) :
        OrganizerRepository(context, currentUserId = { "user-1" }) {
        val gates = mutableListOf<CompletableDeferred<OrganizerMvpLoad<List<OrganizerMvpEvent>>>>()

        override suspend fun loadEventsForMvp(): OrganizerMvpLoad<List<OrganizerMvpEvent>> {
            val gate = CompletableDeferred<OrganizerMvpLoad<List<OrganizerMvpEvent>>>()
            gates += gate
            return gate.await()
        }

        override suspend fun getMyNotifications(
            eventId: UUID?,
            notificationType: NotificationType?,
        ): NetworkResult<List<NotificationResponse>> = NetworkResult.Success(emptyList())
    }

    /** Two gates, so the test can tell "started" from "finished" for each read independently. */
    private class GatedRepository(context: Context) :
        OrganizerRepository(context, currentUserId = { "user-1" }) {
        val events = CompletableDeferred<OrganizerMvpLoad<List<OrganizerMvpEvent>>>()
        val notifications = CompletableDeferred<NetworkResult<List<NotificationResponse>>>()
        val started = mutableListOf<String>()

        override suspend fun loadEventsForMvp(): OrganizerMvpLoad<List<OrganizerMvpEvent>> {
            started += "events"
            return events.await().also { started += "events:done" }
        }

        override suspend fun getMyNotifications(
            eventId: UUID?,
            notificationType: NotificationType?,
        ): NetworkResult<List<NotificationResponse>> {
            started += "notifications"
            return notifications.await().also { started += "notifications:done" }
        }
    }

    /** Real loader and real cache behind a stubbed events fetch, so the cache decides whether a fetch happens. */
    private class CachedRepository(context: Context, cache: OrganizerEventsCache) :
        OrganizerRepository(context, eventsCache = cache, currentUserId = { "user-1" }) {
        var fetches = 0

        override suspend fun fetchOrganizerEvents(): NetworkResult<List<OrganizerEventDto>> {
            fetches++
            return NetworkResult.Success(
                listOf(OrganizerEventDto(eventId = UUID.randomUUID(), title = "Fest", status = "Approved")),
            )
        }

        override suspend fun getMyNotifications(
            eventId: UUID?,
            notificationType: NotificationType?,
        ): NetworkResult<List<NotificationResponse>> = NetworkResult.Success(emptyList())
    }
}
