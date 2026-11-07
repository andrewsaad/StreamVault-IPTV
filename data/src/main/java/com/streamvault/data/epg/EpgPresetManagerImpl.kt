package com.streamvault.data.epg

import android.util.Log
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.domain.model.EpgPreset
import com.streamvault.domain.model.EpgPresetCatalog
import com.streamvault.domain.model.EpgPresetSource
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.EpgPresetErrors
import com.streamvault.domain.repository.EpgPresetManager
import com.streamvault.domain.repository.EpgPresetStatus
import com.streamvault.domain.repository.EpgSourceRepository
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The providers a preset is assigned to. A seam so tests need no provider rows. */
fun interface EpgPresetProviderDirectory {
    suspend fun providerIds(): List<Long>
}

class ProviderDaoPresetDirectory @Inject constructor(
    private val providerDao: ProviderDao,
) : EpgPresetProviderDirectory {
    override suspend fun providerIds(): List<Long> = providerDao.getAllSync().map { it.id }
}

/**
 * Presets are ordinary guide sources plus device-local bookkeeping in [EpgPresetStateStore]:
 * one source row per address shared by every enabled preset that lists it, assigned below
 * every existing source, deleted only when no enabled preset still uses it and only when the
 * preset created it.
 */
@Singleton
class EpgPresetManagerImpl internal constructor(
    private val sources: EpgSourceRepository,
    private val providers: EpgPresetProviderDirectory,
    private val store: EpgPresetStateStore,
    private val importScope: CoroutineScope,
) : EpgPresetManager {

    @Inject
    constructor(
        sources: EpgSourceRepository,
        providers: EpgPresetProviderDirectory,
        store: EpgPresetStateStore,
    ) : this(sources, providers, store, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    private val mutex = Mutex()
    private val refreshing = MutableStateFlow<Set<String>>(emptySet())
    private val inFlightSourceIds = ConcurrentHashMap.newKeySet<Long>()

    override fun observe(): Flow<List<EpgPresetStatus>> =
        combine(store.state, sources.getAllSources(), refreshing) { state, rows, busy ->
            val rowsById = rows.associateBy { it.id }
            EpgPresetCatalog.all.map { preset ->
                val enabled = preset.id in state.enabledPresetIds
                val presetRows = if (enabled) {
                    preset.sources.mapNotNull { source -> state.sources[source.key]?.let { rowsById[it.sourceId] } }
                } else {
                    emptyList()
                }
                EpgPresetStatus(
                    presetId = preset.id,
                    enabled = enabled,
                    lastSuccessAt = presetRows.map { it.lastSuccessAt }.filter { it > 0L }.maxOrNull(),
                    lastError = presetRows.firstNotNullOfOrNull { row -> row.lastError?.takeIf(String::isNotBlank) },
                    refreshing = preset.id in busy,
                )
            }
        }.distinctUntilChanged()

    override suspend fun setEnabled(presetId: String, enabled: Boolean): Result<Unit> {
        val preset = EpgPresetCatalog.byId(presetId) ?: return Result.error(EpgPresetErrors.UNKNOWN_PRESET)
        return if (enabled) enable(preset) else disable(preset)
    }

    override suspend fun refreshNow(presetId: String): Result<Unit> {
        val preset = EpgPresetCatalog.byId(presetId) ?: return Result.error(EpgPresetErrors.UNKNOWN_PRESET)
        val state = store.current()
        if (preset.id !in state.enabledPresetIds) return Result.error(EpgPresetErrors.NOT_ENABLED)
        val due = preset.sources.mapNotNull { state.sources[it.key]?.sourceId }
            .filterNot { store.isInsideAttemptGap(state, it) }
        if (due.isEmpty()) return Result.error(EpgPresetErrors.REFRESH_TOO_SOON)
        return refresh(preset.id, due)
    }

    override suspend fun reconcile() {
        val due = mutex.withLock {
            val state = store.current()
            val enabled = EpgPresetCatalog.all.filter { it.id in state.enabledPresetIds }
            val usedKeys = enabled.flatMapTo(HashSet()) { preset -> preset.sources.map { it.key } }
            val fresh = mutableListOf<Long>()
            var records = state.sources.filterKeys { it in usedKeys }
            enabled.flatMap { it.sources }.distinctBy { it.key }.forEach { source ->
                val before = records[source.key]
                val record = ensureSource(source, before) ?: return@forEach
                if (record.sourceId != before?.sourceId) fresh += record.sourceId
                records = records + (source.key to assignToAllProviders(record))
            }
            store.update { it.copy(sources = records) }
            val unfinished = records.values.map { it.sourceId }.distinct()
                .filter { it !in fresh && it !in inFlightSourceIds && neverFinished(it) }
            unfinished.forEach { store.clearRefreshAttempt(it) }
            fresh + unfinished
        }
        if (due.isNotEmpty()) refresh(presetId = null, sourceIds = due)
    }

    /** No success and no error: the import never reached an outcome, usually because the process died. */
    private suspend fun neverFinished(sourceId: Long): Boolean {
        val row = sources.getSourceById(sourceId) ?: return false
        return row.lastSuccessAt <= 0L && row.lastError.isNullOrBlank()
    }

    private suspend fun enable(preset: EpgPreset): Result<Unit> {
        val sourceIds = mutex.withLock {
            val state = store.current()
            val records = state.sources.toMutableMap()
            for (source in preset.sources) {
                val record = ensureSource(source, records[source.key])
                    ?: return Result.error(EpgPresetErrors.SOURCE_UNAVAILABLE)
                records[source.key] = record
                // Saved before assigning, so a crash mid-way leaves a row reconcile() can still find.
                store.update { it.copy(sources = it.sources + (source.key to record)) }
                records[source.key] = assignToAllProviders(record)
            }
            store.update { it.copy(enabledPresetIds = it.enabledPresetIds + preset.id, sources = records) }
            preset.sources.map { records.getValue(it.key).sourceId }
        }
        // The first attempt of a new row is always allowed; a shared row inside its gap is skipped.
        refresh(preset.id, sourceIds)
        return Result.success(Unit)
    }

    private suspend fun disable(preset: EpgPreset): Result<Unit> = mutex.withLock {
        val state = store.current()
        if (preset.id !in state.enabledPresetIds) return Result.success(Unit)
        val remaining = state.enabledPresetIds - preset.id
        val stillUsed = EpgPresetCatalog.all
            .filter { it.id in remaining }
            .flatMapTo(HashSet()) { other -> other.sources.map { it.key } }
        val released = preset.sources.map { it.key }.filterNot { it in stillUsed }
        for (key in released) {
            val record = state.sources[key] ?: continue
            if (record.owned) {
                sources.deleteSource(record.sourceId)
            } else {
                record.assignedProviderIds.forEach { sources.unassignSourceFromProvider(it, record.sourceId) }
            }
        }
        store.update { it.copy(enabledPresetIds = remaining, sources = it.sources - released.toSet()) }
        Result.success(Unit)
    }

    /** The row for [source]: the remembered one if it still exists, else a hand-added row with the address, else a new one. */
    private suspend fun ensureSource(source: EpgPresetSource, remembered: EpgPresetSourceRecord?): EpgPresetSourceRecord? {
        if (remembered != null && sources.getSourceById(remembered.sourceId) != null) return remembered
        val existing = sources.getAllSources().first().firstOrNull { it.url == source.url }
        if (existing != null) {
            // Our own row survives under the same id; anything else was added by hand and is never deleted.
            val owned = remembered?.owned == true && remembered.sourceId == existing.id
            return EpgPresetSourceRecord(existing.id, owned = owned, assignedProviderIds = emptySet())
        }
        return when (val added = sources.addSource(name = sourceName(source), url = source.url)) {
            is Result.Success -> EpgPresetSourceRecord(added.data.id, owned = true, assignedProviderIds = emptySet())
            else -> {
                Log.w(TAG, "Could not add preset source ${source.key}: ${added.errorMessageOrNull()}")
                null
            }
        }
    }

    /** Assigns [record] to every provider that lacks it, below that provider's existing sources. */
    private suspend fun assignToAllProviders(record: EpgPresetSourceRecord): EpgPresetSourceRecord {
        val alreadyAssigned = sources.getProviderIdsForSource(record.sourceId).toSet()
        val assignedNow = mutableSetOf<Long>()
        for (providerId in providers.providerIds()) {
            if (providerId in alreadyAssigned) continue
            val lowest = sources.getAssignmentsForProvider(providerId).first().maxOfOrNull { it.priority } ?: -1
            if (sources.assignSourceToProvider(providerId, record.sourceId, lowest + 1) is Result.Success) {
                assignedNow += providerId
            }
        }
        // Keep only providers that still have the assignment, plus the ones added now.
        val kept = record.assignedProviderIds.filterTo(HashSet()) { it in alreadyAssigned }
        return record.copy(assignedProviderIds = kept + assignedNow)
    }

    /** Runs in [importScope], so an import outlives the screen that started it; only the wait is cancelled. */
    private suspend fun refresh(presetId: String?, sourceIds: List<Long>): Result<Unit> {
        val ids = sourceIds.distinct()
        if (presetId != null) refreshing.update { it + presetId }
        inFlightSourceIds += ids
        return importScope.async {
            try {
                val errors = ids.mapNotNull { sourceId ->
                    (sources.refreshSource(sourceId) as? Result.Error)?.message
                }
                if (errors.isEmpty()) Result.success(Unit) else Result.error(errors.joinToString("; "))
            } finally {
                inFlightSourceIds -= ids.toSet()
                if (presetId != null) refreshing.update { it - presetId }
            }
        }.await()
    }

    private fun sourceName(source: EpgPresetSource): String = "epgshare01 ${source.key}"

    private companion object {
        const val TAG = "EpgPresetManager"
    }
}
