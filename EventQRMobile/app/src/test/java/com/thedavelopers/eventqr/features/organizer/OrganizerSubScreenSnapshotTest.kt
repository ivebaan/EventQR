package com.thedavelopers.eventqr.features.organizer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thedavelopers.eventqr.core.api.ApiService
import com.thedavelopers.eventqr.core.api.dto.AccountRole
import com.thedavelopers.eventqr.core.api.dto.ScanPurposeCode
import com.thedavelopers.eventqr.core.api.sharedGson
import com.thedavelopers.eventqr.core.cache.LastKnownCache
import com.thedavelopers.eventqr.core.cache.OrganizerSnapshots
import com.thedavelopers.eventqr.core.session.SessionManager
import com.thedavelopers.eventqr.features.ShadowEncryptedSharedPreferences
import com.thedavelopers.eventqr.features.ShadowMasterKeys
import com.thedavelopers.eventqr.features.auth.model.dto.LoginResponse
import com.thedavelopers.eventqr.features.rewards.model.dto.RewardResponse
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.UUID

/**
 * The per-event sub-screen keys: a write lands under its own event, another event never reads it, a
 * successful load stores the answer, and the user's own staff / scan-purpose change drops it so the
 * next open fetches.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [ShadowMasterKeys::class, ShadowEncryptedSharedPreferences::class])
class OrganizerSubScreenSnapshotTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private lateinit var server: MockWebServer
    private lateinit var api: ApiService
    private lateinit var cache: LastKnownCache
    private lateinit var session: SessionManager

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(GsonConverterFactory.create(sharedGson()))
            .build()
            .create(ApiService::class.java)
        session = SessionManager(context).apply { clearSession() }
        session.saveLoginResponse(
            LoginResponse(
                accessToken = "access",
                userId = UUID.fromString("12345678-2222-2222-2222-222222222222"),
                email = "org@test.com",
                fullName = "Org Owner",
                role = AccountRole.ORGANIZER,
                refreshToken = "refresh",
            ),
        )
        cache = LastKnownCache(context)
        cache.clearAll()
        OrganizerEventsCache.shared.clear()
    }

    @After
    fun tearDown() {
        cache.clearAll()
        OrganizerEventsCache.shared.clear()
        session.clearSession()
        server.shutdown()
    }

    private fun json(body: String) = MockResponse().setBody(body).setHeader("Content-Type", "application/json")

    private fun repository(): OrganizerRepository = OrganizerRepository(
        context,
        apiService = api,
        eventsCache = OrganizerEventsCache(),
        lastKnownCache = cache,
        currentUserId = { "user-1" },
    )

    private fun attendee(name: String, eventId: String) = OrganizerMvpAttendee(
        id = UUID.randomUUID().toString(),
        eventId = eventId,
        name = name,
        email = "",
        phone = "-",
        registrationStatus = "Registered",
        currentEventStatus = "Registered",
        points = 0,
        lastTransactionTime = "-",
        registeredDate = "-",
        qrCredentialStatus = "Issued",
        recentTransactions = emptyList(),
        recentRejectedScans = emptyList(),
    )

    private fun purpose(label: String, eventId: String) = OrganizerMvpScanPurpose(
        label = label,
        description = "-",
        enabled = true,
        duplicateRule = "-",
        trackingOnly = true,
        pointsEnabled = false,
        pointsValue = 0,
        requiredSelectionLabel = "Booth",
        id = UUID.randomUUID().toString(),
        code = ScanPurposeCode.BOOTH_VISIT,
    )

    @Test
    fun everySubScreenKeyIsScopedToItsEvent() {
        val a = UUID.randomUUID().toString()
        val b = UUID.randomUUID().toString()
        listOf(
            "attendees" to { id: String -> OrganizerSnapshots.attendees(id) },
            "reports" to { id: String -> OrganizerSnapshots.reports(id) },
            "rewards" to { id: String -> OrganizerSnapshots.rewards(id) },
            "claimed rewards" to { id: String -> OrganizerSnapshots.claimedRewards(id) },
            "reward settings" to { id: String -> OrganizerSnapshots.rewardSettings(id) },
            "scan purposes" to { id: String -> OrganizerSnapshots.scanPurposes(id) },
            "logs" to { id: String -> OrganizerSnapshots.logs(id) },
            "staff" to { id: String -> OrganizerSnapshots.staff(id) },
        ).forEach { (label, keyFor) ->
            assertNotEquals("$label key is not scoped to its event", keyFor(a), keyFor(b))
        }

        runBlocking {
            cache.write(OrganizerSnapshots.attendees(a), listOf(attendee("Cached Ana", a)))
            assertNotNull(cache.read<List<OrganizerMvpAttendee>>(OrganizerSnapshots.attendees(a), OrganizerSnapshots.ATTENDEES_TYPE, OrganizerSnapshots.MAX_AGE_MS))
            assertNull(
                "event A's snapshot was served for event B",
                cache.read<List<OrganizerMvpAttendee>>(OrganizerSnapshots.attendees(b), OrganizerSnapshots.ATTENDEES_TYPE, OrganizerSnapshots.MAX_AGE_MS),
            )
        }
    }

    @Test
    fun aSuccessfulAttendeeLoadStoresItsOwnEventsSnapshot() = runBlocking {
        val eventId = UUID.randomUUID()
        val otherEventId = UUID.randomUUID()
        server.enqueue(
            json(
                """{"success":true,"data":[{"registrationId":"${UUID.randomUUID()}","eventId":"$eventId","name":"Ana","recentTransactions":[],"recentRejectedScans":[]}]}""",
            ),
        )

        val load = repository().loadAttendeesForMvp(eventId.toString())

        assertEquals(listOf("Ana"), load.data.map { it.name })
        assertNotNull(cache.read<List<OrganizerMvpAttendee>>(OrganizerSnapshots.attendees(eventId.toString()), OrganizerSnapshots.ATTENDEES_TYPE, OrganizerSnapshots.MAX_AGE_MS))
        assertNull(cache.read<List<OrganizerMvpAttendee>>(OrganizerSnapshots.attendees(otherEventId.toString()), OrganizerSnapshots.ATTENDEES_TYPE, OrganizerSnapshots.MAX_AGE_MS))
    }

    @Test
    fun aStaffMutationDropsTheStaffSnapshotSoTheNextLoadFetches() = runBlocking {
        val eventId = UUID.randomUUID().toString()
        val key = OrganizerSnapshots.staff(eventId)
        cache.write(
            key,
            listOf(
                OrganizerMvpStaff(
                    id = "assignment-1",
                    name = "Ana",
                    email = "ana@test.com",
                    assignedEventId = eventId,
                    assignedEvent = "Fest",
                    roleLabel = "Scanner",
                    accessStatus = "Active",
                    addedDate = "-",
                    permissions = listOf("Scan QR"),
                ),
            ),
        )
        server.enqueue(json("""{"success":true}"""))

        repository().removeOrganizerStaff(eventId, "assignment-1")

        assertNull("the roster snapshot survived the user's own removal", cache.read<List<OrganizerMvpStaff>>(key, OrganizerSnapshots.STAFF_TYPE, OrganizerSnapshots.MAX_AGE_MS))

        // The next open is a cache miss, so it fetches and rewrites the snapshot.
        server.enqueue(json("""{"success":true,"data":[]}"""))
        repository().loadStaffForMvp(testOrganizerEvent().copy(id = eventId))
        assertEquals(2, server.requestCount)
        assertNotNull(cache.read<List<OrganizerMvpStaff>>(key, OrganizerSnapshots.STAFF_TYPE, OrganizerSnapshots.MAX_AGE_MS))
    }

    @Test
    fun aScanPurposeMutationDropsThePurposesSnapshot() = runBlocking {
        val eventId = UUID.randomUUID().toString()
        val key = OrganizerSnapshots.scanPurposes(eventId)
        cache.write(key, listOf(purpose("Booth A", eventId)))
        server.enqueue(json("""{"success":true}"""))

        repository().deleteScanPurposeForMvp(eventId, "purpose-1")

        assertNull(
            "the purposes snapshot survived the user's own delete",
            cache.read<List<OrganizerMvpScanPurpose>>(key, OrganizerSnapshots.SCAN_PURPOSES_TYPE, OrganizerSnapshots.MAX_AGE_MS),
        )
    }

    @Test
    fun theRewardKeysHoldTheirOwnPayloads() = runBlocking {
        val eventId = UUID.randomUUID().toString()
        cache.write(
            OrganizerSnapshots.rewards(eventId),
            listOf(
                RewardResponse(
                    rewardId = UUID.randomUUID(),
                    eventId = UUID.fromString(eventId),
                    name = "Shirt",
                    pointsRequired = 10,
                    status = com.thedavelopers.eventqr.core.api.dto.RewardStatus.ACTIVE,
                ),
            ),
        )
        cache.write(OrganizerSnapshots.rewardSettings(eventId), false)

        assertEquals(
            listOf("Shirt"),
            cache.read<List<RewardResponse>>(OrganizerSnapshots.rewards(eventId), OrganizerSnapshots.REWARDS_TYPE, OrganizerSnapshots.MAX_AGE_MS)?.data?.map { it.name },
        )
        assertEquals(
            false,
            cache.read<Boolean>(OrganizerSnapshots.rewardSettings(eventId), OrganizerSnapshots.REWARD_SETTINGS_TYPE, OrganizerSnapshots.MAX_AGE_MS)?.data,
        )
    }
}
