package com.thedavelopers.eventqr.features.organizer.scanpurposes

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thedavelopers.eventqr.R
import com.thedavelopers.eventqr.core.api.NetworkResult
import com.thedavelopers.eventqr.core.api.dto.AccountRole
import com.thedavelopers.eventqr.core.api.dto.ScanPurposeCode
import com.thedavelopers.eventqr.core.cache.LastKnownCache
import com.thedavelopers.eventqr.core.cache.OrganizerSnapshots
import com.thedavelopers.eventqr.core.session.SessionManager
import com.thedavelopers.eventqr.features.ShadowEncryptedSharedPreferences
import com.thedavelopers.eventqr.features.ShadowMasterKeys
import com.thedavelopers.eventqr.features.auth.model.dto.LoginResponse
import com.thedavelopers.eventqr.features.organizer.EXTRA_EVENT_ID
import com.thedavelopers.eventqr.features.organizer.OrganizerEventsCache
import com.thedavelopers.eventqr.features.organizer.OrganizerMvpDataSource
import com.thedavelopers.eventqr.features.organizer.OrganizerMvpEvent
import com.thedavelopers.eventqr.features.organizer.OrganizerMvpLoad
import com.thedavelopers.eventqr.features.organizer.OrganizerMvpScanPurpose
import com.thedavelopers.eventqr.features.organizer.OrganizerRepository
import com.thedavelopers.eventqr.features.organizer.allTexts
import com.thedavelopers.eventqr.features.organizer.model.dto.OrganizerScanPurposeDto
import com.thedavelopers.eventqr.features.organizer.testOrganizerEvent
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
 * The scan-purposes screen against the persisted `organizer/scan-purposes/{eventId}` list: the rows
 * paint before the network replies, the fresh answer replaces them, a failure keeps them, a pull
 * bypasses the snapshot and a failure with nothing painted is today's error line.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [ShadowMasterKeys::class, ShadowEncryptedSharedPreferences::class])
class ManageScanPurposesSnapshotTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val userId = UUID.fromString("aaaaaaaa-2222-2222-2222-222222222222")

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

    private fun dto(label: String) = OrganizerScanPurposeDto(
        scanPurposeId = UUID.randomUUID(),
        eventId = UUID.fromString(event.id),
        title = label,
        code = ScanPurposeCode.BOOTH_VISIT,
        enabled = true,
    )

    private fun purpose(label: String) = OrganizerMvpScanPurpose(
        label = label,
        description = "Tracks booth visits",
        enabled = true,
        duplicateRule = "No duplicates",
        trackingOnly = true,
        pointsEnabled = false,
        pointsValue = 0,
        requiredSelectionLabel = "Booth",
        id = UUID.randomUUID().toString(),
        code = ScanPurposeCode.BOOTH_VISIT,
    )

    private fun seed(labels: List<String>) = runBlocking {
        cache.write(OrganizerSnapshots.scanPurposes(event.id), labels.map(::purpose))
    }

    private inner class GatedRepository : OrganizerRepository(
        context,
        eventsCache = OrganizerEventsCache(),
        lastKnownCache = cache,
    ) {
        var result: NetworkResult<List<OrganizerScanPurposeDto>> = NetworkResult.Error("unset")
        val gate = CompletableDeferred<Unit>()
        var calls = 0

        override suspend fun loadApprovedOrganizerEvents(): OrganizerMvpLoad<List<OrganizerMvpEvent>> =
            OrganizerMvpLoad(listOf(event), OrganizerMvpDataSource.BACKEND)

        override suspend fun fetchOrganizerScanPurposes(eventId: String): NetworkResult<List<OrganizerScanPurposeDto>> {
            calls++
            gate.await()
            return result
        }
    }

    private fun buildActivity(repository: OrganizerRepository): ManageScanPurposesActivity {
        val intent = Intent(context, ManageScanPurposesActivity::class.java).putExtra(EXTRA_EVENT_ID, event.id)
        val controller = Robolectric.buildActivity(ManageScanPurposesActivity::class.java, intent)
        val activity = controller.get().apply {
            installRepositoryFactory { repository }
            cacheOverride = cache
        }
        controller.create()
        return activity
    }

    private fun ManageScanPurposesActivity.purposeRows(): Int =
        findViewById<LinearLayout>(R.id.msp_purpose_host)?.childCount ?: 0

    private fun ManageScanPurposesActivity.texts(): List<String> = window.decorView.allTexts()

    @Test
    fun theSnapshotPaintsThePurposesBeforeTheApiAnswers() {
        seed(listOf("Cached booth"))
        val repository = GatedRepository()
        val activity = buildActivity(repository)

        awaitUntil { activity.purposeRows() == 1 }

        assertEquals(1, repository.calls)
        assertTrue(activity.texts().contains("Cached booth"))
        assertFalse(
            "the loading card was drawn over the painted rows",
            activity.texts().contains(activity.getString(R.string.manage_scan_purposes_loading)),
        )
    }

    @Test
    fun theFreshAnswerReplacesTheSnapshot() {
        seed(listOf("Cached booth"))
        val repository = GatedRepository().apply {
            result = NetworkResult.Success(listOf(dto("Fresh one"), dto("Fresh two")))
        }
        val activity = buildActivity(repository)
        awaitUntil { activity.purposeRows() == 1 }

        repository.gate.complete(Unit)
        awaitUntil { activity.purposeRows() == 2 }

        assertEquals(2, activity.purposeRows())
        assertFalse(activity.texts().contains("Cached booth"))
    }

    @Test
    fun aFailedRefreshKeepsThePaintedPurposes() {
        seed(listOf("Cached booth"))
        val repository = GatedRepository().apply { result = NetworkResult.Error("offline") }
        val activity = buildActivity(repository)
        awaitUntil { activity.purposeRows() == 1 }

        repository.gate.complete(Unit)
        idle()
        idle()

        assertEquals(1, activity.purposeRows())
        assertTrue(activity.texts().contains("Cached booth"))
    }

    @Test
    fun aFailedLoadWithoutASnapshotShowsTheErrorLine() {
        val repository = GatedRepository().apply {
            result = NetworkResult.Error("offline")
            gate.complete(Unit)
        }
        val activity = buildActivity(repository)

        awaitUntil { activity.texts().contains("offline") }

        assertEquals(0, activity.purposeRows())
        assertTrue(activity.texts().contains("offline"))
    }

    @Test
    fun pullToRefreshBypassesTheSnapshot() {
        val repository = GatedRepository()
        val activity = buildActivity(repository)
        awaitUntil { repository.calls == 1 }

        seed(listOf("Cached booth"))
        activity.loadPurposes(showInitialLoading = false, forceNetwork = true)
        idle()

        assertEquals(2, repository.calls)
        assertFalse("a pull never paints the persisted rows", activity.texts().contains("Cached booth"))
    }
}
