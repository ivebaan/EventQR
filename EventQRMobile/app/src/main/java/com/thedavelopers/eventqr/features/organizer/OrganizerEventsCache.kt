package com.thedavelopers.eventqr.features.organizer

/**
 * In-memory copy of the caller's `GET organizer/events` answer, shared by every [OrganizerRepository] (each screen
 * builds its own) so opening several organizer screens in a row does not re-download the list. Entries are keyed by
 * the signed-in user id, so an account switch never serves the previous user's rows. Only successful responses are
 * kept; they expire after [ttlMs]. Cleared by [OrganizerRepository.invalidateEvents] (any organizer mutation and
 * pull-to-refresh) and whenever the session changes.
 */
class OrganizerEventsCache(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Entry(val userId: String, val events: List<OrganizerMvpEvent>, val storedAtMs: Long)

    @Volatile
    private var entry: Entry? = null

    /** Bumped by [clear]; a fetch started before a clear must not store its result afterwards. */
    @Volatile
    private var generation = 0

    /** Read before starting a fetch and passed back to [put]. */
    fun generation(): Int = generation

    /**
     * The cached list for [userId], or null when nothing is cached, it belongs to someone else, it is stale, or
     * [userId] is blank (a cleared session must never be served a snapshot).
     */
    fun get(userId: String?): List<OrganizerMvpEvent>? {
        if (userId.isNullOrBlank()) return null
        val current = entry ?: return null
        if (current.userId != userId) return null
        return if (clock() - current.storedAtMs < ttlMs) current.events else null
    }

    /**
     * Stores [events] unless [clear] ran since [fetchGeneration] was read (a stale previous-session result), or
     * [userId] is blank: a read made without a session is never cached under "nobody".
     */
    @Synchronized
    fun put(userId: String?, events: List<OrganizerMvpEvent>, fetchGeneration: Int = generation) {
        if (userId.isNullOrBlank() || fetchGeneration != generation) return
        entry = Entry(userId, events, clock())
    }

    @Synchronized
    fun clear() {
        generation++
        entry = null
    }

    companion object {
        const val DEFAULT_TTL_MS = 60_000L

        /** The process-wide instance used by the app. */
        val shared = OrganizerEventsCache()
    }
}
