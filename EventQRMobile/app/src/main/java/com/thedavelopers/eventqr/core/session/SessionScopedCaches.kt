package com.thedavelopers.eventqr.core.session

import androidx.annotation.VisibleForTesting
import java.util.concurrent.CopyOnWriteArrayList

/** A process-level cache whose content belongs to the signed-in user and must die with that session. */
fun interface SessionScopedCache {
    fun clear()
}

/**
 * The one place core code drops every user-scoped process cache without importing a feature package. The registry
 * is filled once by [com.thedavelopers.eventqr.EventQrApplication.onCreate]; [SessionManager] and the
 * session-expiry handler only call [clearAll].
 */
object SessionScopedCaches {
    private val caches = CopyOnWriteArrayList<SessionScopedCache>()

    fun register(cache: SessionScopedCache) {
        if (cache !in caches) caches += cache
    }

    /** Drops one registration: a screen's cache that is torn down, or a test's fake. */
    fun remove(cache: SessionScopedCache) {
        caches -= cache
    }

    /** Clears every registered cache. One failing cache never stops the others. */
    fun clearAll() {
        caches.forEach { cache -> runCatching { cache.clear() } }
    }

    /** Empties the registry so a test can assert exactly which caches one transition cleared. */
    @VisibleForTesting
    internal fun removeAll() {
        caches.clear()
    }
}
