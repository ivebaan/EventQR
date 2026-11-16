package com.thedavelopers.eventqr.core.cache

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.thedavelopers.eventqr.core.api.sharedGson
import com.thedavelopers.eventqr.core.session.SessionManager
import com.thedavelopers.eventqr.core.session.SessionScopedCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.lang.reflect.Type
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A stored answer and the wall-clock time it was written. Staleness is the caller's decision. */
data class Snapshot<T>(val data: T, val savedAtMs: Long)

/**
 * Persisted "last known" copy of successful GET answers, so a cold open can paint before the network answers.
 *
 * PRIVACY — never pass any of these to [write]:
 * - access or refresh tokens, passwords, PINs;
 * - QR credential payloads, secrets or values (anything `QrPassCache` owns stays there, in its encrypted store);
 * - base64 images or any binary blob (poster/avatar `files/{id}` bodies, report exports).
 * Persisted content is the signed-in user's own data, app-private and outside cloud backup
 * (`noBackupFilesDir`), scoped to their id so an account switch cannot read it, and wiped on logout and on
 * session expiry via [SessionScopedCache.clear]:
 * - notification `title`/`message` bodies ARE stored: they are the user's own notifications, kept for at most
 *   24 hours (the caller's `maxAgeMs`), then ignored;
 * - transaction rows may carry opaque server ids (`qrCredentialId`, `registrationId`); those are references, not
 *   secrets, and never the QR value itself.
 *
 * Every path is `noBackupFilesDir/last_known/<userId>/<encoded-key>.json`, resolved from the session at call
 * time. A blank user id means "no session": reads return null and writes are dropped.
 *
 * Owner capture: a write's owner must be the session the answer was fetched FOR, not the session that happens to
 * be open when it lands. Call sites that fetch must read [currentOwner] before the fetch and pass it to
 * [write]; [write] alone (no fetch in flight) resolves the current session itself.
 */
