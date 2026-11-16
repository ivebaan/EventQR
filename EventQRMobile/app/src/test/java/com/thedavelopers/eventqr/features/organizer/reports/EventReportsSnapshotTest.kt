package com.thedavelopers.eventqr.features.organizer.reports

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thedavelopers.eventqr.core.api.NetworkResult
import com.thedavelopers.eventqr.core.api.dto.AccountRole
import com.thedavelopers.eventqr.core.cache.LastKnownCache
import com.thedavelopers.eventqr.core.cache.OrganizerSnapshots
import com.thedavelopers.eventqr.core.session.SessionManager
import com.thedavelopers.eventqr.features.ShadowEncryptedSharedPreferences
import com.thedavelopers.eventqr.features.ShadowMasterKeys
import com.thedavelopers.eventqr.features.auth.model.dto.LoginResponse
import com.thedavelopers.eventqr.features.organizer.OrganizerEventsCache
import com.thedavelopers.eventqr.features.organizer.OrganizerMvpDataSource
import com.thedavelopers.eventqr.features.organizer.OrganizerMvpEvent
import com.thedavelopers.eventqr.features.organizer.OrganizerMvpLoad
import com.thedavelopers.eventqr.features.organizer.OrganizerRepository
import com.thedavelopers.eventqr.features.organizer.allTexts
import com.thedavelopers.eventqr.features.organizer.testOrganizerEvent
import com.thedavelopers.eventqr.features.reports.model.dto.EventReportSummaryDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * The reports screen against the persisted `organizer/reports/{eventId}` totals: the metric rows
 * paint before the network replies, the fresh answer replaces them, a failure keeps them and reports
 * in the in-flow strip, and a failure with nothing painted is today's error strip.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [ShadowMasterKeys::class, ShadowEncryptedSharedPreferences::class])
class EventReportsSnapshotTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val userId = UUID.fromString("eeeeeeee-1111-1111-1111-111111111111")

    private lateinit var session: SessionManager
    private lateinit var cache: LastKnownCache
    private lateinit var event: OrganizerMvpEvent

    @Before
    fun setUp() {
        OrganizerEventsCache.shared.clear()
        session = SessionManager(context).apply { clearSession() }
        session.saveLoginResponse(
            LoginResponse(
                accessToken = "access",
                userId = userId,
                email = "org@test.com",
                fullName = "Org Owner",
                role = AccountRole.ORGANIZER,
                refreshToken = "refresh",
            ),
        )
        cache = LastKnownCache(context)
        cache.clearAll()
        event = testOrganizerEvent("Cached fest")
    }

    @After
    fun tearDown() {
        cache.clearAll()
        OrganizerEventsCache.shared.clear()
        session.clearSession()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            idle()
            Thread.sleep(10)
        }
        idle()
    }

    private fun seededSummary(registered: Int) = EventReportSummaryDto(
        eventId = event.id,
        eventName = event.title,
        registeredCount = registered,
        checkedInCount = 0,
        exitedCount = 0,
    )

    private fun seed(registered: Int) = runBlocking {
        cache.write(OrganizerSnapshots.reports(event.id), seededSummary(registered))
    }

    private inner class FakeEventsRepository : OrganizerRepository(
        context,
        eventsCache = OrganizerEventsCache(),
        lastKnownCache = cache,
    ) {
        override suspend fun loadApprovedOrganizerEvents(): OrganizerMvpLoad<List<OrganizerMvpEvent>> =
            OrganizerMvpLoad(listOf(event), OrganizerMvpDataSource.BACKEND)
    }

    private inner class GatedReportsRepository : OrganizerReportsRepository(context) {
        var result: NetworkResult<EventReportSummaryDto> = NetworkResult.Error("unset")
        val gate = CompletableDeferred<Unit>()
        var calls = 0

        override suspend fun fetchSummary(eventId: String): NetworkResult<EventReportSummaryDto> {
            calls++
            gate.await()
            return result
        }
    }

    private fun buildActivity(reports: OrganizerReportsRepository): EventReportsActivity {
        val controller = Robolectric.buildActivity(EventReportsActivity::class.java)
        val activity = controller.get().apply {
            installRepositoryFactory { FakeEventsRepository() }
            reportsRepositoryFactory = { reports }
            cacheOverride = cache
        }
        controller.create()
        return activity
    }

    private fun EventReportsActivity.texts(): List<String> = window.decorView.allTexts()

    @Test
    fun theSnapshotPaintsTheTotalsBeforeTheApiAnswers() {
        seed(registered = 7)
        val reports = GatedReportsRepository()
        val activity = buildActivity(reports)

        awaitUntil { activity.texts().contains("7") }

        assertEquals(1, reports.calls)
        assertTrue("the persisted totals were not painted", activity.texts().contains("7"))
    }

    @Test
    fun theFreshTotalsReplaceTheSnapshot() {
        seed(registered = 7)
        val reports = GatedReportsRepository().apply {
            result = NetworkResult.Success(EventReportSummaryDto(eventId = event.id, registeredCount = 9))
        }
        val activity = buildActivity(reports)
        awaitUntil { activity.texts().contains("7") }

        reports.gate.complete(Unit)
        awaitUntil { activity.texts().contains("9") }

        assertTrue(activity.texts().contains("9"))
        assertFalse("the snapshot's number survived the fresh answer", activity.texts().contains("7"))
    }

    @Test
    fun aFailedLoadKeepsThePaintedTotalsAndReportsInTheStrip() {
        seed(registered = 7)
        val reports = GatedReportsRepository().apply { result = NetworkResult.Error("offline") }
        val activity = buildActivity(reports)
        awaitUntil { activity.texts().contains("7") }

        reports.gate.complete(Unit)
        idle()
        idle()

        val texts = activity.texts()
        assertTrue("the painted totals were wiped", texts.contains("7"))
        assertTrue("the failure is missing from the in-flow strip", texts.contains("offline"))
    }

    @Test
    fun aFailedLoadWithoutASnapshotShowsTheErrorStrip() {
        val reports = GatedReportsRepository().apply {
            result = NetworkResult.Error("offline")
            gate.complete(Unit)
        }
        val activity = buildActivity(reports)

        awaitUntil { activity.texts().contains("offline") }

        assertTrue(activity.texts().contains("offline"))
    }
}
