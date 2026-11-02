package com.streamvault.data.sync.favorites

import com.streamvault.data.local.dao.FavoriteSyncDao
import com.streamvault.data.local.entity.FavoriteSyncStateEntity
import com.streamvault.domain.manager.FavoriteSyncError
import com.streamvault.domain.manager.FavoriteSyncJoinMode
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Typed access to the `favorite_sync_state` rows. Callers that mutate the replica run inside the
 * same Room transaction as the favorites write they describe, so both commit or neither does.
 */
@Singleton
class FavoriteSyncStateStore @Inject constructor(
    private val dao: FavoriteSyncDao
) {
    private val clockMutex = Mutex()

    @Volatile
    private var cachedClock: FavoriteSyncClock? = null

    /** Wall clock source; tests replace it to simulate skew. */
    @Volatile
    var nowMs: () -> Long = System::currentTimeMillis

    suspend fun replica(): FavoriteSyncDocument = dao.getValue(KEY_REPLICA)
        ?.let { runCatching { FavoriteSyncJson.decode(it) }.getOrNull() }
        ?: FavoriteSyncDocument.EMPTY

    suspend fun saveReplica(document: FavoriteSyncDocument) = put(KEY_REPLICA, FavoriteSyncJson.encode(document))

    suspend fun etag(): String? = dao.getValue(KEY_ETAG)

    suspend fun setEtag(etag: String?) = if (etag == null) dao.deleteValue(KEY_ETAG) else put(KEY_ETAG, etag)

    suspend fun isDirty(): Boolean = dao.getValue(KEY_DIRTY) == "1"

    suspend fun setDirty(dirty: Boolean) = put(KEY_DIRTY, if (dirty) "1" else "0")

    /** Bumped by every recorded local edit; lets an upload tell whether edits raced it. */
    suspend fun localVersion(): Long = dao.getValue(KEY_LOCAL_VERSION)?.toLongOrNull() ?: 0L

    suspend fun markLocalChange() {
        put(KEY_LOCAL_VERSION, (localVersion() + 1).toString())
        setDirty(true)
    }

    suspend fun joinMode(): FavoriteSyncJoinMode? = dao.getValue(KEY_JOIN_MODE)
        ?.let { value -> FavoriteSyncJoinMode.entries.firstOrNull { it.name == value } }

    suspend fun setJoinMode(mode: FavoriteSyncJoinMode?) =
        if (mode == null) dao.deleteValue(KEY_JOIN_MODE) else put(KEY_JOIN_MODE, mode.name)

    suspend fun lastSyncAt(): Long? = dao.getValue(KEY_LAST_SYNC_AT)?.toLongOrNull()

    suspend fun setLastSyncAt(value: Long) = put(KEY_LAST_SYNC_AT, value.toString())

    suspend fun lastError(): FavoriteSyncError? = dao.getValue(KEY_LAST_ERROR)
        ?.let { value -> FavoriteSyncError.entries.firstOrNull { it.name == value } }

    suspend fun setLastError(error: FavoriteSyncError?) =
        if (error == null) dao.deleteValue(KEY_LAST_ERROR) else put(KEY_LAST_ERROR, error.name)

    /** Household salt from the server copy's header (or picked here before the first upload). */
    suspend fun salt(): ByteArray? = dao.getValue(KEY_SALT)
        ?.let { value -> runCatching { value.chunked(2).map { it.toInt(16).toByte() }.toByteArray() }.getOrNull() }
        ?.takeIf { it.size == FavoriteSyncCrypto.SALT_BYTES }

    suspend fun setSalt(salt: ByteArray) = put(KEY_SALT, salt.toHex())

    suspend fun unmatchedCount(): Int = dao.getValue(KEY_UNMATCHED)?.toIntOrNull() ?: 0

    suspend fun setUnmatchedCount(value: Int) = put(KEY_UNMATCHED, value.toString())

    /** Forgets the replica and server version, e.g. after switching to another sync server. */
    suspend fun resetReplica() {
        listOf(KEY_REPLICA, KEY_ETAG, KEY_DIRTY, KEY_LAST_ERROR, KEY_UNMATCHED, KEY_LAST_SYNC_AT, KEY_SALT)
            .forEach { dao.deleteValue(it) }
    }

    suspend fun clock(): FavoriteSyncClock = cachedClock ?: clockMutex.withLock {
        cachedClock ?: run {
            val deviceId = dao.getValue(KEY_DEVICE_ID) ?: UUID.randomUUID().toString().also { put(KEY_DEVICE_ID, it) }
            val last = dao.getValue(KEY_CLOCK)?.let { runCatching { FavoriteSyncHlc.decode(it) }.getOrNull() }
            FavoriteSyncClock(deviceId, { nowMs() }, last).also { cachedClock = it }
        }
    }

    /** Persists the clock so stamps stay monotonic across process restarts. */
    suspend fun saveClock() {
        cachedClock?.let { put(KEY_CLOCK, it.last().encode()) }
    }

    private suspend fun put(key: String, value: String) = dao.putValue(FavoriteSyncStateEntity(key, value))

    private companion object {
        const val KEY_REPLICA = "replica"
        const val KEY_ETAG = "etag"
        const val KEY_DIRTY = "dirty"
        const val KEY_LOCAL_VERSION = "local_version"
        const val KEY_JOIN_MODE = "join_mode"
        const val KEY_LAST_SYNC_AT = "last_sync_at"
        const val KEY_LAST_ERROR = "last_error"
        const val KEY_UNMATCHED = "unmatched"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_CLOCK = "clock"
        const val KEY_SALT = "salt"
    }
}
