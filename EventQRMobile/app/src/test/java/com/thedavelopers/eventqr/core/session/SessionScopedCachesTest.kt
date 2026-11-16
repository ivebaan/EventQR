package com.thedavelopers.eventqr.core.session

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thedavelopers.eventqr.core.api.dto.AccountRole
import com.thedavelopers.eventqr.features.ShadowEncryptedSharedPreferences
import com.thedavelopers.eventqr.features.ShadowMasterKeys
import com.thedavelopers.eventqr.features.auth.model.dto.LoginResponse
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID

/** The registry that lets core drop every user-scoped cache without importing a feature package. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [ShadowMasterKeys::class, ShadowEncryptedSharedPreferences::class])
class SessionScopedCachesTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val cleared = mutableListOf<String>()
    private val session = SessionManager(context)

    private fun fake(name: String) = SessionScopedCache { cleared += name }

    private fun signIn(userId: UUID = UUID.fromString("11111111-2222-3333-4444-555555555555")) =
        session.saveLoginResponse(
            LoginResponse(
                accessToken = "access",
                userId = userId,
                email = "user@example.com",
                fullName = "User",
                role = AccountRole.ATTENDEE,
                refreshToken = "refresh",
            )
        )

    @Before
    fun setUp() {
        // Start from an empty registry so each assertion is about exactly what the transition cleared.
        SessionScopedCaches.removeAll()
        cleared.clear()
    }

    @Test
    fun clearAll_clearsEveryRegisteredCacheExactlyOnce() {
        SessionScopedCaches.register(fake("first"))
        SessionScopedCaches.register(fake("second"))

        SessionScopedCaches.clearAll()

        assertEquals(listOf("first", "second"), cleared)
    }

    @Test
    fun remove_stopsACacheFromBeingCleared() {
        val kept = fake("kept")
        SessionScopedCaches.register(kept)
        SessionScopedCaches.register(fake("removed"))
        SessionScopedCaches.remove(kept)

        SessionScopedCaches.clearAll()

        assertEquals(listOf("removed"), cleared)
    }

    @Test
    fun register_isIdempotent() {
        val cache = fake("once")
        SessionScopedCaches.register(cache)
        SessionScopedCaches.register(cache)

        SessionScopedCaches.clearAll()

        assertEquals(listOf("once"), cleared)
    }

    @Test
    fun loginSwitch_clearsRegisteredCaches() {
        SessionScopedCaches.register(fake("login"))

        signIn()

        assertEquals(listOf("login"), cleared)
    }

    @Test
    fun clearSession_clearsRegisteredCaches() {
        SessionScopedCaches.register(fake("logout"))

        session.clearSession()

        assertEquals(listOf("logout"), cleared)
    }

    @Test
    fun sessionExpiry_clearsRegisteredCaches() {
        SessionScopedCaches.register(fake("expired"))

        SessionEvents.notifySessionExpired()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf("expired"), cleared)
    }
}
