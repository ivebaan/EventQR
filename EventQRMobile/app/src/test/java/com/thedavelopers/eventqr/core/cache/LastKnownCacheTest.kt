package com.thedavelopers.eventqr.core.cache

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Persisted last-known answers: user scoping, atomic replace, corruption handling and the size cap. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LastKnownCacheTest {

    private data class Sample(val id: Long, val label: String)

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val type = Sample::class.java
    private val userA = "aaaaaaaa-1111-1111-1111-111111111111"
    private val userB = "bbbbbbbb-2222-2222-2222-222222222222"

    private var now = 1_700_000_000_000L
    private var currentUser: String? = userA

    private fun newCache(
        maxTotalBytes: Long = LastKnownCache.DEFAULT_MAX_TOTAL_BYTES,
        maxEntryBytes: Long = LastKnownCache.DEFAULT_MAX_ENTRY_BYTES,
    ) = LastKnownCache(
        context,
        maxTotalBytes = maxTotalBytes,
        maxEntryBytes = maxEntryBytes,
        clock = { now },
        userId = { currentUser },
    )

    private val cache = newCache()

    @Before
    fun setUp() {
        currentUser = userA
        cache.clearAll()
    }

    @After
    fun tearDown() {
        cache.clearAll()
    }

    @Test
    fun writeThenReadWithinMaxAge_returnsPayloadAndSavedAt() = runBlocking {
        cache.write("attendee/dashboard", Sample(1, "one"))

        val snapshot = cache.read<Sample>("attendee/dashboard", type, maxAgeMs = 60_000)

        assertEquals(Sample(1, "one"), snapshot?.data)
        assertEquals(now, snapshot?.savedAtMs)
    }

    @Test
    fun readBeyondMaxAge_returnsNullAndKeepsTheFile() = runBlocking {
        cache.write("events/browse", Sample(2, "two"))
        now += 60_001

        assertNull(cache.read<Sample>("events/browse", type, maxAgeMs = 60_000))
        assertTrue(cache.fileFor(userA, "events/browse").isFile)
    }

    @Test
    fun writeAndReadWithoutASession_areNoOps() = runBlocking {
        cache.write("attendee/dashboard", Sample(3, "three"))
        currentUser = "   "

        assertNull(cache.read<Sample>("attendee/dashboard", type, maxAgeMs = 60_000))
        cache.write("attendee/dashboard", Sample(4, "four"))
        currentUser = userA
        assertEquals(Sample(3, "three"), cache.read<Sample>("attendee/dashboard", type, 60_000)?.data)
    }

    @Test
    fun corruptFile_isMissAndDeleted() = runBlocking {
        val file = cache.fileFor(userA, "corrupt/key").apply {
            parentFile?.mkdirs()
            writeText("{ this is not json")
        }

        assertNull(cache.read<Sample>("corrupt/key", type, maxAgeMs = 60_000))
        assertFalse(file.exists())
    }

    @Test
    fun wrongVersion_isMissAndDeleted() = runBlocking {
        val file = cache.fileFor(userA, "old/schema").apply {
            parentFile?.mkdirs()
            writeText("""{"version":99,"savedAt":$now,"payload":{"id":1,"label":"x"}}""")
        }

        assertNull(cache.read<Sample>("old/schema", type, maxAgeMs = 60_000))
        assertFalse(file.exists())
    }

    @Test
    fun oversizedEntry_isMissAndDeleted() = runBlocking {
        val small = newCache(maxEntryBytes = 100)
        val file = small.fileFor(userA, "huge").apply {
            parentFile?.mkdirs()
            writeText("""{"version":1,"savedAt":$now,"payload":{"id":1,"label":"${"x".repeat(400)}"}}""")
        }

        assertNull(small.read<Sample>("huge", type, maxAgeMs = 60_000))
        assertFalse(file.exists())
    }

    @Test
    fun secondWrite_replacesCleanlyAndLeavesNoTempFile() = runBlocking {
        cache.write("event/1", Sample(1, "first"))
        cache.write("event/1", Sample(2, "second"))

        assertEquals(Sample(2, "second"), cache.read<Sample>("event/1", type, 60_000)?.data)
        val leftovers = cache.fileFor(userA, "event/1").parentFile!!.listFiles().orEmpty()
        assertTrue(leftovers.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun overTheTotalCap_evictsOldestAndKeepsTheNewest() = runBlocking {
        val capped = newCache(maxTotalBytes = 250)
        val keys = listOf("cap/1", "cap/2", "cap/3", "cap/4")
        keys.forEachIndexed { index, key ->
            now += 1_000
            capped.write(key, Sample(index.toLong(), "x".repeat(120)))
        }

        assertEquals(Sample(3, "x".repeat(120)), capped.read<Sample>("cap/4", type, Long.MAX_VALUE)?.data)
        keys.dropLast(1).forEach { key ->
            assertNull(capped.read<Sample>(key, type, Long.MAX_VALUE))
        }
        val total = capped.fileFor(userA, "cap/4").parentFile!!.listFiles().orEmpty().sumOf { it.length() }
        assertTrue("directory must be trimmed under the cap, was $total", total <= 250)
    }

    @Test
    fun anotherUserNeverReadsThePreviousUsersEntry() = runBlocking {
        cache.write("organizer/events", Sample(9, "mine"))
        currentUser = userB

        assertNull(cache.read<Sample>("organizer/events", type, maxAgeMs = 60_000))

        cache.write("organizer/events", Sample(10, "theirs"))
        currentUser = userA
        assertEquals(Sample(9, "mine"), cache.read<Sample>("organizer/events", type, 60_000)?.data)

        cache.clearUser(userA)
        assertNull(cache.read<Sample>("organizer/events", type, 60_000))
        currentUser = userB
        assertEquals(Sample(10, "theirs"), cache.read<Sample>("organizer/events", type, 60_000)?.data)
    }

    @Test
    fun writeUnderACapturedOwner_staysWithThatUser() = runBlocking {
        val captured = cache.currentOwner()
        currentUser = userB

        cache.write("registrations/me", Sample(5, "forA"), captured)

        currentUser = userA
        assertEquals(Sample(5, "forA"), cache.read<Sample>("registrations/me", type, 60_000)?.data)
        currentUser = userB
        assertNull(cache.read<Sample>("registrations/me", type, 60_000))
    }

    @Test
    fun blankOwner_writesNothing() = runBlocking {
        cache.write("registrations/me", Sample(6, "dropped"), "   ")

        assertNull(cache.read<Sample>("registrations/me", type, Long.MAX_VALUE))
    }

    @Test
    fun clearAll_wipesEveryUser() = runBlocking {
        cache.write("attendee/dashboard", Sample(1, "a"))
        currentUser = userB
        cache.write("attendee/dashboard", Sample(2, "b"))

        cache.clearAll()

        assertNull(cache.read<Sample>("attendee/dashboard", type, Long.MAX_VALUE))
        currentUser = userA
        assertNull(cache.read<Sample>("attendee/dashboard", type, Long.MAX_VALUE))
    }
}
