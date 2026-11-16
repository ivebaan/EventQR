package com.thedavelopers.eventqr.features.staff

import android.content.Context
import android.os.Looper
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thedavelopers.eventqr.R
import com.thedavelopers.eventqr.core.api.ApiClient
import com.thedavelopers.eventqr.core.api.NetworkResult
import com.thedavelopers.eventqr.core.api.dto.AccountRole
import com.thedavelopers.eventqr.core.api.dto.EventStatus
import com.thedavelopers.eventqr.core.cache.LastKnownCache
import com.thedavelopers.eventqr.core.cache.StaffSnapshots
import com.thedavelopers.eventqr.core.session.SessionManager
import com.thedavelopers.eventqr.features.ShadowEncryptedSharedPreferences
import com.thedavelopers.eventqr.features.ShadowMasterKeys
import com.thedavelopers.eventqr.features.auth.model.dto.LoginResponse
import com.thedavelopers.eventqr.features.staff.model.dto.StaffAssignedEventResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.UUID

/**
 * The assigned-event list against the persisted `staff/assignments` answer: the rows paint before
 * the network replies with no skeleton, the refresh replaces them, a failure keeps them, and a pull
 * invalidates both copies so the first frame is the network's.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [ShadowMasterKeys::class, ShadowEncryptedSharedPreferences::class])
class StaffAssignedEventsSnapshotTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val userId = UUID.fromString("eeeeeeee-1111-1111-1111-111111111111")

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

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            idle()
            Thread.sleep(10)
        }
        idle()
    }

    private fun assignment(title: String): StaffAssignedEventResponse {
        val now = Instant.now()
        return StaffAssignedEventResponse(
            assignmentId = UUID.randomUUID(),
            eventId = UUID.randomUUID(),
            title = title,
            eventStartAt = now.minusSeconds(600),
            eventEndAt = now.plusSeconds(3_600),
            status = EventStatus.ACTIVE,
            canScan = true,
        )
    }

    private fun seedSnapshot(title: String = "Cached meet") = runBlocking {
        cache.write(StaffSnapshots.ASSIGNMENTS, listOf(assignment(title)))
    }

    private inner class GatedRepository : StaffRepository(
        ApiClient.getService(context),
        assignedEventsCache = AssignedEventsCache(),
        lastKnownCache = cache,
    ) {
        var result: NetworkResult<List<StaffAssignedEventResponse>> = NetworkResult.Success(emptyList())
        val gate = CompletableDeferred<Unit>()
        var calls = 0

        override suspend fun getEvents(): NetworkResult<List<StaffAssignedEventResponse>> {
            calls++
            gate.await()
            return result
        }
    }

    private fun buildActivity(repository: StaffRepository): StaffAssignedEventsActivity {
        val controller = Robolectric.buildActivity(StaffAssignedEventsActivity::class.java)
        val activity = controller.get().apply {
            installRepositoryFactory { repository }
            cacheOverride = cache
        }
        controller.create()
        return activity
    }

    private fun StaffAssignedEventsActivity.rowCount(): Int =
        findViewById<RecyclerView>(R.id.recyclerAssignedEvents).adapter?.itemCount ?: 0

    @Test
    fun theSnapshotPaintsTheRowsBeforeTheApiAnswers_withoutASkeleton() {
        seedSnapshot()
        val repository = GatedRepository().apply {
            result = NetworkResult.Success(listOf(assignment("Fresh meet")))
        }
        val activity = buildActivity(repository)

        awaitUntil { activity.rowCount() == 1 }

        assertEquals(1, repository.calls)
        assertEquals(1, activity.rowCount())
        assertEquals(View.GONE, activity.findViewById<View>(R.id.skeletonLoading).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.recyclerAssignedEvents).visibility)
    }

    @Test
    fun theFreshAnswerReplacesTheSnapshot() {
        seedSnapshot()
        val repository = GatedRepository().apply {
            result = NetworkResult.Success(listOf(assignment("Fresh one"), assignment("Fresh two")))
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
            result = NetworkResult.Error("offline")
        }
        val activity = buildActivity(repository)
        awaitUntil { activity.rowCount() == 1 }

        repository.gate.complete(Unit)
        idle()
        idle()

        assertEquals(1, activity.rowCount())
        assertEquals(View.GONE, activity.findViewById<View>(R.id.txtAssignedEventsEmpty).visibility)
    }

    @Test
    fun aFailedLoadWithoutASnapshotStillShowsTheErrorState() {
        val repository = GatedRepository().apply {
            result = NetworkResult.Error("offline")
            gate.complete(Unit)
        }
        val activity = buildActivity(repository)

        awaitUntil { activity.findViewById<View>(R.id.txtAssignedEventsEmpty).visibility == View.VISIBLE }

        assertEquals(0, activity.rowCount())
        assertEquals(View.GONE, activity.findViewById<View>(R.id.skeletonLoading).visibility)
    }

    @Test
    fun pullToRefreshBypassesTheSnapshotAndKeepsItForTheNextColdOpen() {
        seedSnapshot()
        val repository = GatedRepository().apply {
            result = NetworkResult.Success(listOf(assignment("Fresh meet")))
        }
        val activity = buildActivity(repository)

        // A pull replaces the open's load, so the queued snapshot paint never runs.
        activity.loadEvents(showLoading = false, forceNetwork = true)
        awaitUntil { repository.calls == 1 }

        assertEquals("a pull never paints the persisted rows", 0, activity.rowCount())
        // The persisted copy survives the pull: a failed pull still leaves the next cold open a first paint.
        assertNotNull(
            runBlocking {
                cache.read<List<StaffAssignedEventResponse>>(
                    StaffSnapshots.ASSIGNMENTS,
                    StaffSnapshots.ASSIGNMENTS_TYPE,
                    StaffSnapshots.MAX_AGE_MS,
                )
            },
        )
    }
}
