package com.thedavelopers.eventqr.features.organizer.events

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thedavelopers.eventqr.R
import com.thedavelopers.eventqr.core.api.NetworkResult
import com.thedavelopers.eventqr.features.ShadowEncryptedSharedPreferences
import com.thedavelopers.eventqr.features.ShadowMasterKeys
import com.thedavelopers.eventqr.features.organizer.EXTRA_EVENT_ID
import com.thedavelopers.eventqr.features.organizer.OrganizerMvpEvent
import com.thedavelopers.eventqr.features.organizer.OrganizerRepository
import com.thedavelopers.eventqr.features.organizer.model.dto.OrganizerEventDto
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.time.Instant
import java.util.UUID

/**
 * The Manage event hub's identity band (§6.4): the title owns the full `ui_primary` band, the
 * stats strip follows the tear, and no status line sits under the title (the quiet dot + word line
 * of v1.22 was removed in v1.46).
 *
 * The activity is created but never resumed, so the screen's own load never runs and `bindEvent`
 * is the only writer; the repository seam only keeps the constructor off the network.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [ShadowMasterKeys::class, ShadowEncryptedSharedPreferences::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EventManagementHubActivityTest {

    private var controller: ActivityController<EventManagementHubActivity>? = null

    @After
    fun tearDown() {
        controller?.destroy()
        controller = null
        ShadowLooper.idleMainLooper()
        RuntimeEnvironment.setFontScale(1.0f)
    }

    /**
     * A font-scale change recreates the activity, so the scale must be set before it is built.
     * [resume] drives the screen's own load and idles the main looper, so a test can assert the
     * error path; the default only creates the activity and leaves `bindEvent` the only writer.
     */
    private fun build(
        fontScale: Float = 1.0f,
        resume: Boolean = false,
        factory: (Context) -> OrganizerRepository = { LoadingRepository(it) },
    ): EventManagementHubActivity {
        RuntimeEnvironment.setFontScale(fontScale)
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            EventManagementHubActivity::class.java,
        ).putExtra(EXTRA_EVENT_ID, EVENT_ID)
        val controller = Robolectric.buildActivity(EventManagementHubActivity::class.java, intent)
        this.controller = controller
        val activity = controller.get()
        activity.installRepositoryFactory(factory)
        controller.create()
        if (resume) {
            controller.start().resume()
            ShadowLooper.idleMainLooper()
        }
        return activity
    }

    private fun event(
        status: String = "Draft",
        start: Instant? = null,
        end: Instant? = null,
    ) = OrganizerMvpEvent(
        id = EVENT_ID,
        title = "DevFest Manila 2026",
        organizerName = "TheDAVElopers",
        dateTime = "",
        shortDate = "",
        venue = "Iloilo Convention Center",
        status = status,
        submittedDate = "",
        adminRemarks = "",
        additionalOrganizers = emptyList(),
        registeredCount = 128,
        enteredCount = 0,
        attendedCount = 0,
        exitedCount = 0,
        noShowCount = 0,
        totalTransactions = 0,
        successfulScans = 0,
        rejectedScans = 0,
        benefitClaims = 0,
        boothSessionVisits = 0,
        rewardRedemptions = 0,
        totalPointsAwarded = 0,
        idTemplateStatus = "",
        rewardsStatus = "",
        staffCount = 0,
        scanPurposesCount = 0,
        capacity = 200,
        currentAttendeeCount = 128,
        eventStartAt = start,
        eventEndAt = end,
    )

    @Test
    fun statusLine_isGoneFromTheBandAndTheBindStillFillsTheHead() {
        val activity = build()

        activity.bindEvent(event())

        // The status line and its ids are retired (plan v1.46), so the name resolves to nothing.
        assertEquals(
            "the hub has no status line under the title",
            0,
            activity.resources.getIdentifier("emh_status_row", "id", activity.packageName),
        )
        // The title is now the band's only child: no leftover row keeps the band's height.
        val identity = activity.findViewById<LinearLayout>(R.id.emh_header_identity)
        assertEquals(1, identity.childCount)

        assertEquals(
            "DevFest Manila 2026",
            activity.findViewById<TextView>(R.id.emh_event_title).text.toString(),
        )
        assertEquals("128", activity.findViewById<TextView>(R.id.emh_stat_registered).text.toString())
        assertEquals("200", activity.findViewById<TextView>(R.id.emh_stat_capacity).text.toString())
        assertEquals("72", activity.findViewById<TextView>(R.id.emh_stat_available).text.toString())
    }

    @Test
    fun statusBadge_isGoneFromTheLayout() {
        val activity = build()

        // Positive control: a known hub id resolves, so the absence check cannot pass vacuously.
        assertNotEquals(
            0,
            activity.resources.getIdentifier("emh_header_identity", "id", activity.packageName),
        )
        assertEquals(
            "the trailing lime chip must stay deleted",
            0,
            activity.resources.getIdentifier("emh_status_badge", "id", activity.packageName),
        )
    }

    @Test
    fun title_ownsTheFullBand() {
        val activity = build()
        val identity = activity.findViewById<View>(R.id.emh_header_identity)
        val title = activity.findViewById<TextView>(R.id.emh_event_title)

        assertTrue("the title is a direct child of the band", title.parent === identity)
        assertEquals(
            "the title spans the band",
            LinearLayout.LayoutParams.MATCH_PARENT,
            (title.layoutParams as LinearLayout.LayoutParams).width,
        )
        assertEquals(2, title.maxLines)

        identity.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        identity.layout(0, 0, identity.measuredWidth, identity.measuredHeight)

        val gutter = activity.resources.getDimensionPixelSize(R.dimen.ui_gutter)
        assertEquals(identity.measuredWidth - 2 * gutter, title.measuredWidth)
    }

    @Test
    fun errorPath_replacesTheBandWithTheLoadingAndErrorStates() {
        val activity = build(resume = true, factory = { ErrorRepository(it) })

        // showStateCard() swaps the band's children for the loading + error states.
        val banner = activity.findViewById<LinearLayout>(R.id.emh_header_banner)
        assertEquals(2, banner.childCount)
        assertNull("the error state has no identity band", activity.findViewById<View>(R.id.emh_header_identity))
    }

    @Test
    fun editHead_keepsItsOwnStatusBadge() {
        val activity = build()

        // Positive control: the hub's own band resolves, so the absence check cannot pass vacuously.
        assertNotNull(activity.findViewById<View>(R.id.emh_header_identity))
        assertNull(
            "the editor's shared identity include is untouched",
            activity.findViewById<View>(R.id.oeHeadStatus),
        )
    }

    /** The created-only path never runs this; the resumed error test returns an Error. */
    private class LoadingRepository(context: Context) : OrganizerRepository(context) {
        override suspend fun fetchOrganizerEvent(eventId: String): NetworkResult<OrganizerEventDto> =
            NetworkResult.Loading
    }

    private class ErrorRepository(context: Context) : OrganizerRepository(context) {
        override suspend fun fetchOrganizerEvent(eventId: String): NetworkResult<OrganizerEventDto> =
            NetworkResult.Error("offline")
    }

    private companion object {
        val EVENT_ID: String = UUID.fromString("0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0").toString()
    }
}
