package com.thedavelopers.eventqr.features.registrations

import androidx.annotation.VisibleForTesting
import com.thedavelopers.eventqr.features.registrations.model.dto.RegistrationResponse
import java.time.Duration
import java.time.Instant

object RegistrationsCache {

    private val freshness = Duration.ofSeconds(30)

    /** Test seam: the cache's only clock, so a test can prove the TTL. */
    @VisibleForTesting
    internal var clock: () -> Instant = { Instant.now() }

    @Volatile
    private var registrations: List<RegistrationResponse>? = null

    @Volatile
    private var fetchedAt: Instant? = null

    /** The user the stored answer belongs to; never served to a different signed-in user. */
    @Volatile
    private var ownerId: String? = null

    /** [registrations] when they belong to [userId]; null for another user's answer or none. */
    fun get(userId: String?): List<RegistrationResponse>? =
        registrations.takeIf { ownerId == userId }

    /**
     * Whether [registrations] is a full answer for [userId] inside the TTL. A partial merge never
     * stamps [fetchedAt], so it is never fresh.
     */
    fun isFresh(userId: String?, now: Instant = clock()): Boolean {
        if (ownerId != userId) return false
        val fetched = fetchedAt ?: return false
        return !now.isAfter(fetched.plus(freshness))
    }

    /**
     * The stored answer when it is a full, fresh answer for [userId] and nothing else. Null (stale, or
     * another user's answer) means the caller must fetch. A fetched-empty list IS a full answer: the
     * attendee has no registrations, so readers must not refetch it just because it is empty.
     */
    fun fresh(userId: String?, now: Instant = clock()): List<RegistrationResponse>? =
        registrations.takeIf { isFresh(userId, now) }

    @Synchronized
    fun set(items: List<RegistrationResponse>, ownerId: String?) {
        registrations = items
        fetchedAt = clock()
        this.ownerId = ownerId
    }

    /**
     * Merges one changed registration (register/cancel) into the list without ever marking it
     * fresh: a merge into a null cache seeds a partial list that is missing the attendee's other
     * registrations, so the next reader must still refetch. Only [set] receives a full answer and
     * may stamp [fetchedAt].
     */
    @Synchronized
    fun addRegistration(registration: RegistrationResponse) {
        registrations = (registrations ?: emptyList())
            .filter { it.registrationId != registration.registrationId } + registration
    }

    fun clear() {
        synchronized(this) {
            registrations = null
            fetchedAt = null
            ownerId = null
        }
    }
}
