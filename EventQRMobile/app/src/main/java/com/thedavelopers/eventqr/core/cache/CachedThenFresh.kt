package com.thedavelopers.eventqr.core.cache

import com.thedavelopers.eventqr.core.api.NetworkResult
import java.lang.reflect.Type

/**
 * Serves the persisted snapshot (when it is no older than [maxAgeMs]) through [onSnapshot] and *then* fetches, so a
 * screen can paint before the network answers.
 *
 * Only a [NetworkResult.Success] is written: a failed refresh never destroys the last-known data. The owner is
 * captured before the fetch, so an account switch in flight files the answer under the session that asked for it,
 * never under whoever is signed in when it lands. The helper makes no UI decision — the caller paints on
 * [onSnapshot] and shows an error state only when nothing was painted. A forced refresh is a call site that does
 * not want the snapshot: call [LastKnownCache.read] and `fetch` yourself, or pass `maxAgeMs = -1`.
 */
suspend fun <T> LastKnownCache.cachedThenFresh(
    key: String,
    type: Type,
    maxAgeMs: Long,
    onSnapshot: (Snapshot<T>) -> Unit = {},
    fetch: suspend () -> NetworkResult<T>,
): NetworkResult<T> {
    val owner = currentOwner()
    read<T>(key, type, maxAgeMs)?.let(onSnapshot)
    val result = fetch()
    if (result is NetworkResult.Success) write(key, result.data, owner)
    return result
}
