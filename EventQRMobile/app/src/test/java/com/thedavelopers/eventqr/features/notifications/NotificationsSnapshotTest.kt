package com.thedavelopers.eventqr.features.notifications

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.thedavelopers.eventqr.core.api.NetworkResult
import com.thedavelopers.eventqr.core.api.dto.AccountRole
import com.thedavelopers.eventqr.core.api.dto.NotificationStatus
import com.thedavelopers.eventqr.core.api.dto.NotificationType
import com.thedavelopers.eventqr.core.cache.AttendeeSnapshots
import com.thedavelopers.eventqr.core.cache.LastKnownCache
import com.thedavelopers.eventqr.core.session.SessionManager
import com.thedavelopers.eventqr.features.ShadowEncryptedSharedPreferences
import com.thedavelopers.eventqr.features.ShadowMasterKeys
import com.thedavelopers.eventqr.features.auth.model.dto.LoginResponse
import com.thedavelopers.eventqr.features.notifications.model.dto.NotificationResponse
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.UUID

/**
 * The notifications list paints the persisted snapshot before the network answers, the accepted
 * mark-read write is what a reopen shows, and a failed mark-read keeps the row unread on disk too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [ShadowMasterKeys::class, ShadowEncryptedSharedPreferences::class])
class NotificationsSnapshotTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val userId = UUID.fromString("aaaaaaaa-3333-3333-3333-333333333333")

    private lateinit var session: SessionManager
    private lateinit var cache: LastKnownCache

    @Before
    fun setUp() {
        session = SessionManager(context).apply { clearSession() }
        session.saveLoginResponse(
            LoginResponse(
                accessToken = "access",
                userId = userId,
                email = "a@test.com",
                fullName = "Ada Lovelace",
                role = AccountRole.ATTENDEE,
                refreshToken = "refresh",
            ),
        )
        cache = LastKnownCache(context)
        cache.clearAll()
    }

    @After
    fun tearDown() {
        cache.clearAll()
        session.clearSession()
    }

    private fun notification(title: String, status: NotificationStatus = NotificationStatus.PENDING) =
        NotificationResponse(
            notificationId = UUID.randomUUID(),
            eventId = UUID.randomUUID(),
            recipientUserId = userId,
            title = title,
            message = "Body",
            status = status,
            createdAt = Instant.parse("2026-11-01T09:00:00Z"),
            notificationType = NotificationType.SCAN_APPROVED,
        )

    private fun seedSnapshot(item: NotificationResponse) = runBlocking {
        cache.write(AttendeeSnapshots.NOTIFICATIONS, listOf(item))
    }

    private fun persisted(): List<NotificationResponse>? = runBlocking {
        cache.read<List<NotificationResponse>>(
            AttendeeSnapshots.NOTIFICATIONS,
            AttendeeSnapshots.NOTIFICATIONS_TYPE,
            AttendeeSnapshots.MAX_AGE_MS,
        )?.data
    }

    /** The list read stays suspended until the test releases it. */
    private class GatedRepo(
        context: Context,
        private val list: NetworkResult<List<NotificationResponse>>,
    ) : NotificationsRepository(context) {
        var calls = 0
        var markReadResult: NetworkResult<NotificationResponse> = NetworkResult.Error("offline")
        val gate = CompletableDeferred<Unit>()

        override suspend fun getMyNotifications(unreadOnly: Boolean?): NetworkResult<List<NotificationResponse>> {
            calls++
            gate.await()
            return list
        }

        override suspend fun markNotificationRead(notificationId: String): NetworkResult<NotificationResponse> =
            markReadResult

        override suspend fun markAllNotificationsRead(): NetworkResult<Unit> = NetworkResult.Success(Unit)
    }

    private fun build(repo: NotificationsRepository): UnifiedNotificationsActivity {
        val controller = Robolectric.buildActivity(UnifiedNotificationsActivity::class.java)
        val activity = controller.get()
        activity.repositoryOverride = repo
        activity.cacheOverride = cache
        controller.create().start().resume().visible()
        return activity
    }

    /** The snapshot read hops to an IO thread; idle the main looper and wait for the paint. */
    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    @Test
    fun theSnapshotPaintsBeforeTheApiAnswers_withoutASpinner() {
        seedSnapshot(notification("Cached decision"))
        val repo = GatedRepo(context, NetworkResult.Success(listOf(notification("Fresh decision"))))

        val activity = build(repo)
        awaitUntil { activity.renderedNotifications.isNotEmpty() }

        assertEquals("Cached decision", activity.renderedNotifications.single().title)
        // The screen draws its spinner only over an empty list; a painted list never shows one.
        assertFalse(activity.loadingForTest)
        // The refresh runs underneath the painted rows, exactly once.
        assertEquals(1, repo.calls)
    }

    @Test
    fun theFreshAnswerReplacesTheSnapshot() {
        seedSnapshot(notification("Cached decision"))
        val repo = GatedRepo(context, NetworkResult.Success(listOf(notification("Fresh decision"))))

        val activity = build(repo)
        awaitUntil { activity.renderedNotifications.singleOrNull()?.title == "Cached decision" }

        repo.gate.complete(Unit)
        awaitUntil { activity.renderedNotifications.singleOrNull()?.title == "Fresh decision" }

        assertEquals("Fresh decision", activity.renderedNotifications.single().title)
    }

    @Test
    fun aFailedRefreshKeepsThePaintedSnapshot() {
        seedSnapshot(notification("Cached decision"))
        val repo = GatedRepo(context, NetworkResult.Error("offline"))
        repo.gate.complete(Unit)

        val activity = build(repo)
        awaitUntil { !activity.loadingForTest }

        assertEquals("Cached decision", activity.renderedNotifications.single().title)
    }

    @Test
    fun aFailedFirstLoadWithoutASnapshotShowsTheErrorState() {
        val repo = GatedRepo(context, NetworkResult.Error("offline"))
        repo.gate.complete(Unit)

        val activity = build(repo)
        awaitUntil { !activity.loadingForTest }

        assertTrue(activity.renderedNotifications.isEmpty())
    }

    @Test
    fun anAcceptedMarkReadIsWhatAReopenPaints() {
        val item = notification("Cached decision")
        seedSnapshot(item)
        val repo = GatedRepo(context, NetworkResult.Success(listOf(item)))
        repo.gate.complete(Unit)
        repo.markReadResult = NetworkResult.Success(item.copy(status = NotificationStatus.READ))

        val activity = build(repo)
        awaitUntil { activity.renderedNotifications.isNotEmpty() }

        activity.markRead(item.notificationId)
        awaitUntil { persisted()?.single()?.status == NotificationStatus.READ }

        assertEquals(NotificationStatus.READ, persisted()?.single()?.status)
    }

    @Test
    fun aFailedMarkReadLeavesThePersistedRowUnread() {
        val item = notification("Cached decision")
        seedSnapshot(item)
        val repo = GatedRepo(context, NetworkResult.Success(listOf(item)))
        repo.gate.complete(Unit)
        repo.markReadResult = NetworkResult.Error("offline")

        val activity = build(repo)
        awaitUntil { activity.renderedNotifications.isNotEmpty() }

        activity.markRead(item.notificationId)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(NotificationStatus.PENDING, persisted()?.single()?.status)
    }
}
