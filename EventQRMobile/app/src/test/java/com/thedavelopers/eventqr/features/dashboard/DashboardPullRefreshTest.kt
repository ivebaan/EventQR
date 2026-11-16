package com.thedavelopers.eventqr.features.dashboard

import android.os.Looper
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thedavelopers.eventqr.R
import com.thedavelopers.eventqr.features.ShadowEncryptedSharedPreferences
import com.thedavelopers.eventqr.features.ShadowMasterKeys
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A pull also rotates the session (a role change shows on pull), while a resume stays on the
 * throttled path. A failed rotation never holds the data reload back.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [ShadowMasterKeys::class, ShadowEncryptedSharedPreferences::class])
class DashboardPullRefreshTest {

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Fires the layout's own listener, so the wiring under test is the real one. */
    private fun DashboardActivity.pull() {
        val refresh = findViewById<SwipeRefreshLayout>(R.id.swipeRefreshDashboard)
        refresh.isRefreshing = true
        val listener = SwipeRefreshLayout::class.java.getDeclaredField("mListener")
            .apply { isAccessible = true }.get(refresh) as SwipeRefreshLayout.OnRefreshListener
        listener.onRefresh()
    }

    @Test
    fun aPullForcesTheSessionRefreshOnce_andStillReloadsWhenItFails() {
        var forced = 0
        var stale = 0
        val controller = Robolectric.buildActivity(DashboardActivity::class.java)
        val activity = controller.get().apply {
            sessionForcedRefresh = { forced++; throw IllegalStateException("offline") }
            sessionRefresh = { stale++ }
        }
        controller.create()
        idle()
        assertEquals(1, activity.loadRequests)
        assertEquals("create is never a resume refresh", 0, stale)

        activity.pull()
        idle()

        assertEquals("one forced rotation per pull", 1, forced)
        assertEquals("the rows reload whatever the refresh did", 2, activity.loadRequests)
        assertEquals("a pull is never the throttled path", 0, stale)
    }

    @Test
    fun aResumeUsesTheThrottledRefresh_andNeverForces() {
        var forced = 0
        var stale = 0
        val controller = Robolectric.buildActivity(DashboardActivity::class.java)
        controller.get().apply {
            sessionForcedRefresh = { forced++ }
            sessionRefresh = { stale++ }
        }
        controller.create().start().resume()
        idle()
        assertEquals(1, stale)
        assertEquals(0, forced)

        controller.pause().resume()
        idle()
        assertEquals(2, stale)
        assertEquals("a resume must not spend a refresh token", 0, forced)
    }
}
