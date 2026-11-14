package com.thedavelopers.eventqr.ui.components

import com.thedavelopers.eventqr.features.organizer.pickerOrdered
import com.thedavelopers.eventqr.features.organizer.testOrganizerEvent
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The picker's pure halves: the name filter and the nearest-date sort (plan 5.2 "Event picker"). */
class EventPickerFilterTest {

    private val options = listOf(
        EventPickerOption("1", "Night Market"),
        EventPickerOption("2", "Day Fair"),
        EventPickerOption("3", "Untitled Event"),
    )

    @Test
    fun filter_isCaseInsensitive() {
        assertEquals(listOf("Night Market"), filterEventOptions(options, "NIGHT").map { it.title })
        assertEquals(listOf("Night Market"), filterEventOptions(options, "market").map { it.title })
    }

    @Test
    fun filter_matchesAMidWordSubstring() {
        assertEquals(listOf("Day Fair"), filterEventOptions(options, "y fa").map { it.title })
    }

    @Test
    fun filter_blankReturnsEveryOption() {
        assertEquals(options, filterEventOptions(options, ""))
        assertEquals(options, filterEventOptions(options, "   "))
    }

    @Test
    fun filter_trimsTheQuery() {
        assertEquals(listOf("Day Fair"), filterEventOptions(options, "  fair  ").map { it.title })
    }

    @Test
    fun filter_matchesTheDisplayedUntitledFallback() {
        // Hosts map a blank backend title to the unified "Untitled Event" label before building options.
        assertEquals(listOf("Untitled Event"), filterEventOptions(options, "untitled").map { it.title })
    }

    @Test
    fun filter_noMatchReturnsEmpty() {
        assertTrue(filterEventOptions(options, "zzz").isEmpty())
    }

    @Test
    fun filter_doesNotMutateTheInputList() {
        filterEventOptions(options, "night")
        assertEquals(3, options.size)
        assertEquals("Night Market", options.first().title)
    }

    @Test
    fun sort_putsUpcomingNearestFirst_thenEndedNearestFirst() {
        val now = Instant.now()
        val farFuture = testOrganizerEvent("Far future").copy(
            eventStartAt = now.plusSeconds(20 * 86_400),
            eventEndAt = now.plusSeconds(20 * 86_400),
        )
        val soon = testOrganizerEvent("Soon").copy(
            eventStartAt = now.plusSeconds(2 * 86_400),
            eventEndAt = now.plusSeconds(2 * 86_400),
        )
        val longEnded = testOrganizerEvent("Long ended").copy(
            eventStartAt = now.minusSeconds(20 * 86_400),
            eventEndAt = now.minusSeconds(19 * 86_400),
        )
        val recentlyEnded = testOrganizerEvent("Recently ended").copy(
            eventStartAt = now.minusSeconds(5 * 86_400),
            eventEndAt = now.minusSeconds(4 * 86_400),
        )

        val ordered = listOf(farFuture, longEnded, soon, recentlyEnded).pickerOrdered()

        assertEquals(
            listOf("Soon", "Far future", "Recently ended", "Long ended"),
            ordered.map { it.title },
        )
    }

    @Test
    fun sort_keepsTheIncomingOrderForEqualDates() {
        val now = Instant.now()
        val first = testOrganizerEvent("First").copy(
            eventStartAt = now.plusSeconds(86_400),
            eventEndAt = now.plusSeconds(86_400),
        )
        val second = testOrganizerEvent("Second").copy(
            eventStartAt = now.plusSeconds(86_400),
            eventEndAt = now.plusSeconds(86_400),
        )

        assertEquals(listOf("First", "Second"), listOf(first, second).pickerOrdered().map { it.title })
    }
}
