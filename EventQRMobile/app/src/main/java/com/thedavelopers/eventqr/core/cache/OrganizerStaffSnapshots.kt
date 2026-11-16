package com.thedavelopers.eventqr.core.cache

import com.google.gson.reflect.TypeToken
import com.thedavelopers.eventqr.features.organizer.OrganizerMvpAttendee
import com.thedavelopers.eventqr.features.organizer.OrganizerMvpEvent
import com.thedavelopers.eventqr.features.organizer.OrganizerMvpScanPurpose
import com.thedavelopers.eventqr.features.organizer.OrganizerMvpStaff
import com.thedavelopers.eventqr.features.organizer.OrganizerMvpTransaction
import com.thedavelopers.eventqr.features.reports.model.dto.EventReportSummaryDto
import com.thedavelopers.eventqr.features.rewards.model.dto.RewardRedemptionResponse
import com.thedavelopers.eventqr.features.rewards.model.dto.RewardResponse
import com.thedavelopers.eventqr.features.staff.model.dto.StaffAssignedEventResponse
import com.thedavelopers.eventqr.features.staff.model.dto.StaffTodaySummary
import com.thedavelopers.eventqr.features.transactions.model.dto.TransactionResponse
import java.lang.reflect.Type

/**
 * One day, the window every persisted screen snapshot shares
 * ([AttendeeSnapshots.MAX_AGE_MS] holds the same value for the attendee keys).
 */
const val SNAPSHOT_MAX_AGE_MS = 24L * 60 * 60 * 1000

/**
 * The keys and Gson types of the organizer snapshots.
 *
 * One table because the writer ([com.thedavelopers.eventqr.features.organizer.OrganizerRepository])
 * and the invalidators (its own mutations, plus the staff scan and redemption paths) live apart: a
 * mutation has to drop every key a screen could have painted from.
 *
 * `organizer/events` is the whole `GET organizer/events` answer: the manage-events list and the
 * dashboard's rows and its client-side derived numbers (plan §6.4) all come from it.
 */
object OrganizerSnapshots {
    const val MAX_AGE_MS = SNAPSHOT_MAX_AGE_MS

    const val EVENTS = "organizer/events"

    /**
     * The per-event sub-screen answers. Every key ends in the event id, so a snapshot painted for one
     * event can never be served for another (the cache path already carries the user id).
     */
    fun attendees(eventId: String) = "organizer/attendees/$eventId"

    /** `GET organizer/events/{id}/reports/summary`: the on-open totals, never generated report rows. */
    fun reports(eventId: String) = "organizer/reports/$eventId"

    fun rewards(eventId: String) = "organizer/rewards/$eventId"
    fun claimedRewards(eventId: String) = "organizer/rewards/$eventId/claimed"
    fun rewardSettings(eventId: String) = "organizer/rewards/$eventId/settings"

    fun scanPurposes(eventId: String) = "organizer/scan-purposes/$eventId"
    fun logs(eventId: String) = "organizer/logs/$eventId"
    fun staff(eventId: String) = "organizer/staff/$eventId"

    val EVENTS_TYPE: Type = listType(OrganizerMvpEvent::class.java)
    val ATTENDEES_TYPE: Type = listType(OrganizerMvpAttendee::class.java)
    val REPORTS_TYPE: Type = EventReportSummaryDto::class.java
    val REWARDS_TYPE: Type = listType(RewardResponse::class.java)
    val CLAIMED_REWARDS_TYPE: Type = listType(RewardRedemptionResponse::class.java)
    val REWARD_SETTINGS_TYPE: Type = Boolean::class.java
    val SCAN_PURPOSES_TYPE: Type = listType(OrganizerMvpScanPurpose::class.java)
    val LOGS_TYPE: Type = listType(OrganizerMvpTransaction::class.java)
    val STAFF_TYPE: Type = listType(OrganizerMvpStaff::class.java)

    private fun listType(element: Class<*>): Type = TypeToken.getParameterized(List::class.java, element).type
}

/**
 * The keys and Gson types of the staff snapshots.
 *
 * The day numbers, the recent scans and the assigned-event list are three answers, so a scan that
 * moves today's counts drops the two `staff/dashboard/today-...` keys without touching the assignments.
 */
object StaffSnapshots {
    const val MAX_AGE_MS = SNAPSHOT_MAX_AGE_MS

    const val ASSIGNMENTS = "staff/assignments"
    const val TODAY_SUMMARY = "staff/dashboard/today-summary"
    const val TODAY_TRANSACTIONS = "staff/dashboard/today-transactions"

    val ASSIGNMENTS_TYPE: Type = listType(StaffAssignedEventResponse::class.java)
    val SUMMARY_TYPE: Type = StaffTodaySummary::class.java
    val TRANSACTIONS_TYPE: Type = listType(TransactionResponse::class.java)

    private fun listType(element: Class<*>): Type = TypeToken.getParameterized(List::class.java, element).type
}
