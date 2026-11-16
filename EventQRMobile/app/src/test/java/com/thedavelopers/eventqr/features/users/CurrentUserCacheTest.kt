package com.thedavelopers.eventqr.features.users

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thedavelopers.eventqr.core.api.NetworkResult
import com.thedavelopers.eventqr.core.api.dto.AccountRole
import com.thedavelopers.eventqr.core.api.dto.AccountStatus
import com.thedavelopers.eventqr.core.session.CurrentUserCache
import com.thedavelopers.eventqr.core.session.SessionManager
import com.thedavelopers.eventqr.features.ShadowEncryptedSharedPreferences
import com.thedavelopers.eventqr.features.ShadowMasterKeys
import com.thedavelopers.eventqr.features.attendee.AttendeeRepository
import com.thedavelopers.eventqr.features.auth.model.dto.LoginResponse
import com.thedavelopers.eventqr.features.dashboard.DashboardRepository
import com.thedavelopers.eventqr.features.users.model.dto.UserResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.UUID

/**
 * `GET users/me` is shared between the dashboard and the profile screen: one request per 30 s window,
 * invalidated by a saved profile edit, cleared on logout and on an account switch.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [ShadowMasterKeys::class, ShadowEncryptedSharedPreferences::class])
class CurrentUserCacheTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var session: SessionManager

    private val userA = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val userB = UUID.fromString("22222222-2222-2222-2222-222222222222")

    @Before
    fun setUp() {
        CurrentUserCache.invalidate()
        session = SessionManager(context)
        session.clearSession()
        signIn(userA)
    }

    @After
    fun tearDown() {
        CurrentUserCache.invalidate()
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

    private fun user(id: UUID = userA, name: String = "A") = UserResponse(
        userId = id,
        email = "a@test.com",
        fullName = name,
        phoneNumber = "+639170000000",
        role = AccountRole.ATTENDEE,
        status = AccountStatus.ACTIVE,
    )

    private inner class CountingDashboardRepo : DashboardRepository(context) {
        var calls = 0
        override suspend fun fetchCurrentUser(): NetworkResult<UserResponse> {
            calls++
            return NetworkResult.Success(user())
        }
    }

    private inner class CountingProfileRepo : AttendeeRepository(context) {        var calls = 0
        override suspend fun fetchMyProfile(): NetworkResult<UserResponse> {
            calls++
            return NetworkResult.Success(user(UUID.fromString(session.getUserId())))
        }

        override suspend fun saveProfile(fullName: String, phoneNumber: String?): NetworkResult<UserResponse> =
            NetworkResult.Success(user(name = fullName))
    }

    /** A request that is still in flight while an edit is saved. */
    private inner class GatedProfileRepo : AttendeeRepository(context) {
        val gate = CompletableDeferred<Unit>()
        override suspend fun fetchMyProfile(): NetworkResult<UserResponse> {
            gate.await()
            return NetworkResult.Success(user(name = "Pre-edit"))
        }
    }

    @Test
    fun aFetchInFlightDuringAProfileSaveDoesNotStampItsPreEditAnswer() = runBlocking {
        val repo = GatedProfileRepo()
        val job = launch(Dispatchers.Unconfined) { repo.getMyProfile() }

        // The edit saved while the read was still in flight.
        CurrentUserCache.invalidate()
        repo.gate.complete(Unit)
        job.join()

        assertNull("the pre-edit answer is dropped, not stamped", CurrentUserCache.fresh(userA.toString()))
    }

    @Test
    fun dashboardThenProfileWithinTheTtlMakesOneUsersMeCall() = runBlocking {
        val dashboard = CountingDashboardRepo()
        val profile = CountingProfileRepo()

        assertTrue(dashboard.getCurrentUser() is NetworkResult.Success)
        assertTrue(profile.getMyProfile() is NetworkResult.Success)

        assertEquals(1, dashboard.calls)
        assertEquals("the profile read the dashboard's answer", 0, profile.calls)
    }

    @Test
    fun aSavedProfileEditInvalidatesTheStoredCopy() = runBlocking {
        val dashboard = CountingDashboardRepo()
        val profile = CountingProfileRepo()
        dashboard.getCurrentUser()

        assertTrue(profile.updateProfile("New Name", null) is NetworkResult.Success)
        assertTrue(profile.getMyProfile() is NetworkResult.Success)

        assertEquals(1, profile.calls)
    }

    @Test
    fun logoutClearsTheStoredCopy() = runBlocking {        val dashboard = CountingDashboardRepo()
        dashboard.getCurrentUser()

        session.clearSession()
        signIn(userA)
        dashboard.getCurrentUser()

        assertEquals(2, dashboard.calls)
    }

    @Test
    fun anAccountSwitchDoesNotServeThePreviousUsersCopy() = runBlocking {
        val profile = CountingProfileRepo()
        profile.getMyProfile()

        signIn(userB)
        val result = profile.getMyProfile()

        assertEquals(2, profile.calls)
        assertEquals(userB, (result as NetworkResult.Success).data.userId)
    }

    @Test
    fun aStoredCopyIsNotServedAfterTheTtl() {
        CurrentUserCache.set(userA.toString(), user())

        assertEquals("A", CurrentUserCache.fresh(userA.toString())?.fullName)
        assertNull(CurrentUserCache.fresh(userA.toString(), Instant.now().plusSeconds(31)))
        assertNull(CurrentUserCache.fresh(userB.toString()))
    }
}