class LastKnownCache(
    context: Context,
    private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
    private val maxEntryBytes: Long = DEFAULT_MAX_ENTRY_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
    private val userId: () -> String? = {
        SessionManager(context.applicationContext ?: context).getUserId()
    },
) : SessionScopedCache {

    private val root = File((context.applicationContext ?: context).noBackupFilesDir, DIR_NAME)

    /** Serialises [write] and [trim]: two writers on one directory must not both decide it is under the cap. */
    private val writeMutex = Mutex()

    /** The session id to file a fetched answer under, "" when signed out. Read this BEFORE a fetch. */
    fun currentOwner(): String = attempt { userId().orEmpty() } ?: ""

    /**
     * The stored answer for [key] when it is at most [maxAgeMs] old and belongs to the current user; null for a
     * miss, another user, or a stale entry. A file that is corrupt, oversized or from another schema version is
     * deleted and reported as a miss. Never throws.
     */
    suspend fun <T> read(key: String, type: Type, maxAgeMs: Long): Snapshot<T>? = withContext(Dispatchers.IO) {
        attempt { readEntry(key, type, maxAgeMs) }
    }

    /**
     * Stores [value] under [owner] (see [currentOwner], which a fetching caller must capture before its request).
     * Atomic: the payload lands in a per-write temp file and is moved over the target, and a failure leaves the
     * previous file untouched. The temp name is unique per write so two writers racing on the same key (a refresh
     * landing while the screen also writes) can never interleave bytes into one file. A blank [owner] writes
     * nothing; an entry larger than [maxEntryBytes] is never written. Oldest entries are evicted afterwards to
     * keep the user directory under [maxTotalBytes]. Never throws.
     */
    suspend fun <T> write(key: String, value: T, owner: String) = withContext(Dispatchers.IO) {
        if (owner.isBlank()) return@withContext
        writeMutex.withLock { attempt { writeEntry(key, value, owner) } }
        Unit
    }

    /** [write] for a call site with no request in flight: the session is resolved now. */
    suspend fun <T> write(key: String, value: T) = write(key, value, currentOwner())

    private fun <T> readEntry(key: String, type: Type, maxAgeMs: Long): Snapshot<T>? {
        val user = currentUser() ?: return null
        val file = fileFor(user, key)
        if (!file.isFile) return null
        if (file.length() > maxEntryBytes) {
            file.delete()
            return null
        }
        val envelope = runCatching { gson.fromJson(file.readText(), Envelope::class.java) }.getOrNull()
        if (envelope == null || envelope.version != VERSION || envelope.payload == null) {
            file.delete()
            return null
        }
        // A stale entry is kept: it is still the newest thing on disk for this key until a fresh write lands.
        if (clock() - envelope.savedAt > maxAgeMs) return null
        val data = runCatching { gson.fromJson<T>(envelope.payload, type) }.getOrNull()
        if (data == null) {
            file.delete()
            return null
        }
        return Snapshot(data, envelope.savedAt)
    }

    private fun <T> writeEntry(key: String, value: T, owner: String) {
        val json = gson.toJson(Envelope(VERSION, clock(), gson.toJsonTree(value)))
        val bytes = json.toByteArray(Charsets.UTF_8)
        if (bytes.size > maxEntryBytes) return
        val directory = userDir(owner).apply { mkdirs() }
        val target = File(directory, fileNameFor(key))
        val temp = File(directory, target.name + "." + java.util.UUID.randomUUID() + TEMP_SUFFIX)
        try {
            temp.writeBytes(bytes)
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (_: IOException) {
            temp.delete()
            return
        }
        trim(directory, keep = target)
    }

    /**
     * Drops [key] for the current user. The next open must fetch instead of painting: an answer the
     * attendee has just changed (register, cancel, redeem) must never be masked by an older snapshot.
     * Never throws; a missing file is not a failure.
     */
    suspend fun invalidate(key: String) = withContext(Dispatchers.IO) {
        attempt { currentUser()?.let { fileFor(it, key).delete() } }
        Unit
    }

    /**
     * Account-switch primitive: drops one user's directory, so that account's last-known data can never be read
     * again. Not on today's logout path (only [clearAll] is); it is what an explicit per-account purge needs.
     */
    fun clearUser(userId: String) {
        if (userId.isBlank()) return
        // Inline, not dispatched: the tree is a handful of small files, and session transitions must be final
        // before the next screen reads.
        userDir(userId).deleteRecursively()
    }

    /** Deletes the whole tree, every user. Called on login/account switch, logout and session expiry. */
    fun clearAll() {
        root.deleteRecursively()
    }

    override fun clear() = clearAll()

    @VisibleForTesting
    internal fun fileFor(userId: String, key: String): File = File(userDir(userId), fileNameFor(key))

    private fun currentUser(): String? = userId()?.takeIf { it.isNotBlank() }

    private fun userDir(userId: String): File = File(root, encodeKey(userId))

    /**
     * One file name per key, reversible and collision-free: alphanumerics and `. - _` stay, everything else
     * (notably `/`) becomes `_<hex code>`. A very long key is truncated with a suffix hash so it cannot exceed the
     * filesystem name limit.
     */
    private fun fileNameFor(key: String): String {
        val encoded = encodeKey(key)
        val safe = if (encoded.length <= MAX_NAME_LENGTH) encoded else encoded.take(MAX_NAME_LENGTH - 10) + "_" +
            encoded.hashCode().toUInt().toString(16)
        return safe + EXTENSION
    }

    private fun encodeKey(key: String): String = buildString {
        key.forEach { character ->
            if (character.isLetterOrDigit() && character.code < 128 || character == '.' || character == '-' || character == '_') {
                append(character)
            } else {
                append('_').append(Integer.toHexString(character.code))
            }
        }
    }

    /**
     * Evicts oldest-`savedAt` entries until the directory is back under [maxTotalBytes]. [keep] (the entry just
     * written) is never evicted, so a single oversized-but-allowed entry survives on its own. `lastModified` breaks
     * a same-millisecond tie so a just-written entry is not the victim of its sibling.
     */
    private fun trim(directory: File, keep: File) {
        val files = directory.listFiles { file -> file.isFile && file.name.endsWith(EXTENSION) } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxTotalBytes) return
        for (file in files.sortedWith(compareBy({ savedAtOf(it) }, { it.lastModified() }, { it.name }))) {
            if (total <= maxTotalBytes) return
            if (file == keep) continue
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    /**
     * Only the envelope's `savedAt`, read lazily (trim runs only when the cap is exceeded). The text is read
     * through [File.readText], which closes the handle: a stream left open here makes every later `delete()`
     * fail on Windows, and the cap would silently stop evicting. Name is the last tiebreak, so two entries
     * written in the same millisecond still order the same way on every run.
     */
    private fun savedAtOf(file: File): Long = runCatching {
        JsonParser.parseReader(file.readText().reader()).asJsonObject.get("savedAt").asLong
    }.getOrDefault(file.lastModified())

    /**
     * "Never throws" for the whole body: any failure (Gson, `mkdirs`, the move, a SecurityException, a broken
     * keystore behind the session id) becomes a miss or a dropped write. Cancellation is not a failure.
     */
    private fun <T> attempt(block: () -> T): T? = try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        null
    }

    private class Envelope(val version: Int, val savedAt: Long, val payload: JsonElement?)

    companion object {
        const val VERSION = 1

        /** 4 MB per user directory. */
        const val DEFAULT_MAX_TOTAL_BYTES = 4L * 1024 * 1024

        /** 1 MB per entry: one screen's list, never a blob. */
        const val DEFAULT_MAX_ENTRY_BYTES = 1L * 1024 * 1024

        private const val DIR_NAME = "last_known"
        private const val EXTENSION = ".json"
        private const val TEMP_SUFFIX = ".tmp"
        private const val MAX_NAME_LENGTH = 100

        private val gson: Gson by lazy { sharedGson() }

        @Volatile
        private var shared: LastKnownCache? = null

        /** The process-wide instance, registered with [com.thedavelopers.eventqr.core.session.SessionScopedCaches]. */
        fun get(context: Context): LastKnownCache = shared ?: synchronized(this) {
            shared ?: LastKnownCache(context.applicationContext ?: context).also { shared = it }
        }

        /** Drops the process-wide instance so a test can rebuild it against its own directories. */
        @VisibleForTesting
        internal fun resetForTests() {
            synchronized(this) { shared = null }
        }
    }
}
