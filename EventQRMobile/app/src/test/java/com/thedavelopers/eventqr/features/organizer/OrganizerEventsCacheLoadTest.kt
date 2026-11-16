package com.thedavelopers.eventqr.features.organizer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thedavelopers.eventqr.core.api.ApiService
import com.thedavelopers.eventqr.core.api.NetworkResult
import com.thedavelopers.eventqr.core.api.dto.AccountRole
import com.thedavelopers.eventqr.core.api.sharedGson
import com.thedavelopers.eventqr.core.session.SessionManager
import com.thedavelopers.eventqr.features.ShadowEncryptedSharedPreferences
import com.thedavelopers.eventqr.features.ShadowMasterKeys
import com.thedavelopers.eventqr.features.auth.model.dto.LoginResponse
import com.thedavelopers.eventqr.features.events.model.dto.EventRequest
import com.thedavelopers.eventqr.features.organizer.model.dto.OrganizerEventDto
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.UUID

/**
 * The organizer list is one process-wide, user-scoped snapshot: a second screen opened inside the 60 s window
 * spends no second `GET organizer/events`, an expired one refetches, another user never sees it, a failure is never
 * stored, a read that lands after an invalidation is dropped, and an organizer mutation, a pull-to-refresh or a
 * session change drop it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [ShadowMasterKeys::class, ShadowEncryptedSharedPreferences::class])
class OrganizerEventsCacheLoadTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ApiService

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(GsonConverterFactory.create(sharedGson()))
            .build()
            .create(ApiService::class.java)
        OrganizerEventsCache.shared.clear()
    }

    @After
    fun tearDown() {
        server.shutdown()
        OrganizerEventsCache.shared.clear()
    }

    private fun json(body: String) = MockResponse().setBody(body).setHeader("Content-Type", "application/json")

    private fun eventsBody(vararg titles: String): String {
        val rows = titles.joinToString(",") {
            """{"eventId":"${UUID.randomUUID()}","title":"$it","status":"Approved"}"""
        }
        return """{"success":true,"data":[$rows]}"""
    }

    private fun repository(
        cache: OrganizerEventsCache = OrganizerEventsCache.shared,
        userId: () -> String? = { "user-1" },
    ): OrganizerRepository = OrganizerRepository(
        ApplicationProvider.getApplicationContext(),
        apiService = api,
        eventsCache = cache,
        currentUserId = userId,
    )

    @Test
    fun secondOpenWithinTheWindow_makesNoSecondEventsCall() = runBlocking {
        server.enqueue(json(eventsBody("Fest")))

        val repository = repository()
        repository.loadApprovedOrganizerEvents()
        val second = repository.loadEventsForMvp()

        assertEquals(1, server.requestCount)
        assertEquals(listOf("Fest"), second.data.map { it.title })
    }

    @Test
    fun afterTheWindow_theListIsFetchedAgain() = runBlocking {
        var now = 0L
        val cache = OrganizerEventsCache(clock = { now })
        server.enqueue(json(eventsBody("First")))
        server.enqueue(json(eventsBody("Second")))
        val repository = repository(cache)

        repository.loadEventsForMvp()
        now += OrganizerEventsCache.DEFAULT_TTL_MS - 1
        assertEquals(listOf("First"), repository.loadEventsForMvp().data.map { it.title })
        assertEquals(1, server.requestCount)

        now += 1
        assertEquals(listOf("Second"), repository.loadEventsForMvp().data.map { it.title })
        assertEquals(2, server.requestCount)
    }

    @Test
    fun anotherUserNeverGetsTheFirstUsersRows() = runBlocking {
        val cache = OrganizerEventsCache()
        server.enqueue(json(eventsBody("Mine")))
        server.enqueue(json(eventsBody("Theirs")))

        assertEquals(listOf("Mine"), repository(cache) { "user-1" }.loadEventsForMvp().data.map { it.title })
        assertEquals(listOf("Theirs"), repository(cache) { "user-2" }.loadEventsForMvp().data.map { it.title })
        assertEquals(2, server.requestCount)
    }

    @Test
    fun anOrganizerMutationDropsTheSnapshot() = runBlocking {
        val eventId = UUID.randomUUID()
        server.enqueue(json(eventsBody("Fest")))
        server.enqueue(json("""{"success":true,"data":{"eventId":"$eventId","title":"Fest","status":"Approved"}}"""))
        server.enqueue(json(eventsBody("Fest")))
        val repository = repository()

        repository.loadEventsForMvp()
        repository.updateOrganizerEvent(
            eventId.toString(),
            EventRequest(title = "Fest", capacity = 10, rewardsEnabled = false),
        )
        repository.loadEventsForMvp()

        assertEquals(3, server.requestCount)
    }

    @Test
    fun pullToRefreshBypassesTheSnapshot() = runBlocking {
        server.enqueue(json(eventsBody("Fest")))
        server.enqueue(json(eventsBody("Fest")))
        val repository = repository()

        repository.loadEventsForMvp()
        repository.invalidateEvents()
        repository.loadEventsForMvp()

        assertEquals(2, server.requestCount)
    }

    @Test
    fun aReadThatLandedAfterAnInvalidation_isNeverStored() = runBlocking {
        val cache = OrganizerEventsCache()
        // The invalidation lands while this read is in flight, like a pull-to-refresh during a load.
        val repository = FetchingRepository(ApplicationProvider.getApplicationContext(), cache, invalidateDuringFetch = true)

        repository.loadEventsForMvp()
        assertNull(cache.get("user-1"))

        repository.loadEventsForMvp()
        assertEquals(2, repository.fetches)
    }

    @Test
    fun aFailedFetchIsNeverStored() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"success":false,"message":"boom"}"""))
        server.enqueue(json(eventsBody("Fest")))
        val repository = repository()

        assertEquals(OrganizerMvpDataSource.ERROR, repository.loadEventsForMvp().source)
        assertEquals(listOf("Fest"), repository.loadEventsForMvp().data.map { it.title })
        assertEquals(2, server.requestCount)

        // Only the success was stored: the next read is a cache hit.
        assertEquals(listOf("Fest"), repository.loadEventsForMvp().data.map { it.title })
        assertEquals(2, server.requestCount)
    }

    @Test
    fun loginAndLogoutDropTheSnapshot() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        server.enqueue(json(eventsBody("A")))
        server.enqueue(json(eventsBody("B")))
        server.enqueue(json(eventsBody("C")))
        server.enqueue(json(eventsBody("D")))
        val repository = OrganizerRepository(context, apiService = api)

        SessionManager(context).saveLoginResponse(
            LoginResponse(
                accessToken = "a",
                userId = UUID.randomUUID(),
                email = "a@eventqr.test",
                fullName = "A",
                role = AccountRole.ORGANIZER,
            ),
        )
        repository.loadEventsForMvp()
        assertEquals(listOf("A"), repository.loadEventsForMvp().data.map { it.title })
        assertEquals(1, server.requestCount)

        // Account switch: the previous user's rows must not survive.
        SessionManager(context).saveLoginResponse(
            LoginResponse(
                accessToken = "b",
                userId = UUID.randomUUID(),
                email = "b@eventqr.test",
                fullName = "B",
                role = AccountRole.ORGANIZER,
            ),
        )
        assertEquals(listOf("B"), repository.loadEventsForMvp().data.map { it.title })

        SessionManager(context).clearSession()
        assertEquals(listOf("C"), repository.loadEventsForMvp().data.map { it.title })
        // A read made with no session is never cached under "nobody": the next one fetches again.
        assertEquals(listOf("D"), repository.loadEventsForMvp().data.map { it.title })
        assertEquals(4, server.requestCount)
    }

    /** Stubbed fetch; optionally invalidates mid-flight to prove a late answer cannot be stored. */
    private class FetchingRepository(
        context: Context,
        cache: OrganizerEventsCache,
        private val invalidateDuringFetch: Boolean = false,
    ) : OrganizerRepository(context, eventsCache = cache, currentUserId = { "user-1" }) {
        var fetches = 0

        override suspend fun fetchOrganizerEvents(): NetworkResult<List<OrganizerEventDto>> {
            fetches++
            if (invalidateDuringFetch) invalidateEvents()
            return NetworkResult.Success(
                listOf(OrganizerEventDto(eventId = UUID.randomUUID(), title = "Fetched", status = "Approved")),
            )
        }
    }
}
