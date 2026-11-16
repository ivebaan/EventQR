package com.thedavelopers.eventqr.core.cache

import com.google.gson.reflect.TypeToken
import com.thedavelopers.eventqr.features.dashboard.model.dto.DashboardSummary
import com.thedavelopers.eventqr.features.dashboard.model.dto.DashboardUpcomingEvent
import com.thedavelopers.eventqr.features.events.model.dto.AttendeeEventResponse
import com.thedavelopers.eventqr.features.notifications.model.dto.NotificationResponse
import com.thedavelopers.eventqr.features.registrations.model.dto.RegistrationResponse
import java.lang.reflect.Type

/**
 * The keys, the Gson types and the freshness window of every persisted attendee snapshot.
 *
 * One list because the writers and the invalidators live in different packages: a mutation has to
 * drop every key a screen could have painted from, and only a shared table makes that provable.
 *
 * A key lives with its type. The one exception is the transaction history, whose sealed row type
 * needs a cache-local DTO: its key and Gson type sit together on
 * [com.thedavelopers.eventqr.features.attendee.CachedTransactionRow] instead of here.
 *
 * [MAX_AGE_MS] is one day: a cold open paints the last known screen instantly, and an entry older
 * than that is a miss (the network answers instead). Staleness is never a correctness decision: the
 * normal load always runs right after the paint.
 */
object AttendeeSnapshots {
    const val MAX_AGE_MS = 24L * 60 * 60 * 1000

    const val DASHBOARD_SUMMARY = "attendee/dashboard/summary"
    const val DASHBOARD_UPCOMING = "attendee/dashboard/upcoming"
    const val BROWSE_EVENTS = "attendee/events/browse"
    const val REGISTRATIONS = "attendee/registrations"

    /**
     * The attendee's own notification list. `title`/`message` bodies are persisted deliberately: they
     * are the user's own data, app-private (no-backup), read back for at most [MAX_AGE_MS] and wiped on
     * logout — never a leak, and never the reason to skip the paint.
     */
    const val NOTIFICATIONS = "notifications/mine"

    val SUMMARY_TYPE: Type = DashboardSummary::class.java
    val UPCOMING_TYPE: Type = listType(DashboardUpcomingEvent::class.java)
    val EVENTS_TYPE: Type = listType(AttendeeEventResponse::class.java)
    val REGISTRATIONS_TYPE: Type = listType(RegistrationResponse::class.java)
    val NOTIFICATIONS_TYPE: Type = listType(NotificationResponse::class.java)

    private fun listType(element: Class<*>): Type = TypeToken.getParameterized(List::class.java, element).type
}
