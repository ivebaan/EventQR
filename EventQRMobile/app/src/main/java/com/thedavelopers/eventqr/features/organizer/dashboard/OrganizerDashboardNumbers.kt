package com.thedavelopers.eventqr.features.organizer.dashboard

import com.thedavelopers.eventqr.features.organizer.OrganizerMvpEvent
import java.time.Instant

/**
 * The organizer dashboard's six numbers (plan §6.4), all derived from the existing
 * `GET /api/v1/organizer/events` response - no extra call.
 *
 * A `null` means "not known" (the load failed) and the screen prints `--`; a known zero prints
 * `0`. Zero is a fact and unknown is not, so the two never share a glyph.
 *
 * `Attended` is the label, never `Checked in`: checked-in is ENTERED-based in Reports, attendee
 * management and the staff counts.
 */
data class OrganizerDashboardNumbers(
    val registered: Long? = null,
    val attended: Long? = null,
    val liveNow: Long? = null,
    val upcoming: Long? = null,
    val ended: Long? = null,
    val redeemed: Long? = null,
    /** Attended / registered over ENDED events only, in whole percent; null when unknown or nothing has ended with registrations. */
    val attendancePercent: Long? = null,
    /** Sign-ups across events that have not started yet. */
    val registeredUpcoming: Long? = null,
    /** Attended so far across events that are live right now. */
    val attendedNow: Long? = null,
) {
    companion object {
        /**
         * @param events the organizer's approved/active/completed events, or null when their
         *   load failed. Every field is keyed off this one source, so a failed events load can
         *   never leave a lone number among `--` cells.
         */
        fun of(
            events: List<OrganizerMvpEvent>?,
            now: Instant = Instant.now(),
        ): OrganizerDashboardNumbers {
            val lifecycles = events?.map { lifecycleAt(it, now) }
            fun sumFor(life: Lifecycle, pick: (OrganizerMvpEvent) -> Long): Long? =
                events?.filterIndexed { i, _ -> lifecycles!![i] == life }?.sumOf(pick)
            val endedRegistered = sumFor(Lifecycle.ENDED) { it.registeredCount }
            val endedAttended = sumFor(Lifecycle.ENDED) { it.attendedCount }
            return OrganizerDashboardNumbers(
                attendancePercent = if (endedRegistered != null && endedAttended != null && endedRegistered > 0) {
                    (endedAttended * 100 / endedRegistered).coerceIn(0L, 100L)
                } else {
                    null
                },
                registeredUpcoming = sumFor(Lifecycle.UPCOMING) { it.registeredCount },
                attendedNow = sumFor(Lifecycle.LIVE) { it.attendedCount },
                registered = events?.sumOf { it.registeredCount },
                attended = events?.sumOf { it.attendedCount },
                liveNow = lifecycles?.count { it == Lifecycle.LIVE }?.toLong(),
                upcoming = lifecycles?.count { it == Lifecycle.UPCOMING }?.toLong(),
                ended = lifecycles?.count { it == Lifecycle.ENDED }?.toLong(),
                redeemed = events?.sumOf { it.rewardRedemptions },
            )
        }

        /**
         * The same rule as `OrganizerMvpEvent.lifecycleStatus()` for the statuses [of] accepts
         * (approved/active/completed are time-derived): past end = ended, future start =
         * upcoming, otherwise live. Draft, pending and cancelled events never reach the dashboard.
         */
        private fun lifecycleAt(event: OrganizerMvpEvent, now: Instant): Lifecycle = when {
            event.eventEndAt?.isBefore(now) == true -> Lifecycle.ENDED
            event.eventStartAt?.isAfter(now) == true -> Lifecycle.UPCOMING
            else -> Lifecycle.LIVE
        }
    }
}

private enum class Lifecycle { UPCOMING, LIVE, ENDED }
