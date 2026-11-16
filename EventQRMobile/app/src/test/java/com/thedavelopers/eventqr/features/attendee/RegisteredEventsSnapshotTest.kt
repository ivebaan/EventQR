package com.thedavelopers.eventqr.features.attendee

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thedavelopers.eventqr.core.api.NetworkResult
import com.thedavelopers.eventqr.core.api.dto.AccountRole
import com.thedavelopers.eventqr.core.api.dto.RegistrationStatus
import com.thedavelopers.eventqr.core.cache.AttendeeSnapshots
import com.thedavelopers.eventqr.core.cache.LastKnownCache
import com.thedavelopers.eventqr.core.session.SessionManager
import com.thedavelopers.eventqr.features.ShadowEncryptedSharedPreferences
import com.thedavelopers.eventqr.features.ShadowMasterKeys
import com.thedavelopers.eventqr.features.auth.model.dto.LoginResponse
import com.thedavelopers.eventqr.features.registrations.RegistrationsCache
import com.thedavelopers.eventqr.features.registrations.model.dto.RegistrationResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.UUID

/**
 * My passes with the persisted list: the snapshot paints before the network answers (no skeleton),
 * the fresh answer replaces it, and a failure keeps whatever was painted. Pull-to-refresh bypasses
 * the snapshot, and a registration change drops it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [ShadowMasterKeys::class, ShadowEncryptedSharedPreferences::class])
class RegisteredEventsSnapshotTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val userId = UUID.fromString("aaaaaaaa-2222-2222-2222-222222222222")

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

    private class RecordingView : RegisteredEventsContract.View {
        val loading = mutableListOf<Boolean>()
        var rows: List<RegistrationResponse>? = null
        var message: String? = null

        override fun showLoading(isLoading: Boolean) {
            loading += isLoading
        }

        override fun showRegisteredEvents(registrations: List<RegistrationResponse>) {
            rows = registrations
        }

        override fun showMessage(message: String) {
            this.message = message
        }
    }

    /** The registrations read stays suspended until the test releases it. */
    private inner class GatedRepo(private val result: NetworkResult<List<RegistrationResponse>>) :
        AttendeeRepository(context) {
        var calls = 0
        val gate = CompletableDeferred<Unit>()

        override suspend fun getMyRegistrations(): NetworkResult<List<RegistrationResponse>> {
            calls++
            gate.await()
            return result
        }
    }

    private fun registration(title: String) = RegistrationResponse(
        registrationId = UUID.randomUUID(),
        eventId = UUID.randomUUID(),
        attendeeUserId = userId,
        attendeeEmail = "a@test.com",
        attendeeName = "Ada Lovelace",
        status = RegistrationStatus.REGISTERED,
        eventTitle = title,
        eventStartAt = Instant.now().plusSeconds(86_400),
        eventEndAt = Instant.now().plusSeconds(90_000),
    )

    private fun seedSnapshot(title: String) = runBlocking {
        cache.write(AttendeeSnapshots.REGISTRATIONS, listOf(registration(title)))
    }

    private fun presenter(view: RecordingView, repo: AttendeeRepository) =
        RegisteredEventsPresenter(view, repo, session, CoroutineScope(Dispatchers.Unconfined), cache)

    /** The snapshot read hops to an IO thread, so wait for the paint instead of assuming its timing. */
    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
    }

    @Test
    fun theSnapshotPaintsBeforeTheApiAnswers_withoutASkeleton() {
        seedSnapshot("Cached pass")
        val view = RecordingView()
        val repo = GatedRepo(NetworkResult.Success(listOf(registration("Fresh pass"))))

        presenter(view, repo).load()
        awaitUntil { view.rows != null }

        assertEquals("Cached pass", view.rows?.single()?.eventTitle)
        // The paint stops the loading state instead of raising it: no skeleton over the rows.
        assertEquals(listOf(false), view.loading)
        // The refresh runs underneath the painted rows, exactly once.
        assertEquals(1, repo.calls)
    }

    @Test
    fun theFreshAnswerReplacesTheSnapshot() {
        seedSnapshot("Cached pass")
        val view = RecordingView()
        val repo = GatedRepo(NetworkResult.Success(listOf(registration("Fresh pass"))))

        presenter(view, repo).load()
        awaitUntil { view.rows?.single()?.eventTitle == "Cached pass" }

        repo.gate.complete(Unit)
        awaitUntil { view.rows?.single()?.eventTitle == "Fresh pass" }

        assertEquals("Fresh pass", view.rows?.single()?.eventTitle)
        assertEquals(1, repo.calls)
    }

    @Test
    fun aFailedRefreshKeepsThePaintedSnapshot() {
        seedSnapshot("Cached pass")
        val view = RecordingView()
        val repo = GatedRepo(NetworkResult.Error("offline"))

        presenter(view, repo).load()
        awaitUntil { view.rows?.single()?.eventTitle == "Cached pass" }

        repo.gate.complete(Unit)
        awaitUntil { view.message != null }

        assertEquals("Cached pass", view.rows?.single()?.eventTitle)
    }

    @Test
    fun anEmptyPersistedListIsAMiss_notAPaintedEmptyState() {
        runBlocking { cache.write(AttendeeSnapshots.REGISTRATIONS, emptyList<RegistrationResponse>()) }
        val view = RecordingView()
        val repo = GatedRepo(NetworkResult.Success(listOf(registration("Fresh pass"))))

        presenter(view, repo).load()
        awaitUntil { view.loading.isNotEmpty() }

        // Nothing usable was painted: the skeleton covers the load, and no empty state is claimed.
        assertEquals(listOf(true), view.loading)
        assertNull(view.rows)

        repo.gate.complete(Unit)
        awaitUntil { view.rows?.single()?.eventTitle == "Fresh pass" }

        assertEquals("Fresh pass", view.rows?.single()?.eventTitle)
    }

    @Test
    fun aFailedLoadWithoutASnapshotKeepsTodaysSkeletonThenMessage() {
        val view = RecordingView()
        val repo = GatedRepo(NetworkResult.Error("offline"))
        repo.gate.complete(Unit)

        presenter(view, repo).load()
        awaitUntil { view.message != null }

        assertNull(view.rows)
        assertEquals(listOf(true, false), view.loading)
    }

    @Test
    fun pullToRefreshBypassesTheSnapshot() {
        seedSnapshot("Cached pass")
        val view = RecordingView()
        val repo = GatedRepo(NetworkResult.Success(listOf(registration("Fresh pass"))))

        presenter(view, repo).load(forceNetwork = true)
        awaitUntil { repo.calls == 1 }

        assertNull("a pull always asks the network", view.rows)
        assertEquals(listOf(true), view.loading)
    }

    @Test
    fun aRegistrationChangeDropsTheSnapshot_soTheNextOpenFetches() {
        seedSnapshot("Cached pass")
        runBlocking { AttendeeRepository(context).invalidateRegistrationSnapshots() }

        assertNull(
            runBlocking {
                cache.read<List<RegistrationResponse>>(
                    AttendeeSnapshots.REGISTRATIONS,
                    AttendeeSnapshots.REGISTRATIONS_TYPE,
                    AttendeeSnapshots.MAX_AGE_MS,
                )
            },
        )

        val view = RecordingView()
        val repo = GatedRepo(NetworkResult.Success(listOf(registration("Fresh pass"))))
        presenter(view, repo).load()
        awaitUntil { repo.calls == 1 }

        assertNull(view.rows)
        assertEquals(listOf(true), view.loading)
    }
}
