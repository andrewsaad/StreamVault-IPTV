package com.streamvault.data.epg

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.streamvault.domain.util.PersistedTimestampPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * One guide source a preset uses on this device. [owned] is false when the address already
 * existed as a hand-added source, in which case the preset never deletes it.
 * [assignedProviderIds] are the providers the preset assigned the source to, so turning the
 * preset off only undoes its own assignments. [handledProviderIds] are the providers already
 * seen, so one that lacks the source later had it removed by the user and is left alone.
 * [defaultUrl] is the built-in address when the row was linked, so a later build that corrects
 * it can tell an unedited row (still at [defaultUrl]) from one the user changed; null on lines
 * saved before it was remembered.
 */
data class EpgPresetSourceRecord(
    val sourceId: Long,
    val owned: Boolean,
    val assignedProviderIds: Set<Long>,
    val handledProviderIds: Set<Long> = assignedProviderIds,
    val defaultUrl: String? = null,
)

/**
 * Device-local preset state. Sources, [lastAttemptAtByKey] and [addresses] are keyed by
 * [com.streamvault.domain.model.EpgPresetSource.key]. [addresses] holds the user's edited
 * address per file and outlives the preset being off, so turning it on again reuses the edit.
 * [lastAttemptAtByKey] outlives it too: turning a preset off and on again deletes and recreates
 * its source row, and must not reset the cap of two attempts in 24 hours (SC-005).
 */
data class EpgPresetState(
    val enabledPresetIds: Set<String> = emptySet(),
    val sources: Map<String, EpgPresetSourceRecord> = emptyMap(),
    val lastAttemptAtByKey: Map<String, Long> = emptyMap(),
    val addresses: Map<String, String> = emptyMap(),
) {
    /** The last attempt of each preset-owned row now in use, found through the file key it serves. */
    val lastAttemptAtBySourceId: Map<Long, Long>
        get() = HashMap<Long, Long>().also { bySourceId ->
            sources.forEach { (key, record) ->
                val attempt = lastAttemptAtByKey[key]
                if (record.owned && attempt != null) bySourceId.merge(record.sourceId, attempt) { a, b -> maxOf(a, b) }
            }
        }

    fun isPresetOwnedSource(sourceId: Long): Boolean =
        sources.values.any { it.owned && it.sourceId == sourceId }

    /** The file keys a preset-owned row serves; usually one. */
    fun presetKeysOf(sourceId: Long): List<String> =
        sources.filterValues { it.owned && it.sourceId == sourceId }.keys.toList()
}

/** Lets the source refresh ask whether an attempt may start now; recording the attempt when it may. */
interface EpgSourceRefreshGate {
    suspend fun tryBeginRefreshAttempt(sourceId: Long): Boolean

    /** Forgets an attempt that never ran to an outcome (cancelled, or lost to process death). */
    suspend fun clearRefreshAttempt(sourceId: Long)

    /** No gap for any source; the default when no preset store is wired in (tests). */
    object AllowAll : EpgSourceRefreshGate {
        override suspend fun tryBeginRefreshAttempt(sourceId: Long): Boolean = true
        override suspend fun clearRefreshAttempt(sourceId: Long) = Unit
    }
}

/** Which guide source rows a preset created on this device, for code that must rank or skip them. */
fun interface EpgPresetSourceLookup {
    suspend fun presetOwnedSourceIds(): Set<Long>

    /** No preset rows; the default when no preset store is wired in (tests). */
    object None : EpgPresetSourceLookup {
        override suspend fun presetOwnedSourceIds(): Set<Long> = emptySet()
    }
}

/**
 * Per-device preset state in its own DataStore file, separate from the main preferences, so
 * it never travels with a backup or another device (FR-026). Encoded as tab-separated lines,
 * following the pattern of [com.streamvault.data.preferences.EmptyGuideKeyCache].
 */
