package com.thedavelopers.eventqr.features.staff

import android.content.Context
import android.os.Looper
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thedavelopers.eventqr.R
import com.thedavelopers.eventqr.core.api.dto.AccountRole
import com.thedavelopers.eventqr.core.cache.LastKnownCache
import com.thedavelopers.eventqr.core.session.SessionManager
import com.thedavelopers.eventqr.features.ShadowEncryptedSharedPreferences
import com.thedavelopers.eventqr.features.ShadowMasterKeys
import com.thedavelopers.eventqr.features.auth.model.dto.LoginResponse
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

/** A pull also rotates the session, and a failed rotation never holds the reload back. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [ShadowMasterKeys::class, ShadowEncryptedSharedPreferences::class])
class StaffDashboardPullRefreshTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        SessionManager(context).apply { clearSession() }.saveLoginResponse(
            LoginResponse(
                accessToken = "access",
                userId = UUID.fromString("dddddddd-1111-1111-1111-111111111111"),
                email = "staff@test.com",
                fullName = "Sam Staff",
                role = AccountRole.STAFF,
                refreshToken = "refresh",
            ),
        )
        LastKnownCache(context).clearAll()
    }

    @After
    fun tearDown() {
        LastKnownCache(context).clearAll()
        SessionManager(context).clearSession()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Fires the layout's own listener, so the wiring under test is the real one. */
    private fun StaffDashboardActivity.pull() {
        val refresh = findViewById<SwipeRefreshLayout>(R.id.swipeRefreshDashboard)
        refresh.isRefreshing = true
        val listener = SwipeRefreshLayout::class.java.getDeclaredField("mListener")
            .apply { isAccessible = true }.get(refresh) as SwipeRefreshLayout.OnRefreshListener
        listener.onRefresh()
    }

    @Test
    fun aPullForcesTheSessionRefreshOnce_andStillReloadsWhenItFails() {
        var forced = 0
        val controller = Robolectric.buildActivity(StaffDashboardActivity::class.java)
        val activity = controller.get().apply {
            sessionForcedRefresh = { forced++; throw IllegalStateException("offline") }
        }
        controller.create()
        idle()
        assertTrue("the staff guard passed", activity.loadRequests == 1)

        activity.pull()
        idle()

        assertEquals("one forced rotation per pull", 1, forced)
        assertEquals("the rows reload whatever the refresh did", 2, activity.loadRequests)
    }
}
