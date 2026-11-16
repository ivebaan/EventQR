package com.thedavelopers.eventqr.features.notifications

import android.os.Bundle
import android.content.Intent
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import android.view.View
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.thedavelopers.eventqr.R
import com.thedavelopers.eventqr.core.cache.AttendeeSnapshots
import com.thedavelopers.eventqr.core.cache.LastKnownCache
import com.thedavelopers.eventqr.ui.components.setContentWithDetailHeader
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.thedavelopers.eventqr.core.api.NetworkResult
import com.thedavelopers.eventqr.core.api.dto.AccountRole
import com.thedavelopers.eventqr.core.api.dto.NotificationStatus
import com.thedavelopers.eventqr.core.api.dto.NotificationType
import com.thedavelopers.eventqr.core.session.SessionManager
import com.thedavelopers.eventqr.core.session.SessionRefresher
import com.thedavelopers.eventqr.core.util.RoleMapper
import com.thedavelopers.eventqr.features.attendee.EXTRA_EVENT_ID
import com.thedavelopers.eventqr.features.attendee.EventDetailActivity
import com.thedavelopers.eventqr.features.attendee.MyEventRequestsActivity
import com.thedavelopers.eventqr.features.notifications.model.dto.NotificationResponse
import com.thedavelopers.eventqr.features.organizer.EXTRA_EVENT_ID as ORGANIZER_EVENT_ID
import com.thedavelopers.eventqr.features.organizer.events.EventManagementHubActivity
import com.thedavelopers.eventqr.features.organizer.events.OrganizerEventDetailsActivity
import com.thedavelopers.eventqr.features.organizer.openOrganizerPage
import com.thedavelopers.eventqr.ui.theme.EventQrTheme
import com.thedavelopers.eventqr.ui.theme.dimenOffset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

open class UnifiedNotificationsActivity : AppCompatActivity() {

    /** Tests set these before the first load to replace the network repository and the cache. */
    internal var repositoryOverride: NotificationsRepository? = null
    internal var cacheOverride: LastKnownCache? = null

    private val repository: NotificationsRepository
        get() = repositoryOverride ?: NotificationsRepository(this)
    private val cache: LastKnownCache
        get() = cacheOverride ?: LastKnownCache.get(this)

    private val _notifications = MutableStateFlow<List<NotificationResponse>>(emptyList())
    private val _isLoading = MutableStateFlow(false)
    private val _errorMessage = MutableStateFlow<String?>(null)
    /** A failed read/write of the list itself (§6.8 row 21); Retry re-runs [lastFailedOp]. */
    private val actionFailure = NotificationActionFailure()
    private var lastFailedOp: (() -> Unit)? = null

    /** Test handle: the list the Compose screen renders. */
    @VisibleForTesting
    internal val renderedNotifications: List<NotificationResponse> get() = _notifications.value

    /** Test handle: the spinner state, which the screen shows only over an empty list. */
    @VisibleForTesting
    internal val loadingForTest: Boolean get() = _isLoading.value

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val header = setContentWithDetailHeader("Notifications") {
            EventQrTheme {
                val notifications = _notifications.collectAsStateWithLifecycle().value
                val isLoading = _isLoading.collectAsStateWithLifecycle().value
                val errorMessage = _errorMessage.collectAsStateWithLifecycle().value
                val actionFailureCount = actionFailure.count.collectAsStateWithLifecycle().value

                NotificationsScreen(
                    notifications = notifications,
                    isLoading = isLoading,
                    errorMessage = errorMessage,
                    actionFailureCount = actionFailureCount,
                    onNotificationClick = { notification ->
                        if (notification.status != NotificationStatus.READ && notification.readAt == null) {
                            markRead(notification.notificationId)
                        }
                        openNotification(notification)
                    },
                    onRetryClick = { loadNotifications() },
                    onActionRetry = { lastFailedOp?.invoke() },
                    navigatesOnTap = { notification ->
                        notificationDestinationFor(notification.notificationType, notification.eventId?.toString()) != null
                    },
                )
            }
        }