@Singleton
class EpgPresetStateStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val clock: () -> Long,
) : EpgSourceRefreshGate, EpgPresetSourceLookup {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        createDataStore { context.preferencesDataStoreFile(FILE_NAME) },
        System::currentTimeMillis,
    )

    val state: Flow<EpgPresetState> = dataStore.data.map(::readState)

    suspend fun current(): EpgPresetState = state.first()

    suspend fun update(transform: (EpgPresetState) -> EpgPresetState) {
        dataStore.edit { preferences ->
            writeState(preferences, transform(readState(preferences)))
        }
    }

    fun now(): Long = clock()

    override suspend fun presetOwnedSourceIds(): Set<Long> =
        current().sources.values.filter { it.owned }.mapTo(HashSet()) { it.sourceId }

    /** True when a preset-owned source was attempted less than [MIN_ATTEMPT_GAP_MILLIS] ago. */
    fun isInsideAttemptGap(state: EpgPresetState, sourceId: Long, now: Long = clock()): Boolean =
        state.isPresetOwnedSource(sourceId) &&
            PersistedTimestampPolicy.isFresh(state.lastAttemptAtBySourceId[sourceId], now, MIN_ATTEMPT_GAP_MILLIS)

    override suspend fun tryBeginRefreshAttempt(sourceId: Long): Boolean {
        var allowed = true
        dataStore.edit { preferences ->
            val current = readState(preferences)
            if (!current.isPresetOwnedSource(sourceId)) return@edit
            val now = clock()
            if (isInsideAttemptGap(current, sourceId, now)) {
                allowed = false
                return@edit
            }
            writeState(
                preferences,
                current.copy(lastAttemptAtByKey = current.lastAttemptAtByKey + current.presetKeysOf(sourceId).associateWith { now }),
            )
        }
        return allowed
    }

    override suspend fun clearRefreshAttempt(sourceId: Long) {
        update { it.copy(lastAttemptAtByKey = it.lastAttemptAtByKey - it.presetKeysOf(sourceId).toSet()) }
    }

    companion object {
        /** 12 hours, so a preset source is attempted at most twice in any 24 hours, failures included. */
        const val MIN_ATTEMPT_GAP_MILLIS = 12L * 60L * 60L * 1000L
        internal const val FILE_NAME = "epg_preset_state"

        private val ENABLED_KEY = stringSetPreferencesKey("enabled_presets")
        private val SOURCES_KEY = stringPreferencesKey("sources")
        /** Attempts per file key (F18). */
        private val ATTEMPTS_KEY = stringPreferencesKey("attempts_by_key")

        /** Attempts per source row id, written before F18; read to migrate, then removed on the next write. */
        private val LEGACY_ATTEMPTS_KEY = stringPreferencesKey("attempts")
        private val ADDRESSES_KEY = stringPreferencesKey("addresses")

        private fun createDataStore(produceFile: () -> File): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                produceFile = produceFile,
            )

        private fun readState(preferences: Preferences): EpgPresetState {
            val sources = decodePresetSources(preferences[SOURCES_KEY])
            // Lines saved by an older build are keyed by row id; the rows still in use give them a file key.
            // Those of rows no longer in use are dropped. A line already keyed by file wins.
            val keyBySourceId = sources.entries.associate { (key, record) -> record.sourceId to key }
            val migrated = decodePresetAttempts(preferences[LEGACY_ATTEMPTS_KEY])
                .mapNotNull { (sourceId, at) -> keyBySourceId[sourceId]?.let { it to at } }
                .toMap()
            return EpgPresetState(
                enabledPresetIds = preferences[ENABLED_KEY].orEmpty(),
                sources = sources,
                lastAttemptAtByKey = migrated + decodePresetAttemptsByKey(preferences[ATTEMPTS_KEY]),
                addresses = decodePresetAddresses(preferences[ADDRESSES_KEY]),
            )
        }

        private fun writeState(preferences: MutablePreferences, state: EpgPresetState) {
            preferences[ENABLED_KEY] = state.enabledPresetIds
            preferences[SOURCES_KEY] = encodePresetSources(state.sources)
            preferences[ATTEMPTS_KEY] = encodePresetAttemptsByKey(state.lastAttemptAtByKey)
            preferences.remove(LEGACY_ATTEMPTS_KEY)
            preferences[ADDRESSES_KEY] = encodePresetAddresses(state.addresses)
        }
    }
}

