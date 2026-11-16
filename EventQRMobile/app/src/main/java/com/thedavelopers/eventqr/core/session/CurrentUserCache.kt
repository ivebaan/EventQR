package com.thedavelopers.eventqr.core.session

import com.thedavelopers.eventqr.core.api.NetworkResult
import com.thedavelopers.eventqr.features.users.model.dto.UserResponse
import java.time.Duration
import java.time.Instant

/**
 * Process-memory copy of `GET users/me`, so the dashboard and the profile screen share one answer
 * instead of each opening with its own request. Owner-scoped, cleared on logout and on
 * login/account switch, and kept short-lived because a role change must show up quickly.
 */
object CurrentUserCache {
    private val freshness = Duration.ofSeconds(30)

    @Volatile
    private var user: UserResponse? = null

    @Volatile
    private var ownerId: String? = null

    @Volatile
    private var fetchedAt: Instant? = null

    /** Bumped by [invalidate]; a fetch started before it must not store its result afterwards. */
    @Volatile
    private var generation = 0

    /** Read before starting a fetch and passed back to [set]. */
    fun generation(): Int = generation

    /** The stored answer when it is fresh and belongs to [userId]; null means fetch. */
    fun fresh(userId: String?, now: Instant = Instant.now()): UserResponse? {
        if (userId.isNullOrBlank() || ownerId != userId) return null
        val fetched = fetchedAt ?: return null
        val stored = user ?: return null
        return stored.takeIf { !now.isAfter(fetched.plus(freshness)) }
    }

    /**
     * Stores [user] unless [invalidate] ran since [fetchGeneration] was read: a request that started
     * before a saved profile edit must not stamp the pre-edit answer as fresh.
     */
    @Synchronized
    fun set(userId: String?, user: UserResponse, fetchGeneration: Int = generation) {
        if (fetchGeneration != generation) return
        this.user = user
        ownerId = userId
        fetchedAt = Instant.now()
    }

    /** Drops the stored copy: a saved profile edit, logout, or an account switch. */
    @Synchronized
    fun invalidate() {
        generation++
        user = null
        ownerId = null
        fetchedAt = null
    }
}

/**
 * `users/me` through [CurrentUserCache]: a fresh answer for [userId] is returned without a request,
 * a miss is fetched and stored (unless the cache was invalidated while the request was in flight).
 */
internal suspend fun cachedCurrentUser(
    userId: String?,
    fetch: suspend () -> NetworkResult<UserResponse>,
): NetworkResult<UserResponse> {
    CurrentUserCache.fresh(userId)?.let { return NetworkResult.Success(it) }
    val fetchGeneration = CurrentUserCache.generation()
    val result = fetch()
    if (result is NetworkResult.Success) CurrentUserCache.set(userId, result.data, fetchGeneration)
    return result
}
