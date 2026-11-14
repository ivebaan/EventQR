package com.thedavelopers.eventqr.features.organizer.reports

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thedavelopers.eventqr.R
import com.thedavelopers.eventqr.core.api.NetworkResult
import com.thedavelopers.eventqr.features.organizer.testOrganizerEvent
import com.thedavelopers.eventqr.features.reports.model.dto.EventReportSummaryDto
import com.thedavelopers.eventqr.features.ShadowEncryptedSharedPreferences
import com.thedavelopers.eventqr.features.ShadowMasterKeys
import com.thedavelopers.eventqr.features.organizer.OrganizerMvpDataSource
import com.thedavelopers.eventqr.features.organizer.OrganizerMvpEvent
import com.thedavelopers.eventqr.features.organizer.OrganizerMvpLoad
import com.thedavelopers.eventqr.features.organizer.OrganizerRepository
import com.thedavelopers.eventqr.features.organizer.allTexts
import com.thedavelopers.eventqr.features.organizer.findButton
import com.thedavelopers.eventqr.ui.components.EventPickerField
import com.thedavelopers.eventqr.ui.components.EventPickerInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/** N4: a failed organizer-events fetch shows an error with Retry, never "No events found". */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [ShadowMasterKeys::class, ShadowEncryptedSharedPreferences::class])
class EventReportsEventsLoadTest {

    private fun build(): EventReportsActivity =
        Robolectric.buildActivity(EventReportsActivity::class.java).create().get()

    private fun failed(message: String?) =
        OrganizerMvpLoad(emptyList<OrganizerMvpEvent>(), OrganizerMvpDataSource.ERROR, message)

    private fun EventReportsActivity.texts() = window.decorView.allTexts()

    /** The empty state's action is a text button now, not a Material Button. */
    private fun android.view.View.clickText(label: CharSequence): Boolean {
        if (this is android.widget.TextView && text == label) {
            performClick()
            return true
        }
        if (this is android.view.ViewGroup) {
            for (i in 0 until childCount) {
                if (getChildAt(i).clickText(label)) return true
            }
        }
        return false
    }

    private fun android.view.View.hasText(label: CharSequence): Boolean {
        if (this is android.widget.TextView && text == label) return true
        if (this is android.view.ViewGroup) {
            for (i in 0 until childCount) if (getChildAt(i).hasText(label)) return true
        }
        return false
    }

    @Test
    fun failedLoad_showsTheErrorWithRetry_notTheEmptyState() {
        val activity = build()

        activity.onEventsLoaded(failed("Network problem. Check your connection and try again."))

        val texts = activity.texts()
        assertTrue(texts.contains("Network problem. Check your connection and try again."))
        assertFalse(texts.contains(activity.getString(R.string.event_reports_no_events_title)))
        assertTrue(texts.contains(activity.getString(R.string.organizer_retry)))
    }

    @Test
    fun failedLoadWithoutMessage_usesTheGenericEventsLoadError() {
        val activity = build()

        activity.onEventsLoaded(failed(null))

        assertTrue(activity.texts().contains(activity.getString(R.string.organizer_dashboard_events_load_failed)))
    }

    @Test
    fun retry_replacesTheErrorWithTheLoadingSkeleton() {
        val activity = build()
        activity.onEventsLoaded(failed("Timeout"))

        activity.window.decorView.clickText(activity.getString(R.string.organizer_retry))

        assertFalse(activity.texts().contains("Timeout"))
        assertFalse(activity.window.decorView.hasText(activity.getString(R.string.organizer_retry)))
    }

    private class FakeReportsRepository :
        OrganizerReportsRepository(ApplicationProvider.getApplicationContext()) {
        override suspend fun fetchSummary(eventId: String): NetworkResult<EventReportSummaryDto> =
            NetworkResult.Success(EventReportSummaryDto())
    }

    /** No network: the initial load must not race the test's own onEventsLoaded call. */
    private class FakeOrganizerRepository(context: android.content.Context) : OrganizerRepository(context) {
        override suspend fun loadApprovedOrganizerEvents(): OrganizerMvpLoad<List<OrganizerMvpEvent>> =
            OrganizerMvpLoad(emptyList(), OrganizerMvpDataSource.BACKEND)
    }

    @Test
    fun eventSelector_usesTheAlreadyLoadedEventList() {
        val activity = build()
        activity.reportsRepository = FakeReportsRepository()
        val events = listOf(testOrganizerEvent("Night Market"), testOrganizerEvent("Day Fair"))

        // No second fetch: in this test a fetch would fail and leave the selector without the event name.
        activity.onEventsLoaded(OrganizerMvpLoad(events, OrganizerMvpDataSource.BACKEND))

        assertTrue(activity.texts().contains("Night Market"))
        assertNull(activity.window.decorView.findButton(activity.getString(R.string.organizer_retry)))
    }

    @Test
    fun selectingAnEvent_rebuildsTheListWithNoOpenPopup() {
        val controller = Robolectric.buildActivity(EventReportsActivity::class.java)
        controller.get().installRepositoryFactory { FakeOrganizerRepository(it) }
        val activity = controller.create().get()
        activity.reportsRepository = FakeReportsRepository()
        val events = listOf(testOrganizerEvent("Night Market"), testOrganizerEvent("Day Fair"))
        activity.onEventsLoaded(OrganizerMvpLoad(events, OrganizerMvpDataSource.BACKEND))

        val input = activity.findViewById<EventPickerField>(R.id.event_picker_field)
            .findViewById<EventPickerInput>(R.id.eventPickerInput)
        input.performClick()
        assertTrue(input.isPopupShowing)

        input.onItemClickListener.onItemClick(null, null, 1, 0)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        val rebuilt = activity.findViewById<EventPickerField>(R.id.event_picker_field)
            .findViewById<EventPickerInput>(R.id.eventPickerInput)
        assertFalse(rebuilt.isPopupShowing)
        assertEquals("Day Fair", rebuilt.text.toString())
    }

    @Test
    fun successfulEmptyLoad_stillShowsNoEventsFound() {
        val activity = build()

        activity.onEventsLoaded(OrganizerMvpLoad(emptyList(), OrganizerMvpDataSource.BACKEND))

        assertTrue(activity.texts().contains(activity.getString(R.string.event_reports_no_events_title)))
        assertNull(activity.window.decorView.findButton(activity.getString(R.string.organizer_retry)))
    }
}