internal fun decodePresetSources(encoded: String?): Map<String, EpgPresetSourceRecord> =
    encoded.orEmpty()
        .lineSequence()
        .mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 3) return@mapNotNull null
            val key = parts[0].trim()
            val sourceId = parts[1].trim().toLongOrNull() ?: return@mapNotNull null
            if (key.isEmpty() || sourceId <= 0L) return@mapNotNull null
            val providers = decodeProviderIds(parts.getOrNull(3))
            key to EpgPresetSourceRecord(
                sourceId,
                owned = parts[2].trim() == "1",
                assignedProviderIds = providers,
                // Lines written before this column existed have no list of their own.
                handledProviderIds = if (parts.size > 4) decodeProviderIds(parts[4]) else providers,
                defaultUrl = parts.getOrNull(5)?.trim()?.takeIf(String::isNotEmpty),
            )
        }
        .toMap()

private fun decodeProviderIds(encoded: String?): Set<Long> =
    encoded.orEmpty()
        .split(',')
        .mapNotNull { it.trim().toLongOrNull()?.takeIf { id -> id > 0L } }
        .toSet()

internal fun encodePresetSources(sources: Map<String, EpgPresetSourceRecord>): String =
    sources.entries
        .filter { (key, record) -> key.isNotBlank() && '\t' !in key && '\n' !in key && record.sourceId > 0L }
        .sortedBy { it.key }
        .joinToString("\n") { (key, record) ->
            val owned = if (record.owned) "1" else "0"
            val assigned = record.assignedProviderIds.sorted().joinToString(",")
            val handled = record.handledProviderIds.sorted().joinToString(",")
            val defaultUrl = record.defaultUrl?.takeIf { url -> '\t' !in url && '\n' !in url }.orEmpty()
            "$key\t${record.sourceId}\t$owned\t$assigned\t$handled\t$defaultUrl"
        }

internal fun decodePresetAttempts(encoded: String?): Map<Long, Long> =
    encoded.orEmpty()
        .lineSequence()
        .mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size != 2) return@mapNotNull null
            val sourceId = parts[0].trim().toLongOrNull() ?: return@mapNotNull null
            val timestamp = parts[1].trim().toLongOrNull() ?: return@mapNotNull null
            if (sourceId <= 0L || timestamp <= 0L) return@mapNotNull null
            sourceId to timestamp
        }
        .toMap()

internal fun decodePresetAttemptsByKey(encoded: String?): Map<String, Long> =
    encoded.orEmpty()
        .lineSequence()
        .mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size != 2) return@mapNotNull null
            val key = parts[0].trim()
            val timestamp = parts[1].trim().toLongOrNull() ?: return@mapNotNull null
            if (key.isEmpty() || timestamp <= 0L) null else key to timestamp
        }
        .toMap()

internal fun encodePresetAttemptsByKey(attempts: Map<String, Long>): String =
    attempts.entries
        .filter { (key, timestamp) -> key.isNotBlank() && '\t' !in key && '\n' !in key && timestamp > 0L }
        .sortedBy { it.key }
        .joinToString("\n") { (key, timestamp) -> "$key\t$timestamp" }

internal fun decodePresetAddresses(encoded: String?): Map<String, String> =
    encoded.orEmpty()
        .lineSequence()
        .mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size != 2) return@mapNotNull null
            val key = parts[0].trim()
            val url = parts[1].trim()
            if (key.isEmpty() || url.isEmpty()) null else key to url
        }
        .toMap()

internal fun encodePresetAddresses(addresses: Map<String, String>): String =
    addresses.entries
        .filter { (key, url) -> listOf(key, url).all { it.isNotBlank() && '\t' !in it && '\n' !in it } }
        .sortedBy { it.key }
        .joinToString("\n") { (key, url) -> "$key\t$url" }
