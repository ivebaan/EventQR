package com.thedavelopers.eventqr.features.organizer.staff

import android.content.Context
import android.content.Intent
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
import com.thedavelopers.eventqr.features.organizer.EXTRA_EVENT_ID
import com.thedavelopers.eventqr.features.organizer.OrganizerEventsCache
import com.thedavelopers.eventqr.features.organizer.OrganizerMvpDataSource
import com.thedavelopers.eventqr.features.organizer.OrganizerMvpEvent
import com.thedavelopers.eventqr.features.organizer.OrganizerMvpLoad
import com.thedavelopers.eventqr.features.organizer.OrganizerMvpStaff
import com.thedavelopers.eventqr.features.organizer.OrganizerRepository
import com.thedavelopers.eventqr.features.organizer.allTexts
import com.thedavelopers.eventqr.features.organizer.model.dto.OrganizerStaffDto
import com.thedavelopers.eventqr.features.organizer.testOrganizerEvent
import com.thedavelopers.eventqr.ui.components.EventQrEmptyState
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
import java.util.UUID

/**
 * The staff roster against the persisted `organizer/staff/{eventId}` list: the rows paint before the
 * network replies, the fresh answer replaces them, a failure keeps them (with the in-flow strip), a
 * pull bypasses the snapshot and a failure with nothing painted is today's error state.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [ShadowMasterKeys::class, ShadowEncryptedSharedPreferences::class])
class ManageUsersSnapshotTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val userId = UUID.fromString("cccccccc-2222-2222-2222-222222222222")

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

    private fun dto(name: String) = OrganizerStaffDto(
        assignmentId = UUID.randomUUID(),
        eventId = UUID.fromString(event.id),
        staffUserId = UUID.randomUUID(),
        name = name,
        email = "$name@test.com",
        roleLabel = "Scanner",
        active = true,
    )

    private fun staff(name: String) = OrganizerMvpStaff(
        id = UUID.randomUUID().toString(),
        name = name,
        email = "$name@test.com",
        assignedEventId = event.id,
        assignedEvent = event.title,
        roleLabel = "Scanner",
        accessStatus = "Active",
        addedDate = "-",
        permissions = listOf("Scan QR"),
    )

    private fun seed(names: List<String>) = runBlocking {
        cache.write(OrganizerSnapshots.staff(event.id), names.map(::staff))
    }

    private inner class GatedRepository : OrganizerRepository(
        context,
        eventsCache = OrganizerEventsCache(),
        lastKnownCache = cache,
    ) {
        var result: NetworkResult<List<OrganizerStaffDto>> = NetworkResult.Error("unset")
        val gate = CompletableDeferred<Unit>()
        var calls = 0

        override suspend fun loadApprovedOrganizerEvents(): OrganizerMvpLoad<List<OrganizerMvpEvent>> =
            OrganizerMvpLoad(listOf(event), OrganizerMvpDataSource.BACKEND)

        override suspend fun fetchOrganizerStaff(eventId: String): NetworkResult<List<OrganizerStaffDto>> {
            calls++
            gate.await()
            return result
        }
    }

    private fun buildActivity(repository: OrganizerRepository): ManageUsersActivity {
        val intent = Intent(context, ManageUsersActivity::class.java).putExtra(EXTRA_EVENT_ID, event.id)
        val controller = Robolectric.buildActivity(ManageUsersActivity::class.java, intent)
        val activity = controller.get().apply {
            installRepositoryFactory { repository }
            cacheOverride = cache
        }
        controller.create()
        return activity
    }

    private fun ManageUsersActivity.rowCount(): Int =
        findViewById<RecyclerView>(R.id.recyclerStaffAssignment)?.adapter?.itemCount ?: 0

    private fun ManageUsersActivity.texts(): List<String> = window.decorView.allTexts()

    @Test
    fun theSnapshotPaintsTheRosterBeforeTheApiAnswers() {
        seed(listOf("Cached Ana"))
        val repository = GatedRepository()
        val activity = buildActivity(repository)

        awaitUntil { activity.rowCount() == 1 }

        assertEquals(1, repository.calls)
        assertEquals(1, activity.rowCount())
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.recyclerStaffAssignment).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.errorStaffAssignment).visibility)
    }

    @Test
    fun theFreshAnswerReplacesTheSnapshot() {
        seed(listOf("Cached Ana"))
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
    fun aFailedRefreshKeepsThePaintedRosterAndReportsInTheStrip() {
        seed(listOf("Cached Ana"))
        val repository = GatedRepository().apply { result = NetworkResult.Error("offline") }
        val activity = buildActivity(repository)
        awaitUntil { activity.rowCount() == 1 }

        repository.gate.complete(Unit)
        idle()
        idle()

        assertEquals(1, activity.rowCount())
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.errorStaffAssignment).visibility)
        assertTrue(activity.texts().contains("offline"))
    }

    @Test
    fun aFailedLoadWithoutASnapshotShowsTheErrorState() {
        val repository = GatedRepository().apply {
            result = NetworkResult.Error("offline")
            gate.complete(Unit)
        }
        val activity = buildActivity(repository)

        awaitUntil { activity.findViewById<EventQrEmptyState>(R.id.txtEmptyStaff).visibility == View.VISIBLE }

        val empty = activity.findViewById<EventQrEmptyState>(R.id.txtEmptyStaff)
        assertEquals(0, activity.rowCount())
        assertEquals(activity.getString(R.string.ui_staff_load_failed), empty.text.toString())
        assertTrue(activity.texts().contains(activity.getString(R.string.common_retry)))
    }

    @Test
    fun pullToRefreshBypassesTheSnapshot() {
        val repository = GatedRepository()
        val activity = buildActivity(repository)
        awaitUntil { repository.calls == 1 }

        seed(listOf("Cached Ana"))
        activity.loadAssigned(forceNetwork = true)
        idle()

        assertEquals(2, repository.calls)
        assertEquals("a pull never paints the persisted roster", 0, activity.rowCount())
    }
}
