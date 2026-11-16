package com.thedavelopers.eventqr.features.organizer.attendees

import android.content.Context
import android.os.Looper
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thedavelopers.eventqr.R
import com.thedavelopers.eventqr.core.api.NetworkResult
import com.thedavelopers.eventqr.core.api.dto.AccountRole
import com.thedavelopers.eventqr.core.cache.LastKnownCache
import com.thedavelopers.eventqr.core.cache.OrganizerSnapshots
import com.thedavelopers.eventqr.core.session.SessionManager
import com.thedavelopers.eventqr.features.ShadowEncryptedSharedPreferences
import com.thedavelopers.eventqr.features.ShadowMasterKeys
import com.thedavelopers.eventqr.features.auth.model.dto.LoginResponse
import com.thedavelopers.eventqr.features.organizer.OrganizerEventsCache
import com.thedavelopers.eventqr.features.organizer.OrganizerMvpAttendee
import com.thedavelopers.eventqr.features.organizer.OrganizerMvpDataSource
import com.thedavelopers.eventqr.features.organizer.OrganizerMvpEvent
import com.thedavelopers.eventqr.features.organizer.OrganizerMvpLoad
import com.thedavelopers.eventqr.features.organizer.OrganizerRepository
import com.thedavelopers.eventqr.features.organizer.model.dto.OrganizerAttendeeDto
import com.thedavelopers.eventqr.features.organizer.testOrganizerEvent
import com.thedavelopers.eventqr.ui.components.EventQrEmptyState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * The attendee list against the persisted `organizer/attendees/{eventId}` answer: the rows paint
 * before the network replies with no skeleton, the refresh replaces them, a failure keeps them, a
 * pull bypasses the snapshot, and another event's snapshot is never served.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [ShadowMasterKeys::class, ShadowEncryptedSharedPreferences::class])
class AttendeeManagementSnapshotTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val userId = UUID.fromString("dddddddd-1111-1111-1111-111111111111")

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

    private fun dto(name: String, eventId: String = event.id) = OrganizerAttendeeDto(
        registrationId = UUID.randomUUID(),
        eventId = UUID.fromString(eventId),
        name = name,
        email = "a@test.com",
    )

    private fun attendee(name: String, eventId: String = event.id) = OrganizerMvpAttendee(
        id = UUID.randomUUID().toString(),
        eventId = eventId,
        name = name,
        email = "a@test.com",
        phone = "-",
        registrationStatus = "Registered",
        currentEventStatus = "Registered",
        points = 0,
        lastTransactionTime = "-",
        registeredDate = "-",
        qrCredentialStatus = "Issued",
        recentTransactions = emptyList(),
        recentRejectedScans = emptyList(),
    )

    private fun seed(rows: List<OrganizerMvpAttendee>) = runBlocking {
        cache.write(OrganizerSnapshots.attendees(event.id), rows)
    }

    private inner class GatedRepository(private val served: OrganizerMvpEvent = event) : OrganizerRepository(
        context,
        eventsCache = OrganizerEventsCache(),
        lastKnownCache = cache,
    ) {
        var result: NetworkResult<List<OrganizerAttendeeDto>> = NetworkResult.Error("unset")
        val gate = CompletableDeferred<Unit>()
        var calls = 0

        override suspend fun loadApprovedOrganizerEvents(): OrganizerMvpLoad<List<OrganizerMvpEvent>> =
            OrganizerMvpLoad(listOf(served), OrganizerMvpDataSource.BACKEND)

        override suspend fun fetchOrganizerAttendees(eventId: String): NetworkResult<List<OrganizerAttendeeDto>> {
            calls++
            gate.await()
            return result
        }
    }

    private fun buildActivity(repository: OrganizerRepository): AttendeeManagementActivity {
        val controller = Robolectric.buildActivity(AttendeeManagementActivity::class.java)
        val activity = controller.get().apply {
            installRepositoryFactory { repository }
            cacheOverride = cache
        }
        controller.create()
        return activity
    }

    private fun AttendeeManagementActivity.rowCount(): Int =
        findViewById<RecyclerView>(R.id.recyclerAttendees)?.adapter?.itemCount ?: 0

    @Test
    fun theSnapshotPaintsTheRowsBeforeTheApiAnswers_withoutASkeleton() {
        seed(listOf(attendee("Cached Ana")))
        val repository = GatedRepository()
        val activity = buildActivity(repository)

        awaitUntil { activity.rowCount() == 1 }

        assertEquals(1, repository.calls)
        assertEquals(1, activity.rowCount())
        assertEquals(View.GONE, activity.findViewById<View>(R.id.skeletonLoading).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.recyclerAttendees).visibility)
    }

    @Test
    fun theFreshAnswerReplacesTheSnapshot() {
        seed(listOf(attendee("Cached Ana")))
        val repository = GatedRepository().apply {
            result = NetworkResult.Success(listOf(dto("Fresh one"), dto("Fresh two")))
        }
        val activity = buildActivity(repository)
        awaitUntil { activity.rowCount() == 1 }

        repository.gate.complete(Unit)
        awaitUntil { activity.rowCount() == 2 }

        assertEquals(2, activity.rowCount())
    }

    @Test
    fun aFailedRefreshKeepsThePaintedRows() {
        seed(listOf(attendee("Cached Ana")))
        val repository = GatedRepository().apply { result = NetworkResult.Error("offline") }
        val activity = buildActivity(repository)
        awaitUntil { activity.rowCount() == 1 }

        repository.gate.complete(Unit)
        idle()
        idle()

        assertEquals(1, activity.rowCount())
        assertEquals(View.GONE, activity.findViewById<View>(R.id.layoutAttendeesEmpty).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.skeletonLoading).visibility)
    }

    @Test
    fun aFailedLoadWithoutASnapshotStillShowsTheErrorState() {
        val repository = GatedRepository().apply {
            result = NetworkResult.Error("offline")
            gate.complete(Unit)
        }
        val activity = buildActivity(repository)

        awaitUntil { activity.findViewById<EventQrEmptyState>(R.id.layoutAttendeesEmpty).visibility == View.VISIBLE }

        assertEquals(0, activity.rowCount())
        val empty = activity.findViewById<EventQrEmptyState>(R.id.layoutAttendeesEmpty)
        assertEquals(View.VISIBLE, empty.visibility)
        assertEquals(activity.getString(R.string.attendee_management_unable_to_load_attendees), empty.text.toString())
        assertEquals("offline", empty.subtext.toString())
        assertEquals(View.GONE, activity.findViewById<View>(R.id.skeletonLoading).visibility)
    }

    @Test
    fun pullToRefreshBypassesTheSnapshot() {
        val repository = GatedRepository()
        val activity = buildActivity(repository)
        // The open's fetch is in flight and nothing has been painted yet.
        awaitUntil { repository.calls == 1 }

        seed(listOf(attendee("Cached Ana")))
        activity.loadAttendees(forceNetwork = true)
        idle()

        assertEquals(2, repository.calls)
        assertEquals("a pull never paints the persisted rows", 0, activity.rowCount())
    }

    @Test
    fun anotherEventsSnapshotIsNeverPainted() {
        seed(listOf(attendee("Cached Ana")))
        val otherEvent = testOrganizerEvent("Other fest")
        val repository = GatedRepository(served = otherEvent)
        val activity = buildActivity(repository)

        awaitUntil { repository.calls == 1 }
        idle()

        assertEquals("the other event's snapshot was served", 0, activity.rowCount())
    }

}
