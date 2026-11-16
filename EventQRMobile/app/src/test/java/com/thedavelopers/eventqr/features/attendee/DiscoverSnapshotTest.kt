package com.thedavelopers.eventqr.features.attendee

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thedavelopers.eventqr.core.api.NetworkResult
import com.thedavelopers.eventqr.core.api.dto.AccountRole
import com.thedavelopers.eventqr.core.cache.AttendeeSnapshots
import com.thedavelopers.eventqr.core.cache.LastKnownCache
import com.thedavelopers.eventqr.core.session.SessionManager
import com.thedavelopers.eventqr.features.ShadowEncryptedSharedPreferences
import com.thedavelopers.eventqr.features.ShadowMasterKeys
import com.thedavelopers.eventqr.features.auth.model.dto.LoginResponse
import com.thedavelopers.eventqr.features.events.model.dto.AttendeeEventResponse
import com.thedavelopers.eventqr.features.registrations.RegistrationsCache
import com.thedavelopers.eventqr.features.registrations.model.dto.RegistrationResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Discover with the persisted browse list: the snapshot paints the cards before the network
 * answers, its failure keeps them instead of the error state, and a pull bypasses it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [ShadowMasterKeys::class, ShadowEncryptedSharedPreferences::class])
class DiscoverSnapshotTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val userId = UUID.fromString("aaaaaaaa-5555-5555-5555-555555555555")

    private lateinit var session: SessionManager
    private lateinit var cache: LastKnownCache

    @Before
    fun setUp() {
        RegistrationsCache.clear()
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

    private class RecordingView : EventsContract.View {
        val loading = mutableListOf<Boolean>()
        val errors = mutableListOf<String>()
        val events = mutableListOf<List<AttendeeEventResponse>>()

        override fun showLoading(isLoading: Boolean) {
            loading += isLoading
        }

        override fun showMessage(message: String) = Unit
        override fun showEvents(items: List<AttendeeEventResponse>) {
            events += items
        }

        override fun showRegisteredEventIds(ids: Set<UUID>?) = Unit
        override fun showError(message: String) {
            errors += message
        }
    }

    private fun event(title: String) = AttendeeEventResponse(eventId = UUID.randomUUID(), title = title)

    /** The browse read stays suspended until the test releases it. */
    private inner class GatedRepo(private val result: NetworkResult<List<AttendeeEventResponse>>) :
        AttendeeRepository(context) {
        var calls = 0
        val gate = CompletableDeferred<Unit>()

        override suspend fun getBrowseEvents(): NetworkResult<List<AttendeeEventResponse>> {
            calls++
            gate.await()
            return result
        }

        override suspend fun getMyRegistrations(): NetworkResult<List<RegistrationResponse>> =
            NetworkResult.Success(emptyList())
    }

    private fun seedSnapshot(title: String) = runBlocking {
        cache.write(AttendeeSnapshots.BROWSE_EVENTS, listOf(event(title)))
    }

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    @Test
    fun theSnapshotPaintsBeforeTheApiAnswers_withoutASkeleton() {
        seedSnapshot("Cached fair")
        val view = RecordingView()
        val repo = GatedRepo(NetworkResult.Success(listOf(event("Fresh fair"))))

        EventsPresenter(view, repo, cache).loadEvents()
        awaitUntil { view.events.isNotEmpty() }

        assertEquals("Cached fair", view.events.single().single().title)
        // A painted snapshot means the skeleton is never raised over the cards.
        assertTrue(view.loading.isEmpty())
        // The refresh runs underneath them, exactly once.
        assertEquals(1, repo.calls)
    }

    @Test
    fun theFreshAnswerReplacesTheSnapshot() {
        seedSnapshot("Cached fair")
        val view = RecordingView()
        val repo = GatedRepo(NetworkResult.Success(listOf(event("Fresh fair"))))

        EventsPresenter(view, repo, cache).loadEvents()
        awaitUntil { view.events.isNotEmpty() }

        repo.gate.complete(Unit)
        awaitUntil { view.events.lastOrNull()?.singleOrNull()?.title == "Fresh fair" }

        assertEquals("Fresh fair", view.events.last().single().title)
    }

    @Test
    fun aFailedLoadKeepsThePaintedSnapshot() {
        seedSnapshot("Cached fair")
        val view = RecordingView()
        val repo = GatedRepo(NetworkResult.Error("offline"))
        repo.gate.complete(Unit)

        EventsPresenter(view, repo, cache).loadEvents()
        awaitUntil { view.loading.contains(false) }

        assertEquals("Cached fair", view.events.single().single().title)
        assertTrue("the painted cards are never replaced by the error state", view.errors.isEmpty())
    }

    @Test
    fun pullToRefreshBypassesTheSnapshot() {
        seedSnapshot("Cached fair")
        val view = RecordingView()
        val repo = GatedRepo(NetworkResult.Success(listOf(event("Fresh fair"))))

        EventsPresenter(view, repo, cache).loadEvents(forceNetwork = true)
        awaitUntil { repo.calls == 1 }

        assertTrue(view.events.isEmpty())
        assertEquals(listOf(true), view.loading)
    }

    @Test
    fun anEmptyPersistedListIsAMiss_notAPaintedEmptyState() {
        runBlocking { cache.write(AttendeeSnapshots.BROWSE_EVENTS, emptyList<AttendeeEventResponse>()) }
        val view = RecordingView()
        val repo = GatedRepo(NetworkResult.Success(listOf(event("Fresh fair"))))

        EventsPresenter(view, repo, cache).loadEvents()
        awaitUntil { view.loading.isNotEmpty() }

        // Nothing usable was painted, so the load owns the frame: the skeleton, not an empty state.
        assertEquals(listOf(true), view.loading)
        assertTrue(view.events.isEmpty())

        repo.gate.complete(Unit)
        awaitUntil { view.events.isNotEmpty() }

        assertEquals("Fresh fair", view.events.single().single().title)
    }

    @Test
    fun aFailedLoadWithoutASnapshotStillShowsTheErrorState() {
        val view = RecordingView()
        val repo = GatedRepo(NetworkResult.Error("offline"))
        repo.gate.complete(Unit)

        EventsPresenter(view, repo, cache).loadEvents()
        awaitUntil { view.errors.isNotEmpty() }

        assertNull(view.events.singleOrNull())
        assertEquals(listOf(true, false), view.loading)
    }
}
