package com.thedavelopers.eventqr.features.registrations

import com.thedavelopers.eventqr.core.api.dto.RegistrationStatus
import com.thedavelopers.eventqr.features.registrations.model.dto.RegistrationResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.UUID

/** The 30 s registrations cache (feature/registrations/RegistrationsCache.kt). */
class RegistrationsCacheTest {

    private val start = Instant.parse("2026-01-01T00:00:00Z")

    @Before
    fun setUp() {
        RegistrationsCache.clear()
        RegistrationsCache.clock = { start }
    }

    @After
    fun tearDown() {
        RegistrationsCache.clear()
        RegistrationsCache.clock = { Instant.now() }
    }

    private fun registration(title: String = "Event") = RegistrationResponse(
        registrationId = UUID.randomUUID(),
        eventId = UUID.randomUUID(),
        attendeeUserId = UUID.randomUUID(),
        attendeeEmail = "a@test.com",
        attendeeName = "A",
        status = RegistrationStatus.REGISTERED,
        eventTitle = title,
    )

    @Test
    fun aFreshFullAnswerForTheSameUserIsServed() {
        val items = listOf(registration())
        RegistrationsCache.set(items, "user-1")

        assertEquals(items, RegistrationsCache.fresh("user-1", start))
    }

    @Test
    fun afterTheTtlTheAnswerIsNotServed() {
        RegistrationsCache.set(listOf(registration()), "user-1")

        // The window is inclusive at 30 s, as before.
        assertEquals(1, RegistrationsCache.fresh("user-1", start.plusSeconds(30))?.size)
        assertNull(RegistrationsCache.fresh("user-1", start.plusSeconds(31)))
    }

    @Test
    fun anotherUsersAnswerIsNeverServed() {
        RegistrationsCache.set(listOf(registration()), "user-1")

        assertNull(RegistrationsCache.fresh("user-2", start))
        assertNull(RegistrationsCache.fresh(null, start))
    }

    @Test
    fun anEmptyFullAnswerIsServed_notRefetched() {
        RegistrationsCache.set(emptyList(), "user-1")

        // A fetched-zero is a full answer: the attendee has no registrations right now.
        assertEquals(emptyList<RegistrationResponse>(), RegistrationsCache.fresh("user-1", start))
        assertNull("but never to another user", RegistrationsCache.fresh("user-2", start))
    }

    @Test
    fun aPartialMergeNeverBecomesAFreshAnswer() {
        RegistrationsCache.clear()
        RegistrationsCache.addRegistration(registration())

        assertNull(RegistrationsCache.fresh("user-1", start))
        assertEquals(1, RegistrationsCache.get(null)?.size)
    }

    @Test
    fun getAndIsFreshAreOwnerAware() {
        RegistrationsCache.set(listOf(registration()), "user-1")

        assertEquals(1, RegistrationsCache.get("user-1")?.size)
        assertNull(RegistrationsCache.get("user-2"))
        assertTrue(RegistrationsCache.isFresh("user-1", start))
        assertFalse(RegistrationsCache.isFresh("user-2", start))
    }

    @Test
    fun clearDropsEverything() {
        RegistrationsCache.set(listOf(registration()), "user-1")
        RegistrationsCache.clear()

        assertNull(RegistrationsCache.get(null))
        assertNull(RegistrationsCache.fresh("user-1", start))
    }
}
