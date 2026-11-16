package com.thedavelopers.eventqr.core.cache

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thedavelopers.eventqr.core.api.NetworkResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The cached-then-fresh helper: snapshot first, network second, and only success is persisted. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CachedThenFreshTest {

    private data class Sample(val id: Long, val label: String)

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val type = Sample::class.java
    private val key = "attendee/dashboard"

    private val ownerA = "aaaaaaaa-1111-1111-1111-111111111111"
    private val ownerB = "bbbbbbbb-2222-2222-2222-222222222222"
    private var now = 1_700_000_000_000L
    private var current = ownerA
    private val cache = LastKnownCache(
        context,
        clock = { now },
        userId = { current },
    )

    @Before
    fun setUp() {
        current = ownerA
        cache.clearAll()
    }

    @After
    fun tearDown() = cache.clearAll()

    @Test
    fun snapshotIsEmittedBeforeTheFetchResolves() = runBlocking {
        cache.write(key, Sample(1, "cached"))
        // The fetch cannot finish until the snapshot callback has run: a wrong order deadlocks here.
        val gate = CompletableDeferred<Unit>()
        var seen: Sample? = null

        val result = withTimeout(5_000) {
            cache.cachedThenFresh<Sample>(
                key = key,
                type = type,
                maxAgeMs = 60_000,
                onSnapshot = { snapshot ->
                    seen = snapshot.data
                    gate.complete(Unit)
                },
                fetch = {
                    gate.await()
                    NetworkResult.Error("boom")
                },
            )
        }

        assertEquals(Sample(1, "cached"), seen)
        assertEquals("boom", (result as NetworkResult.Error).message)
    }

    @Test
    fun successIsReturnedAndStored() = runBlocking {
        val result = cache.cachedThenFresh<Sample>(key, type, maxAgeMs = 60_000) {
            NetworkResult.Success(Sample(2, "fresh"))
        }

        assertEquals(Sample(2, "fresh"), (result as NetworkResult.Success).data)
        assertEquals(Sample(2, "fresh"), cache.read<Sample>(key, type, 60_000)?.data)
        assertEquals(now, cache.read<Sample>(key, type, 60_000)?.savedAtMs)
    }

    @Test
    fun errorIsReturnedWithoutOverwritingTheSnapshot() = runBlocking {
        now += 1_000
        cache.write(key, Sample(3, "cached"))
        val savedAt = now

        val result = cache.cachedThenFresh<Sample>(key, type, maxAgeMs = -1) {
            NetworkResult.Error("offline")
        }

        assertEquals("offline", (result as NetworkResult.Error).message)
        assertEquals(Sample(3, "cached"), cache.read<Sample>(key, type, Long.MAX_VALUE)?.data)
        assertEquals(savedAt, cache.read<Sample>(key, type, Long.MAX_VALUE)?.savedAtMs)
    }

    @Test
    fun noSnapshot_meansNoCallback() = runBlocking {
        var called = false

        val result = cache.cachedThenFresh<Sample>(
            key = key,
            type = type,
            maxAgeMs = 60_000,
            onSnapshot = { called = true },
            fetch = { NetworkResult.Error("offline") },
        )

        assertFalse(called)
        assertEquals("offline", (result as NetworkResult.Error).message)
        assertNull(cache.read<Sample>(key, type, Long.MAX_VALUE))
    }

    @Test
    fun answerIsFiledUnderTheOwnerCapturedBeforeTheFetch() = runBlocking {
        // An account switch lands while the request is in flight.
        val result = cache.cachedThenFresh<Sample>(key, type, maxAgeMs = -1) {
            current = ownerB
            NetworkResult.Success(Sample(4, "fetchedForA"))
        }

        assertEquals(Sample(4, "fetchedForA"), (result as NetworkResult.Success).data)
        current = ownerA
        assertEquals(Sample(4, "fetchedForA"), cache.read<Sample>(key, type, 60_000)?.data)
        current = ownerB
        assertNull(cache.read<Sample>(key, type, 60_000))
    }
}
