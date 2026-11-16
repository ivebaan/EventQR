package com.thedavelopers.eventqr.core.session

import android.content.Context
import com.google.gson.JsonParser
import com.thedavelopers.eventqr.core.api.ApiClient
import com.thedavelopers.eventqr.core.api.ApiService
import com.thedavelopers.eventqr.core.api.NetworkResult
import com.thedavelopers.eventqr.core.api.SessionTokenRotator
import com.thedavelopers.eventqr.core.api.SessionTokenRotator.Rotation
import com.thedavelopers.eventqr.core.api.dto.AccountRole
import com.thedavelopers.eventqr.core.api.safeApiCall
import com.thedavelopers.eventqr.core.util.RoleMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Base64

/**
 * Refreshes the signed-in session in the background right after launch, so the dashboard opens on the cached
 * session instead of waiting for the network. Runs on an application-level scope because the launching activity
 * finishes as soon as it has routed.
 *
 * A refresh rotates the token pair (POST auth/refresh) so the access token carries the CURRENT database role
 * (e.g. an attendee upgraded to organizer after approval), then reads auth/me to store that role and profile.
 */
object SessionRefresher {
    /**
     * The forced path's window ([refreshNow]): a refresh within this window is skipped, so callers that
     * must have the current role now still spend at most one refresh token a minute. The screen-resume
     * path uses the much wider [RESUME_REFRESH_INTERVAL_MS] instead.
     */
    const val MIN_REFRESH_INTERVAL_MS = 60_000L

    /**
     * The resume path's window ([refreshIfStale]): a screen that resumes inside it reuses the session it
     * already has. Only an expiring/missing token, or a caller that must be forced (a 401 or an approved
     * decision), refreshes sooner. Both windows are measured from the last successful rotation.
     */
    const val RESUME_REFRESH_INTERVAL_MS = 10 * 60_000L

    /**
     * The escalation window: regardless of [RESUME_REFRESH_INTERVAL_MS], an access token with less than
     * this left is refreshed on the next resume, so a screen never opens on a token about to expire.
     */
    internal const val EXPIRY_SKEW_MS = 2 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class Result(
        val rotation: Rotation,
        /**
         * The normalized role when auth/me showed a change the rotation itself didn't (that one is reported by
         * [SessionTokenRotator]), else null. Already passed to [SessionEvents.notifyRoleChanged] by [refreshNow].
         */
        val changedRole: String?,
    )

    fun refresh(context: Context) {
        val appContext = context.applicationContext
        scope.launch { refreshNow(appContext) }
    }

    /**
     * Rotates the session now and stores the current role and profile, for callers that need the current
     * role immediately: 401 recovery and an approved decision. Throttled only by [MIN_REFRESH_INTERVAL_MS];
     * the screen-resume path must use [refreshIfStale], which skips for [RESUME_REFRESH_INTERVAL_MS].
     * A role change goes to [SessionEvents.notifyRoleChanged] exactly once: from the rotation when the
     * refresh response carried the new role, otherwise from auth/me. Being offline keeps the cached
     * session; a rejected refresh token expires it via [SessionEvents].
     */
    suspend fun refreshNow(context: Context): Result =
        refresh(context.applicationContext, MIN_REFRESH_INTERVAL_MS)

    /**
     * The screen-resume path: rotates only when the session is older than [RESUME_REFRESH_INTERVAL_MS] or the
     * access token is close to expiry, so returning to a dashboard does not spend a refresh token each time.
     * The "last successful refresh" timestamp is the rotator's, so a rotation already done for this resume (by
     * launch, or by a 401) is not repeated.
     */
    suspend fun refreshIfStale(context: Context): Result = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        refreshIfStale(
            ApiClient.sessionRotator(appContext),
            ApiClient.getService(appContext),
            SessionManager(appContext),
            System.currentTimeMillis(),
        )
    }

    /** The same decision against an injected rotator/api (unit tests), so no singletons are involved. */
    internal suspend fun refreshIfStale(
        rotator: SessionTokenRotator,
        api: ApiService,
        sessionManager: SessionManager,
        nowMs: Long,
    ): Result = reportRoleChange(
        refreshSession(
            rotator,
            api,
            sessionManager,
            resumeIntervalMs(sessionManager.getAuthToken(), nowMs),
        ),
    )

    private suspend fun refresh(appContext: Context, minIntervalMs: Long): Result = reportRoleChange(
        refreshSession(
            ApiClient.sessionRotator(appContext),
            ApiClient.getService(appContext),
            SessionManager(appContext),
            minIntervalMs,
        ),
    )

    /** A role change the rotation already carried is not repeated, hence [Result.changedRole] alone. */
    private fun reportRoleChange(result: Result): Result = result.also { done ->
        done.changedRole?.let(SessionEvents::notifyRoleChanged)
    }

    /**
     * 0 = always refresh (no token, or one that expires within [EXPIRY_SKEW_MS]), else
     * [RESUME_REFRESH_INTERVAL_MS]. A token whose expiry cannot be read is treated as a normal one: it is still
     * replaced once the window elapses.
     */
    internal fun resumeIntervalMs(accessToken: String?, nowMs: Long): Long {
        if (accessToken.isNullOrBlank()) return 0L
        val expiresAtMs = tokenExpiryMs(accessToken) ?: return RESUME_REFRESH_INTERVAL_MS
        return if (expiresAtMs - nowMs <= EXPIRY_SKEW_MS) 0L else RESUME_REFRESH_INTERVAL_MS
    }

    /** The JWT `exp` claim in epoch millis, or null when the token is not a readable JWT. */
    internal fun tokenExpiryMs(token: String): Long? = runCatching {
        val payload = token.split('.').getOrNull(1) ?: return null
        val json = String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
        JsonParser.parseString(json).asJsonObject.get("exp").asLong * 1000
    }.getOrNull()

    internal suspend fun refreshSession(
        rotator: SessionTokenRotator,
        api: ApiService,
        sessionManager: SessionManager,
        minIntervalMs: Long,
    ): Result {
        // The rotation stores (and reports) the role its response carried.
        val rotation = withContext(Dispatchers.IO) { rotator.rotate(minIntervalMs) }
        if (rotation != Rotation.ROTATED) return Result(rotation, null)
        var changedRole: String? = null
        (safeApiCall { api.getAuthMe() } as? NetworkResult.Success)?.data?.let { me ->
            // Gson leaves an unknown enum value null despite the non-null type.
            val role: AccountRole? = me.role
            if (role != null) {
                // Compared with the role as of after the rotation, so a change it already reported isn't repeated.
                val before = RoleMapper.normalizeRole(sessionManager.getUserRole())
                val after = RoleMapper.normalizeRole(role.name)
                sessionManager.saveRole(role)
                if (before.isNotEmpty() && after.isNotEmpty() && after != before) changedRole = after
            }
            sessionManager.updateProfile(me.fullName, me.phoneNumber)
        }
        return Result(rotation, changedRole)
    }
}
