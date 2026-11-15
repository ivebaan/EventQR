package com.thedavelopers.eventqr.features.transactions

import android.content.Context
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thedavelopers.eventqr.R
import com.thedavelopers.eventqr.core.api.dto.TransactionType
import com.thedavelopers.eventqr.features.organizer.transactionTypeLabel
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * An event can run several booth/session purposes, so those rows are titled by the purpose name.
 * Every other type keeps the generic type label, and a raw enum code never renders as a name.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class TransactionBoothPurposeTitleTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun attendeeBoothVisitRowUsesThePurposeName() {
        val view = TextView(context)
        assertEquals("Booth A", mapTransactionTitle(view, TransactionType.BOOTH_VISIT, "Booth A"))
        assertEquals("Booth A", mapTransactionTitle(view, TransactionType.BOOTH_VISIT, "  Booth A  "))
    }

    @Test
    fun attendeeBoothVisitRowFallsBackToTheTypeLabelWithoutAPurposeName() {
        val view = TextView(context)
        val label = context.getString(R.string.scan_purpose_type_booth_visit)
        assertEquals("Booth Visit", label)
        assertEquals(label, mapTransactionTitle(view, TransactionType.BOOTH_VISIT, null))
        assertEquals(label, mapTransactionTitle(view, TransactionType.BOOTH_VISIT, "   "))
    }

    @Test
    fun attendeeSessionVisitRowFollowsTheSameRule() {
        val view = TextView(context)
        assertEquals("Session 1", mapTransactionTitle(view, TransactionType.SESSION_VISIT, "Session 1"))
        assertEquals(
            context.getString(R.string.scan_purpose_type_session_visit),
            mapTransactionTitle(view, TransactionType.SESSION_VISIT, null),
        )
    }

    @Test
    fun attendeeNonBoothTypesIgnoreACustomPurposeName() {
        val view = TextView(context)
        assertEquals(
            context.getString(R.string.item_transaction_log_entry),
            mapTransactionTitle(view, TransactionType.ENTRY, "Booth A"),
        )
        assertEquals(
            context.getString(R.string.scan_purpose_type_session_attendance),
            mapTransactionTitle(view, TransactionType.ATTENDANCE, "Session 1"),
        )
    }

    @Test
    fun organizerBoothRowsUseThePurposeName() {
        val label = context.getString(R.string.organizer_repo_type_booth_session_visit)
        assertEquals("Booth A", boothPurposeTitle(TransactionType.BOOTH_VISIT, "Booth A", label))
        assertEquals("Booth B", boothPurposeTitle(TransactionType.SESSION_VISIT, "Booth B", label))
    }

    @Test
    fun organizerBoothRowsKeepTheTypeLabelWhenTheBackendSendsTheRawCode() {
        val label = context.getString(R.string.organizer_repo_type_booth_session_visit)
        assertEquals("Booth/Session Visit", label)
        assertEquals(label, boothPurposeTitle(TransactionType.BOOTH_VISIT, "BOOTH_VISIT", label))
        assertEquals(label, boothPurposeTitle(TransactionType.SESSION_VISIT, "SESSION_VISIT", label))
        assertEquals(label, boothPurposeTitle(TransactionType.BOOTH_VISIT, null, label))
        assertEquals(label, boothPurposeTitle(TransactionType.BOOTH_VISIT, "  ", label))
    }

    @Test
    fun aPurposeNamedAfterAnotherTypeCodeFallsBackToTheLabel() {
        val label = context.getString(R.string.organizer_repo_type_booth_session_visit)
        assertEquals(label, boothPurposeTitle(TransactionType.BOOTH_VISIT, "SESSION_VISIT", label))
        assertEquals(label, boothPurposeTitle(TransactionType.SESSION_VISIT, "ENTRY", label))
    }

    @Test
    fun organizerNonBoothTypesIgnoreACustomPurposeName() {
        assertEquals(
            "Entry",
            boothPurposeTitle(TransactionType.ENTRY, "Booth A", "Entry"),
        )
        assertEquals(
            "Attendance",
            boothPurposeTitle(TransactionType.ATTENDANCE, "Session 1", "Attendance"),
        )
    }

    /** Attendee-details timeline labels raw type codes, so the booth rule has to apply there too. */
    @Test
    fun attendeeTimelineLabelsBoothCodesByThePurposeName() {
        val generic = context.getString(R.string.txn_type_booth_session_visit)
        assertEquals("Booth/Session Visit", generic)
        assertEquals("Booth A", transactionTypeLabel(context, "BOOTH_VISIT", "Booth A"))
        assertEquals("Booth B", transactionTypeLabel(context, "SESSION_VISIT", "Booth B"))
        assertEquals(generic, transactionTypeLabel(context, "BOOTH_VISIT", null))
        assertEquals(generic, transactionTypeLabel(context, "SESSION_VISIT", null))
        assertEquals(generic, transactionTypeLabel(context, "BOOTH_VISIT", "  "))
        assertEquals(generic, transactionTypeLabel(context, "BOOTH_VISIT", "BOOTH_VISIT"))
        assertEquals(generic, transactionTypeLabel(context, "BOOTH_VISIT"))
    }

    @Test
    fun attendeeTimelineLabelsEveryOtherTypeUnchanged() {
        assertEquals(
            context.getString(R.string.txn_type_entry),
            transactionTypeLabel(context, "ENTRY", "Booth A"),
        )
        assertEquals(
            context.getString(R.string.txn_type_attendance),
            transactionTypeLabel(context, "ATTENDANCE", "Booth A"),
        )
    }
}
