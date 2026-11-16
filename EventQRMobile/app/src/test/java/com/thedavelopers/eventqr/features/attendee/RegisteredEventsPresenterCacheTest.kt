package com.thedavelopers.eventqr.features.attendee

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thedavelopers.eventqr.core.api.NetworkResult
import com.thedavelopers.eventqr.core.api.dto.AccountRole
import com.thedavelopers.eventqr.core.api.dto.RegistrationStatus
import com.thedavelopers.eventqr.core.session.SessionManager
import com.thedavelopers.eventqr.features.ShadowEncryptedSharedPreferences
import com.thedavelopers.eventqr.features.ShadowMasterKeys
import com.thedavelopers.eventqr.features.auth.model.dto.LoginResponse
import com.thedavelopers.eventqr.features.registrations.RegistrationsCache
import com.thedavelopers.eventqr.features.registrations.model.dto.RegistrationResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.UUID

/**
 * My passes serves a fresh full `registrations/me` answer instead of refetching it on open; pull-to-refresh,
 * an explicit Retry, a stale answer and an answer belonging to another user still go to the network.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [ShadowMasterKeys::class, ShadowEncryptedSharedPreferences::class])
class RegisteredEventsPresenterCacheTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val start = Instant.parse("2026-01-01T00:00:00Z")
    private var now = start
    private lateinit var session: SessionManager

    private val userA = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val userB = UUID.fromString("22222222-2222-2222-2222-222222222222")

    @Before
    fun setUp() {
        now = start
        RegistrationsCache.clock = { now }
        RegistrationsCache.clear()
        session = SessionManager(context)
        session.clearSession()
        signIn(userA)
    }

    @After
    fun tearDown() {
        RegistrationsCache.clear()
        RegistrationsCache.clock = { Instant.now() }
        session.clearSession()
    }

    private fun signIn(userId: UUID) = session.saveLoginResponse(
        LoginResponse(
            accessToken = "access",
            userId = userId,
            email = "a@test.com",
            fullName = "A",
            role = AccountRole.ATTENDEE,
            refreshToken = "refresh",
        ),
    )

    private inner class CountingRepo(private val items: List<RegistrationResponse>, private val ownerId: String?) :
        AttendeeRepository(context) {
        var calls = 0

        override suspend fun getMyRegistrations(): NetworkResult<List<RegistrationResponse>> {
            calls++
            // Mirrors the real repository: a full answer is what stamps the cache, with its owner.
            RegistrationsCache.set(items, ownerId)
            return NetworkResult.Success(items)
        }
    }

    private class RecordingView : RegisteredEventsContract.View {
        var rows: List<RegistrationResponse>? = null
        override fun showLoading(isLoading: Boolean) = Unit
        override fun showRegisteredEvents(registrations: List<RegistrationResponse>) {
            rows = registrations
        }

        override fun showMessage(message: String) = Unit
    }

    private fun registration(title: String) = RegistrationResponse(
        registrationId = UUID.randomUUID(),
        eventId = UUID.randomUUID(),
        attendeeUserId = userA,
        attendeeEmail = "a@test.com",
        attendeeName = "A",
        status = RegistrationStatus.REGISTERED,
        eventTitle = title,
        eventStartAt = start.plusSeconds(86_400),
        eventEndAt = start.plusSeconds(90_000),
    )

    private fun presenterOf(repo: AttendeeRepository) =
        RegisteredEventsPresenter(RecordingView(), repo, session, CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun aSecondOpenWithinTheTtlIssuesNoRegistrationsRequest_andShowsTheSameRows() {
        val items = listOf(registration("One"), registration("Two"))
        val first = CountingRepo(items, userA.toString())
        presenterOf(first).load()
        assertEquals(1, first.calls)

        val second = CountingRepo(items, userA.toString())
        val view = RecordingView()
        RegisteredEventsPresenter(view, second, session, CoroutineScope(Dispatchers.Unconfined)).load()

        assertEquals("the cached answer is served, not refetched", 0, second.calls)
        assertEquals(2, view.rows?.size)
    }

    @Test
    fun aCachedZeroRegistrationsAnswerIsServed_notRefetched() {
        val empty = emptyList<RegistrationResponse>()
        val first = CountingRepo(empty, userA.toString())
        presenterOf(first).load()
        assertEquals(1, first.calls)

        val second = CountingRepo(empty, userA.toString())
        val view = RecordingView()
        RegisteredEventsPresenter(view, second, session, CoroutineScope(Dispatchers.Unconfined)).load()

        assertEquals("a fetched-zero is a full answer", 0, second.calls)
        assertEquals(0, view.rows?.size)
    }

    @Test
    fun afterTheTtlTheNextOpenFetches() {
        val repo = CountingRepo(listOf(registration("One")), userA.toString())
        presenterOf(repo).load()
        assertEquals(1, repo.calls)

        now = start.plusSeconds(31)
        presenterOf(repo).load()

        assertEquals(2, repo.calls)
    }

    @Test
    fun pullToRefreshAlwaysFetches() {
        val repo = CountingRepo(listOf(registration("One")), userA.toString())
        presenterOf(repo).load()
        assertEquals(1, repo.calls)

        presenterOf(repo).load(forceNetwork = true)

        assertEquals(2, repo.calls)
    }

    @Test
    fun logoutThenLoginClearsTheCacheAndTheNextOpenFetches() {
        val repo = CountingRepo(listOf(registration("One")), userA.toString())
        presenterOf(repo).load()
        assertEquals(1, repo.calls)

        session.clearSession()
        signIn(userA)
        presenterOf(repo).load()

        assertEquals(2, repo.calls)
    }

    @Test
    fun anAnswerStoredForAnotherUserIsNotServed() {
        RegistrationsCache.set(listOf(registration("Someone else's")), userB.toString())

        val repo = CountingRepo(listOf(registration("Mine")), userA.toString())
        presenterOf(repo).load()

        assertEquals(1, repo.calls)
    }
}