        val markAllRead = TextView(this).apply {
            text = getString(R.string.ui_notifications_mark_all_read)
            setTextAppearance(R.style.TextAppearance_EventQR_Type_Label)
            gravity = android.view.Gravity.CENTER
            minHeight = dimenOffset(R.dimen.ui_touch_min)
            minWidth = dimenOffset(R.dimen.ui_touch_min)
            setPadding(dimenOffset(R.dimen.space_12), 0, dimenOffset(R.dimen.space_12), 0)
            setOnClickListener { markAllRead() }
        }
        header.endSlot.addView(markAllRead)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                _notifications.collect { list ->
                    val hasUnread = list.any { it.status != NotificationStatus.READ && it.readAt == null }
                    markAllRead.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
                    markAllRead.isEnabled = hasUnread
                    markAllRead.setTextColor(getColor(if (hasUnread) R.color.ui_primary else R.color.ui_text_disabled))
                }
            }
        }

        loadNotifications()
    }

    protected fun loadNotifications() {
        _errorMessage.value = null
        // The spinner is a first-load state: with rows already painted (a snapshot) the refresh runs
        // under them, and only the pull or the retry ever shows a progress state.
        val paintSnapshot = _notifications.value.isEmpty()
        _isLoading.value = paintSnapshot
        lifecycleScope.launch {
            // Captured before the read: an account switch in flight must not refile this list under
            // whoever is signed in when it lands.
            val owner = cache.currentOwner()
            if (paintSnapshot) {
                cache.read<List<NotificationResponse>>(
                    AttendeeSnapshots.NOTIFICATIONS,
                    AttendeeSnapshots.NOTIFICATIONS_TYPE,
                    AttendeeSnapshots.MAX_AGE_MS,
                )?.let { snapshot ->
                    _notifications.value = snapshot.data
                    _isLoading.value = false
                }
            }
            when (val result = repository.getMyNotifications()) {
                is NetworkResult.Success -> {
                    _notifications.value = result.data
                    _isLoading.value = false
                    cache.write(AttendeeSnapshots.NOTIFICATIONS, result.data, owner)
                }
                is NetworkResult.Error -> {
                    _isLoading.value = false
                    // A failed refresh over the painted snapshot keeps it: the error state owns the
                    // body only when there is nothing to show.
                    if (_notifications.value.isEmpty()) {
                        _errorMessage.value = result.message.ifBlank {
                            getString(R.string.ui_notifications_load_failed)
                        }
                    }
                }
                NetworkResult.Loading -> Unit
            }
        }
    }

    @VisibleForTesting
    internal fun markRead(notificationId: UUID) {
        val previous = _notifications.value
        _notifications.value = withReadState(previous, setOf(notificationId))
        lifecycleScope.launch {
            // The owner of the request, fixed before it is sent; the write follows the server's answer.
            val owner = cache.currentOwner()
            // The shade copy only leaves once the server stored the read, so a failed mark keeps it.
            val result = EventDecisionNotifier.markRead(this@UnifiedNotificationsActivity, listOf(notificationId.toString())) {
                repository.markNotificationRead(notificationId.toString())
            }
            when (result) {
                is NetworkResult.Success -> {
                    clearActionFailure()
                    persistReadState(owner)
                }
                is NetworkResult.Error -> {
                    _notifications.value = previous
                    showActionFailure(result.message) { markRead(notificationId) }
                }
                NetworkResult.Loading -> Unit
            }
        }
    }

    private fun markAllRead() {
        val previous = _notifications.value
        _notifications.value = withReadState(previous, previous.map { it.notificationId }.toSet())
        val ids = previous.map { it.notificationId.toString() }
        lifecycleScope.launch {
            val owner = cache.currentOwner()
            val result = EventDecisionNotifier.markRead(this@UnifiedNotificationsActivity, ids) {
                repository.markAllNotificationsRead()
            }
            when (result) {
                is NetworkResult.Success -> {
                    clearActionFailure()
                    persistReadState(owner)
                }
                is NetworkResult.Error -> {
                    _notifications.value = previous
                    showActionFailure(result.message) { markAllRead() }
                }
                NetworkResult.Loading -> Unit
            }
        }
    }

    /**
     * The server accepted the read flags on screen, so the persisted list must carry them: a reopened
     * screen paints the snapshot first, and an older unread state there would be a lie. [owner] is the
     * session the mark-read request was sent for, captured before it.
     */
    private suspend fun persistReadState(owner: String) {
        cache.write(AttendeeSnapshots.NOTIFICATIONS, _notifications.value, owner)
    }

    /**
     * §6.8 row 21: a failed read reports in the strip above the list, and Retry re-runs the same
     * read. The row state was already reverted, so the list never claims an optimistic write.
     * The server text goes to the log, never into the strip (§7.5).
     */
    private fun showActionFailure(message: String, retry: () -> Unit) {
        Log.w(TAG, "notification read failed: $message")
        lastFailedOp = retry
        // The screen announces the sentence from a LaunchedEffect on every new failure (§5.16). The
        // sentence never changes, so the counter carries the repeat: an equal StateFlow value would
        // be conflated and the strip would look dead after Retry.
        actionFailure.fail()
    }

    private fun clearActionFailure() {
        lastFailedOp = null
        actionFailure.clear()
    }

    private fun openNotification(notification: NotificationResponse) {
        val eventId = notification.eventId?.toString()
        if (notification.notificationType == NotificationType.EVENT_APPROVED) {
            openApprovedRequest(eventId)
            return
        }
        when (resolvedDestination(this, notification.notificationType, eventId)) {
            NotificationDestination.ORGANIZER_HUB -> openOrganizerPage(EventManagementHubActivity::class.java, eventId)
            NotificationDestination.ORGANIZER_EVENT_DETAILS -> startActivity(
                Intent(this, OrganizerEventDetailsActivity::class.java).putExtra(ORGANIZER_EVENT_ID, eventId),
            )
            NotificationDestination.ATTENDEE_EVENT_DETAIL -> startActivity(
                Intent(this, EventDetailActivity::class.java).putExtra(EXTRA_EVENT_ID, eventId),
            )
            NotificationDestination.ATTENDEE_REQUESTS -> startActivity(Intent(this, MyEventRequestsActivity::class.java))
            null -> Unit
        }
    }

    /**
     * Approval grants the organizer role in the same admin action, so the cached role can lag one
     * refresh behind. Refresh once here; a failed refresh keeps the cached role's fallback.
     */
    private fun openApprovedRequest(eventId: String?) {
        if (eventId == null) return
        val session = SessionManager(this)
        if (RoleMapper.isAtLeast(session.getUserRole(), AccountRole.ORGANIZER)) {
            openOrganizerPage(EventManagementHubActivity::class.java, eventId)
            return
        }
        lifecycleScope.launch {
            runCatching { SessionRefresher.refreshNow(this@UnifiedNotificationsActivity) }
            if (RoleMapper.isAtLeast(session.getUserRole(), AccountRole.ORGANIZER)) {
                openOrganizerPage(EventManagementHubActivity::class.java, eventId)
            } else {
                startActivity(Intent(this@UnifiedNotificationsActivity, EventDetailActivity::class.java).putExtra(EXTRA_EVENT_ID, eventId))
            }
        }
    }

    private companion object {
        const val TAG = "Notifications"
    }
}

/**
 * Local read state for the rows. Ids compare as UUIDs, so a differently-typed id can never silently
 * miss and leave a tapped row looking unread.
 */
internal fun withReadState(
    source: List<NotificationResponse>,
    readIds: Set<UUID>,
    readAt: Instant = Instant.now(),
): List<NotificationResponse> = source.map { notification ->
    if (notification.notificationId in readIds) {
        notification.copy(status = NotificationStatus.READ, readAt = readAt)
    } else {
        notification
    }
}

/**
 * §6.8 row 21 strip state. The sentence never changes, and a StateFlow conflates equal values, so a
 * repeat failure after Retry must bump a counter - only that re-emits, re-renders and re-announces.
 */
internal class NotificationActionFailure {
    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count.asStateFlow()

    fun fail() {
        _count.value += 1
    }

    fun clear() {
        _count.value = 0
    }
}
