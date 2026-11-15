package com.thedavelopers.eventqr.features.organizer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thedavelopers.eventqr.R
import com.thedavelopers.eventqr.core.api.dto.TransactionResult
import com.thedavelopers.eventqr.core.api.dto.TransactionType
import com.thedavelopers.eventqr.features.organizer.model.dto.OrganizerTransactionDto
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Booth/session rows are titled by their purpose name in the organizer log; every other type keeps
 * the display label, and the backend's raw-code fallback never renders as a name.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class OrganizerTransactionBoothTitleTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun dto(type: TransactionType, purpose: String?) = OrganizerTransactionDto(
        transactionId = UUID.randomUUID(),
        eventId = UUID.randomUUID(),
        scanPurpose = purpose,
        transactionType = type,
        resultStatus = TransactionResult.APPROVED,
    )

    @Test
    fun boothVisitRowIsTitledByThePurposeName() {
        val mapped = dto(TransactionType.BOOTH_VISIT, "Booth A").toMvpTransaction(context, "Test event")
        assertEquals("Booth A", mapped.type)
    }

    @Test
    fun boothVisitRowFallsBackToTheTypeLabelForTheRawCode() {
        val mapped = dto(TransactionType.BOOTH_VISIT, "BOOTH_VISIT").toMvpTransaction(context, "Test event")
        assertEquals(context.getString(R.string.organizer_repo_type_booth_session_visit), mapped.type)
        assertEquals("Booth/Session Visit", mapped.type)
    }

    @Test
    fun nonBoothRowKeepsTheTypeLabel() {
        val mapped = dto(TransactionType.ENTRY, "Booth A").toMvpTransaction(context, "Test event")
        assertEquals(context.getString(R.string.organizer_repo_type_entry), mapped.type)
    }
}
