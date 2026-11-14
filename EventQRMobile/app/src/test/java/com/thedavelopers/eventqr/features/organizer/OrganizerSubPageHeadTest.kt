package com.thedavelopers.eventqr.features.organizer

import android.app.Activity
import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thedavelopers.eventqr.R
import com.thedavelopers.eventqr.features.ShadowEncryptedSharedPreferences
import com.thedavelopers.eventqr.features.ShadowMasterKeys
import com.thedavelopers.eventqr.features.organizer.events.ManageEventsActivity
import com.thedavelopers.eventqr.features.organizer.reports.EventReportsActivity
import com.thedavelopers.eventqr.features.organizer.rewards.ManageRewardsActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/**
 * §6.2: the organizer sub-pages (Events, Reports, Rewards) carry no ORGANIZER eyebrow. The slot
 * stays in the layout but is INVISIBLE, so the head spacing is unchanged.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [ShadowMasterKeys::class, ShadowEncryptedSharedPreferences::class])
class OrganizerSubPageHeadTest {

    private fun Activity.assertNoEyebrow() {
        assertNotEquals(View.VISIBLE, findViewById<View>(R.id.passHeadEyebrow).visibility)
    }

    @Test
    fun eventsHead_hasNoEyebrow() {
        val activity = Robolectric.buildActivity(ManageEventsActivity::class.java).create().get()

        activity.assertNoEyebrow()
        assertEquals(
            activity.getString(R.string.ui_organizer_events),
            activity.findViewById<TextView>(R.id.passHeadTitle).text.toString(),
        )
    }

    @Test
    fun reportsHead_hasNoEyebrow() {
        val activity = Robolectric.buildActivity(EventReportsActivity::class.java).create().get()

        activity.assertNoEyebrow()
        assertEquals(
            activity.getString(R.string.event_reports_screen_title),
            activity.findViewById<TextView>(R.id.passHeadTitle).text.toString(),
        )
    }

    @Test
    fun rewardsHead_hasNoEyebrow() {
        val activity = Robolectric.buildActivity(ManageRewardsActivity::class.java).create().get()

        activity.assertNoEyebrow()
        assertEquals(
            activity.getString(R.string.manage_rewards_title),
            activity.findViewById<TextView>(R.id.passHeadTitle).text.toString(),
        )
    }
}
