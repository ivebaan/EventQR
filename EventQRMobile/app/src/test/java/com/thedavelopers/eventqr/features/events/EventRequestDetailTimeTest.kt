package com.thedavelopers.eventqr.features.events

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thedavelopers.eventqr.core.api.dto.EventRequestStatus
import com.thedavelopers.eventqr.core.format.formatEventTime
import com.thedavelopers.eventqr.core.format.formatRegistrationWindow
import com.thedavelopers.eventqr.features.events.model.dto.EventRequestResponse
import com.thedavelopers.eventqr.ui.theme.EventQrTheme
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * §6.5 / §6.2 / §6.4: the request detail prints EVENT TIME and REGISTRATION WINDOW through the same
 * two helpers the attendee event detail and the organizer event details read, so one pair of
 * instants cannot read two ways one tap apart. Instants are UTC; the event zone is Manila (UTC+8).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class EventRequestDetailTimeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Manila 13 Nov 2026 00:20. */
    private val start = Instant.parse("2026-11-12T16:20:00Z")

    /** Manila 13 Nov 2026 12:20 PM - the same calendar day as [start]. */
    private val sameDayEnd = Instant.parse("2026-11-13T04:20:00Z")

    /** Manila 14 Nov 2026 1:00 AM - a different calendar day. */
    private val nextDayEnd = Instant.parse("2026-11-13T17:00:00Z")

    /** Manila 1 May 2026 9:00 AM. */
    private val windowOpen = Instant.parse("2026-05-01T01:00:00Z")

    /** Manila 1 Jun 2026 11:59 PM. */
    private val windowClose = Instant.parse("2026-06-01T15:59:00Z")

    private fun request(
        startAt: Instant? = start,
        endAt: Instant? = sameDayEnd,
        registeredFrom: Instant? = windowOpen,
        registeredTo: Instant? = windowClose,
    ) = EventRequestResponse(
        eventRequestId = UUID.randomUUID(),
        requesterUserId = UUID.randomUUID(),
        eventName = "DevFest Manila",
        startDateTime = startAt,
        endDateTime = endAt,
        registrationStartDateTime = registeredFrom,
        registrationEndDateTime = registeredTo,
        status = EventRequestStatus.PENDING,
    )

    @Test
    fun durationValue_isTheSharedFormatterOutput() {
        val request = request()

        assertEquals(
            formatEventTime(context, start, sameDayEnd),
            eventRequestTime(context, request),
        )
        // Pinned literally too: a change to either formatter has to be deliberate here.
        assertEquals(
            "13\u00A0Nov\u00A02026, 12:20\u00A0AM \u2013\u00A012:20\u00A0PM",
            eventRequestTime(context, request),
        )
    }

    @Test
    fun registrationValue_isTheSharedFormatterOutput() {
        val request = request()

        assertEquals(
            formatRegistrationWindow(context, windowOpen, windowClose),
            eventRequestRegistrationWindow(context, request),
        )
        assertEquals(
            "1\u00A0May\u00A02026, 9:00\u00A0AM\n\u2013\u00A01\u00A0Jun\u00A02026, 11:59\u00A0PM",
            eventRequestRegistrationWindow(context, request),
        )
    }

    /**
     * A range across two days breaks once, after the start datetime, so no line ever holds half a
     * timestamp: line 1 is a whole `d MMM yyyy, h:mm a` and line 2 opens with the en dash.
     */
    @Test
    fun differentDayRange_neverSplitsATimestamp() {
        val request = request(endAt = nextDayEnd)

        val value = requireNotNull(eventRequestTime(context, request))
        val lines = value.split("\n")

        assertEquals(2, lines.size)
        assertWholeDateTime(lines[0])
        assertTrue("line 2 opens with the dash", lines[1].startsWith("\u2013\u00A0"))
        assertWholeDateTime(lines[1].removePrefix("\u2013\u00A0"))
    }

    /** No start hides the row and its hairline, exactly as the other two event details do. */
    @Test
    fun noStart_hidesTheEventTimeRow() {
        assertNull(eventRequestTime(context, request(startAt = null)))
    }

    /** Neither end of the window hides the row; one end alone still prints. */
    @Test
    fun registrationWindow_hidesOnlyWithNoEndsAtAll() {
        assertNull(eventRequestRegistrationWindow(context, request(registeredFrom = null, registeredTo = null)))
        assertEquals(
            "1\u00A0Jun\u00A02026, 11:59\u00A0PM",
            eventRequestRegistrationWindow(context, request(registeredFrom = null)),
        )
    }

    /** The screen renders those two values as its label + value rows, for an admin. */
    @Test
    fun screenRendersBothRangesThroughTheSharedFormatter() {
        val request = request(endAt = nextDayEnd)
        composeTestRule.setContent {
            EventQrTheme {
                EventRequestDetailScreen(
                    request = request,
                    isLoading = false,
                    errorMessage = null,
                    isAdmin = true,
                    onRetryClick = {},
                )
            }
        }

        composeTestRule
            .onNodeWithText(requireNotNull(eventRequestTime(context, request)), useUnmergedTree = true)
            .assertExists()
        composeTestRule
            .onNodeWithText(requireNotNull(eventRequestRegistrationWindow(context, request)), useUnmergedTree = true)
            .assertExists()
    }

    private fun assertWholeDateTime(line: String) {
        assertTrue(
            "expected a whole `d MMM yyyy, h:mm a` datetime, got `$line`",
            Regex("^\\d{1,2}\u00A0[A-Z][a-z]{2}\u00A0\\d{4}, \\d{1,2}:\\d{2}\u00A0[AP]M$").matches(line),
        )
    }
}
