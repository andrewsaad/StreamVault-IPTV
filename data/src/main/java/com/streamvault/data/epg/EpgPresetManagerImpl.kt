package com.streamvault.data.epg

import android.util.Log
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.domain.model.EpgPreset
import com.streamvault.domain.model.EpgPresetCatalog
import com.streamvault.domain.model.EpgPresetSource
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.EpgPresetErrors
import com.streamvault.domain.repository.EpgPresetManager
import com.streamvault.domain.repository.EpgPresetSourceStatus
import com.streamvault.domain.repository.EpgPresetStatus
import com.streamvault.domain.repository.EpgSourceRepository
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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

    /** One run of imports; [ids] are the source rows it covers and [job] ends when all of them have. */
    private class ImportBatch(val ids: List<Long>) {
        lateinit var job: Deferred<Result<Unit>>
    }

    /**
     * The import batches running for each source row, so turning a preset off can stop all of them (F19).
     * A row can have several: an address edit starts its own batch while an earlier one still runs.
     */
    private val importBatches = ConcurrentHashMap<Long, MutableSet<ImportBatch>>()

    private fun registerBatch(batch: ImportBatch) {
        batch.ids.forEach { id ->
            importBatches.computeIfAbsent(id) { ConcurrentHashMap.newKeySet() }.add(batch)
        }
    }

    private fun unregisterBatch(batch: ImportBatch) {
        batch.ids.forEach { id ->
            // Atomic with registerBatch: a key is dropped only while its set is empty.
            importBatches.computeIfPresent(id) { _, set ->
                set.remove(batch)
                if (set.isEmpty()) null else set
            }
        }
    }

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
                    sources = preset.sources.map { source ->
                        val row = if (enabled) state.sources[source.key]?.let { rowsById[it.sourceId] } else null
                        EpgPresetSourceStatus(source.key, row?.id, row?.url ?: addressFor(source, state))
                    },
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

    override suspend fun updateAddress(presetId: String, sourceKey: String, url: String): Result<Unit> {
        val preset = EpgPresetCatalog.byId(presetId) ?: return Result.error(EpgPresetErrors.UNKNOWN_PRESET)
        val source = preset.sources.firstOrNull { it.key == sourceKey }
            ?: return Result.error(EpgPresetErrors.UNKNOWN_PRESET)
        val trimmed = url.trim()
        return mutex.withLock {
            val state = store.current()
            if (preset.id !in state.enabledPresetIds) return@withLock Result.error(EpgPresetErrors.NOT_ENABLED)
            // The repository accepts a blank address (it means "no guide"), which a preset file never is.
            if (trimmed.isEmpty()) return@withLock Result.error(EpgPresetErrors.INVALID_ADDRESS)
            val row = state.sources[source.key]?.let { sources.getSourceById(it.sourceId) }
                ?: return@withLock Result.error(EpgPresetErrors.SOURCE_UNAVAILABLE)
            // Saving the same address again is not a new file, so it keeps the gap (FR-007).
            if (trimmed == row.url) return@withLock Result.success(Unit)
            val updated = sources.updateSource(row.copy(url = trimmed))
            if (updated is Result.Error) return@withLock updated
            // The repository may normalize the address, so remember what it stored.
            val saved = sources.getSourceById(row.id)?.url ?: trimmed
            // A retyped address can normalize back to the stored one; that is still the same file.
            if (saved == row.url) return@withLock Result.success(Unit)
            store.update { it.copy(addresses = it.addresses.remembering(source, saved)) }
            // A new address is a new file, so the gap left by the old one no longer applies.
            store.clearRefreshAttempt(row.id)
            // Only this file is imported; the editor closes at once and the status line shows progress.
            importScope.launch { refresh(preset.id, listOf(row.id)) }
            Result.success(Unit)
        }
    }

    override suspend fun reconcile() {
        // Providers given a preset row here; their matches are recomputed once, after the imports.
        val newlyAssigned = HashSet<Long>()
        val due = mutex.withLock {
            val state = store.current()
            val enabled = EpgPresetCatalog.all.filter { it.id in state.enabledPresetIds }
            val usedKeys = enabled.flatMapTo(HashSet()) { preset -> preset.sources.map { it.key } }
            // Left by a process death mid-enable or a key gone from the catalog. Released before the
            // rows below are ensured, so a key renamed at the same address cannot lose the row it just took.
            state.sources.filterKeys { it !in usedKeys }.values.forEach { release(it) }
            val fresh = mutableListOf<Long>()
            var records = state.sources.filterKeys { it in usedKeys }
            enabled.flatMap { it.sources }.distinctBy { it.key }.forEach { source ->
                val before = records[source.key]
                val ensured = ensureSource(source, before, addressFor(source, state)) ?: return@forEach
                val (record, moved) = followBuiltInAddress(source, ensured, state)
                if (record.sourceId != before?.sourceId || moved) fresh += record.sourceId
                val assigned = assignToAllProviders(source.key, record)
                newlyAssigned += assigned.assignedProviderIds - record.assignedProviderIds
                records = records + (source.key to assigned)
            }
            rankInCatalogOrder(records, newlyAssigned)
            store.update { it.copy(sources = records) }
            val unfinished = records.values.map { it.sourceId }.distinct()
                .filter { it !in fresh && !importBatches.containsKey(it) && neverFinished(it) }
            unfinished.forEach { store.clearRefreshAttempt(it) }
            fresh + unfinished
        }
        if (due.isNotEmpty() || newlyAssigned.isNotEmpty()) {
            refresh(presetId = null, sourceIds = due, alsoResolve = newlyAssigned)
        }
    }

    /** No success and no error: the import never reached an outcome, usually because the process died. */
    private suspend fun neverFinished(sourceId: Long): Boolean {
        val row = sources.getSourceById(sourceId) ?: return false
        return row.lastSuccessAt <= 0L && row.lastError.isNullOrBlank()
    }

    private suspend fun enable(preset: EpgPreset): Result<Unit> {
        // Providers given a preset row here; their matches are recomputed once, after the imports.
        val newlyAssigned = HashSet<Long>()
        val sourceIds: List<Long>? = mutex.withLock {
            val state = store.current()
            val records = state.sources.toMutableMap()
            for (source in preset.sources) {
                val record = ensureSource(source, records[source.key], addressFor(source, state))
                    ?: return@withLock null
                records[source.key] = record
                // Saved before assigning, so a crash mid-way leaves a row reconcile() can still find.
                store.update { it.copy(sources = it.sources + (source.key to record)) }
                val assigned = assignToAllProviders(source.key, record)
                newlyAssigned += assigned.assignedProviderIds - record.assignedProviderIds
                records[source.key] = assigned
            }
            rankInCatalogOrder(records, newlyAssigned)
            store.update { it.copy(enabledPresetIds = it.enabledPresetIds + preset.id, sources = records) }
            preset.sources.map { records.getValue(it.key).sourceId }
        }
        if (sourceIds == null) {
            // The assignments made before the failure skipped their pass; they still get one, outside the lock.
            if (newlyAssigned.isNotEmpty()) sources.refreshSources(emptyList(), alsoResolve = newlyAssigned)
            return Result.error(EpgPresetErrors.SOURCE_UNAVAILABLE)
        }
        // The first attempt of a new row is always allowed; a shared row inside its gap is skipped,
        // but the providers it was just assigned to are still resolved.
        refresh(preset.id, sourceIds, alsoResolve = newlyAssigned)
        return Result.success(Unit)
    }

    private suspend fun disable(preset: EpgPreset): Result<Unit> = mutex.withLock {
        val state = store.current()
        if (preset.id !in state.enabledPresetIds) return Result.success(Unit)
        val remaining = state.enabledPresetIds - preset.id
        val stillUsed = EpgPresetCatalog.all
            .filter { it.id in remaining }
            .flatMapTo(HashSet()) { other -> other.sources.map { it.key } }
        val released = preset.sources.filterNot { it.key in stillUsed }
        // F19: an import still running for a row this deletes is stopped first, so it cannot write into
        // the deleted row, and its cancelled attempt is cleared while the row's file key is still known.
        cancelImports(released.mapNotNull { state.sources[it.key]?.takeIf { record -> record.owned }?.sourceId })
        var addresses = state.addresses
        released.forEach { source ->
            val record = state.sources[source.key] ?: return@forEach
            // An address changed in any editor is kept, so turning the preset on again reuses it.
            sources.getSourceById(record.sourceId)?.let { row -> addresses = addresses.remembering(source, row.url) }
            release(record)
        }
        val releasedKeys = released.mapTo(HashSet()) { it.key }
        store.update {
            it.copy(enabledPresetIds = remaining, sources = it.sources - releasedKeys, addresses = addresses)
        }
        Result.success(Unit)
    }

    /**
     * Cancels the import batches running for [sourceIds] and waits for them to end. A batch also covers
     * other sources (a file shared with another preset); those are imported again, in a new batch.
     */
    private suspend fun cancelImports(sourceIds: List<Long>) {
        val batches = sourceIds.flatMap { importBatches[it].orEmpty().toList() }.distinct()
        batches.forEach { it.job.cancelAndJoin() }
        val survivors = batches.flatMap { it.ids }.distinct().filter { it !in sourceIds }
        if (survivors.isNotEmpty()) importScope.launch { refresh(presetId = null, sourceIds = survivors) }
    }

    /** Deletes a row the preset created; a hand-added row keeps everything except the preset's own assignments. */
    private suspend fun release(record: EpgPresetSourceRecord) {
        if (record.owned) {
            sources.deleteSource(record.sourceId)
        } else {
            record.assignedProviderIds.forEach { sources.unassignSourceFromProvider(it, record.sourceId) }
        }
    }

    /** The row for [source]: the remembered one if it still exists, else a hand-added row with [url], else a new one. */
    private suspend fun ensureSource(
        source: EpgPresetSource,
        remembered: EpgPresetSourceRecord?,
        url: String,
    ): EpgPresetSourceRecord? {
        if (remembered != null && sources.getSourceById(remembered.sourceId) != null) return remembered
        val existing = sources.getAllSources().first().firstOrNull { it.url == url }
        if (existing != null) {
            // Our own row survives under the same id; anything else was added by hand and is never deleted.
            val owned = remembered?.owned == true && remembered.sourceId == existing.id
            return EpgPresetSourceRecord(existing.id, owned = owned, assignedProviderIds = emptySet(), defaultUrl = source.url)
        }
        return when (val added = sources.addSource(name = sourceName(source), url = url)) {
            is Result.Success ->
                EpgPresetSourceRecord(added.data.id, owned = true, assignedProviderIds = emptySet(), defaultUrl = source.url)
            else -> {
                Log.w(TAG, "Could not add preset source ${source.key}: ${added.errorMessageOrNull()}")
                null
            }
        }
    }

    /**
     * FR-023: a build that corrects a built-in address moves the rows the preset created and the
     * user never edited onto it, and lifts the gap so the new file is imported now. Returns the
     * record to keep and whether the row moved. A hand-added or edited row keeps its address.
     */
    private suspend fun followBuiltInAddress(
        source: EpgPresetSource,
        record: EpgPresetSourceRecord,
        state: EpgPresetState,
    ): Pair<EpgPresetSourceRecord, Boolean> {
        if (record.defaultUrl == source.url) return record to false
        val row = sources.getSourceById(record.sourceId) ?: return record to false
        if (record.defaultUrl == null) {
            // Saved before the built-in address was remembered: adopt it only when the row already uses it.
            return (if (row.url == source.url) record.copy(defaultUrl = source.url) else record) to false
        }
        val unedited = record.owned && source.key !in state.addresses && row.url == record.defaultUrl
        if (!unedited) return record to false
        if (sources.updateSource(row.copy(url = source.url)) is Result.Error) return record to false
        store.clearRefreshAttempt(record.sourceId)
        return record.copy(defaultUrl = source.url) to true
    }

    /**
     * Assigns [record] to every provider not yet seen, below that provider's existing sources.
     * A seen provider that lacks it had it removed by the user, so it stays removed.
     * Each assignment is saved under [key] as it is made, so one cut off by process death or an
     * early return is still undone when the preset is turned off, even on a hand-added row.
     */
    private suspend fun assignToAllProviders(key: String, record: EpgPresetSourceRecord): EpgPresetSourceRecord {
        val alreadyAssigned = sources.getProviderIdsForSource(record.sourceId).toSet()
        val handled = record.handledProviderIds + alreadyAssigned
        // Keep only providers that still have the assignment, plus the ones added below.
        var current = record.copy(
            assignedProviderIds = record.assignedProviderIds.filterTo(HashSet()) { it in alreadyAssigned },
            handledProviderIds = handled,
        )
        for (providerId in providers.providerIds()) {
            if (providerId in handled) continue
            val lowest = sources.getAssignmentsForProvider(providerId).first().maxOfOrNull { it.priority } ?: -1
            // No pass per assignment (F13): the caller resolves each provider once, after importing.
            if (sources.assignSourceToProvider(providerId, record.sourceId, lowest + 1, resolve = false) is Result.Success) {
                current = current.copy(
                    assignedProviderIds = current.assignedProviderIds + providerId,
                    handledProviderIds = current.handledProviderIds + providerId,
                )
                val saved = current
                store.update { it.copy(sources = it.sources + (key to saved)) }
            }
        }
        return current
    }

    /**
     * Puts the preset's own rows of each provider in [providerIds] in catalog order (QATAR1 before
     * AE1, whichever preset was turned on first), reusing the priorities those rows
     * already hold, so hand-added sources keep their place above them. Runs only for providers
     * that just got a new preset row, so a user's own reordering stands until then.
     */
    private suspend fun rankInCatalogOrder(records: Map<String, EpgPresetSourceRecord>, providerIds: Set<Long>) {
        for (providerId in providerIds) {
            val priorities = sources.getAssignmentsForProvider(providerId).first()
                .associate { it.epgSourceId to it.priority }
            val ranked = CATALOG_KEY_ORDER.mapNotNull(records::get)
                .filter { providerId in it.assignedProviderIds && it.sourceId in priorities }
                .map { it.sourceId }
                .distinct()
            val slots = ranked.map(priorities::getValue).sorted()
            ranked.zip(slots).forEach { (sourceId, priority) ->
                if (priorities[sourceId] != priority) {
                    sources.updateAssignmentPriority(providerId, sourceId, priority, resolve = false)
                }
            }
        }
    }

    /**
     * Runs in [importScope], so an import outlives the screen that started it; only the wait is
     * cancelled. Every provider is resolved once after all the imports, plus [alsoResolve] (F13).
     */
    private suspend fun refresh(presetId: String?, sourceIds: List<Long>, alsoResolve: Set<Long> = emptySet()): Result<Unit> {
        val ids = sourceIds.distinct()
        val providersToResolve = alsoResolve.toSet()
        if (presetId != null) refreshing.update { it + presetId }
        val batch = ImportBatch(ids)
        batch.job = importScope.async(start = CoroutineStart.LAZY) {
            val errors = sources.refreshSources(ids, providersToResolve).values
                .mapNotNull { (it as? Result.Error)?.message }
            if (errors.isEmpty()) Result.success(Unit) else Result.error(errors.joinToString("; "))
        }
        // Registered before it starts and cleaned up on completion, which also covers a cancel before the first step.
        registerBatch(batch)
        batch.job.invokeOnCompletion {
            unregisterBatch(batch)
            if (presetId != null) refreshing.update { it - presetId }
        }
        batch.job.start()
        return try {
            batch.job.await()
        } catch (cancelled: CancellationException) {
            // Our own cancellation ends the wait; the import was stopped by the preset being turned off.
            currentCoroutineContext().ensureActive()
            Result.error(EpgPresetErrors.NOT_ENABLED)
        }
    }

    /** The user's edited address for [source], else the built-in one. */
    private fun addressFor(source: EpgPresetSource, state: EpgPresetState): String =
        state.addresses[source.key] ?: source.url

    /** Keeps [url] as the edit for [source], or forgets the edit when it is blank or the built-in address again. */
    private fun Map<String, String>.remembering(source: EpgPresetSource, url: String): Map<String, String> {
        val trimmed = url.trim()
        return if (trimmed.isEmpty() || trimmed == source.url) this - source.key else this + (source.key to trimmed)
    }

    /** "epgshare01 AE1", "open-epg QATAR1": the site, then the file. A source name, never translated. */
    private fun sourceName(source: EpgPresetSource): String {
        val site = runCatching { URI(source.url).host }.getOrNull().orEmpty()
            .removePrefix("www.").substringBefore('.')
        return listOf(site, source.key).filter(String::isNotBlank).joinToString(" ")
    }

    private companion object {
        const val TAG = "EpgPresetManager"

        /** Every preset file key, in the order the catalog lists them. */
        val CATALOG_KEY_ORDER: List<String> = EpgPresetCatalog.all.flatMap { it.sources }.map { it.key }.distinct()
    }
}
