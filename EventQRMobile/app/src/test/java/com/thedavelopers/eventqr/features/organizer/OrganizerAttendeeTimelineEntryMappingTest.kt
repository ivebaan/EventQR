package com.thedavelopers.eventqr.features.organizer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.JsonParser
import com.thedavelopers.eventqr.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The attendee timeline names booth/session rows by their purpose, so the entry mapping must carry
 * the backend's nullable `scanPurposeName` through to the label. Older backends omit it and simply
 * yield the generic label.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class OrganizerAttendeeTimelineEntryMappingTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun entry(json: String) = JsonParser.parseString(json).toMvpTransactionEntry()

    @Test
    fun objectEntryCarriesTheScanPurposeName() {
        val mapped = entry("""{"type":"BOOTH_VISIT","timestamp":"2026-01-01T10:00:00Z","scanPurposeName":"Booth A"}""")
        assertEquals("BOOTH_VISIT", mapped?.type)
        assertEquals("Booth A", mapped?.scanPurposeName)
    }

    @Test
    fun objectEntryWithoutTheScanPurposeNameIsNull() {
        assertNull(entry("""{"type":"BOOTH_VISIT","timestamp":"2026-01-01T10:00:00Z"}""")?.scanPurposeName)
        assertNull(entry("""{"type":"BOOTH_VISIT","scanPurposeName":null}""")?.scanPurposeName)
    }

    @Test
    fun blankScanPurposeNameIsDropped() {
        assertNull(entry("""{"type":"BOOTH_VISIT","scanPurposeName":"   "}""")?.scanPurposeName)
        assertNull(entry("""{"type":"BOOTH_VISIT","scanPurposeName":""}""")?.scanPurposeName)
    }

    @Test
    fun nonStringScanPurposeNameIsDropped() {
        assertNull(entry("""{"type":"BOOTH_VISIT","scanPurposeName":123}""")?.scanPurposeName)
        assertNull(entry("""{"type":"BOOTH_VISIT","scanPurposeName":true}""")?.scanPurposeName)
        assertNull(entry("""{"type":"BOOTH_VISIT","scanPurposeName":{"name":"Booth A"}}""")?.scanPurposeName)
    }

    @Test
    fun bareStringEntryKeepsTheTypeOnly() {
        val mapped = entry(""""ENTRY"""")
        assertEquals("ENTRY", mapped?.type)
        assertNull(mapped?.scanPurposeName)
        assertNull(mapped?.timestamp)
    }

    /** Pins that the parsed field actually reaches the timeline label. */
    @Test
    fun parsedScanPurposeNameReachesTheTimelineLabel() {
        val mapped = entry("""{"type":"BOOTH_VISIT","timestamp":"2026-01-01T10:00:00Z","scanPurposeName":"Booth A"}""")
        assertEquals("Booth A", transactionTypeLabel(context, mapped!!.type, mapped.scanPurposeName))
    }

    @Test
    fun missingScanPurposeNameYieldsTheGenericTimelineLabel() {
        val mapped = entry("""{"type":"BOOTH_VISIT","timestamp":"2026-01-01T10:00:00Z"}""")
        assertEquals(
            context.getString(R.string.txn_type_booth_session_visit),
            transactionTypeLabel(context, mapped!!.type, mapped.scanPurposeName),
        )
        assertEquals("Booth/Session Visit", transactionTypeLabel(context, mapped.type, mapped.scanPurposeName))
    }
}
