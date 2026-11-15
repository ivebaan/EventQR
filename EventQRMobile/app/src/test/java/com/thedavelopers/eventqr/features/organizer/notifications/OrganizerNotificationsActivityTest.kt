package com.thedavelopers.eventqr.features.organizer.notifications

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.ui.platform.ComposeView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thedavelopers.eventqr.R
import com.thedavelopers.eventqr.core.api.dto.AccountRole
import com.thedavelopers.eventqr.core.session.SessionManager
import com.thedavelopers.eventqr.features.ShadowEncryptedSharedPreferences
import com.thedavelopers.eventqr.features.ShadowMasterKeys
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [ShadowMasterKeys::class, ShadowEncryptedSharedPreferences::class])
class OrganizerNotificationsActivityTest {

    @Before
    fun setUp() {
        SessionManager(ApplicationProvider.getApplicationContext()).apply {
            clearSession()
            saveRole(AccountRole.ORGANIZER)
        }
    }

    @Test
    fun organizerSession_reachesTheSharedNotificationsScreen() {
        val activity = Robolectric.buildActivity(OrganizerNotificationsActivity::class.java).create().get()

        assertFalse("an organizer must not be denied the screen", activity.isFinishing)
        // The shared screen builds all three: its own title, the mark-all-read action and the Compose body.
        val title = activity.findViewById<TextView>(R.id.nav_header_title)
        assertNotNull("shared detail header", title)
        assertNotNull(
            "mark-all-read action from the shared activity",
            findText(activity.window.decorView, activity.getString(R.string.ui_notifications_mark_all_read)),
        )
        assertNotNull("shared Compose body", findComposeView(activity.window.decorView))
    }

    private fun findText(root: View, text: String): View? {
        if (root is TextView && root.text.toString() == text) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) findText(root.getChildAt(index), text)?.let { return it }
        }
        return null
    }

    private fun findComposeView(root: View): ComposeView? {
        if (root is ComposeView) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) findComposeView(root.getChildAt(index))?.let { return it }
        }
        return null
    }
}
