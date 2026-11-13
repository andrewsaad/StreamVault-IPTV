package com.streamvault.data.health

import com.streamvault.data.local.dao.ChannelDao
import com.streamvault.data.mapper.toDomain
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.ChannelHealthImportSummary
import com.streamvault.domain.model.ChannelHealthRules
import com.streamvault.domain.model.ChannelHealthScanScope
import com.streamvault.domain.model.ChannelHealthScanState
import com.streamvault.domain.model.ChannelHealthScopeCounts
import com.streamvault.domain.model.ChannelHealthSuggestion
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.XtreamConfig
import com.streamvault.domain.repository.ChannelHealthRepository
import com.streamvault.domain.repository.ProviderSnapshotRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

@Singleton
class ChannelHealthRepositoryImpl @Inject constructor(
    private val snapshots: ProviderSnapshotRepository,
    private val channelDao: ChannelDao,
    private val preferences: PreferencesRepository,
    private val results: ChannelHealthResults,
    private val scanner: ChannelHealthScanner,
    private val scheduler: ChannelHealthScanScheduler,
) : ChannelHealthRepository {

    // "Running" follows the queued work, not the scanner's own flag, so the state does not drop to
    // idle in the gaps when WorkManager has stopped the job and is about to resume it.
    override fun observeScanState(providerId: Long): Flow<ChannelHealthScanState> =
        combine(scanner.observeState(providerId), scheduler.observeActive(providerId)) { state, active ->
            state.copy(running = active)
        }

    override fun observeSuggestions(providerId: Long): Flow<List<ChannelHealthSuggestion>> =
        combine(results.observe(providerId), preferences.getHiddenChannelIds(providerId)) { document, hidden ->
            document.records.values
                .filter { ChannelHealthRules.isSuggestion(it) && it.channelId !in hidden }
                .associateBy { it.channelId }
        }.map { suggested ->
            if (suggested.isEmpty()) {
                emptyList()
            } else {
                channelDao.getByProviderSync(providerId)
                    .filter { it.id in suggested }
                    .map { ChannelHealthSuggestion(it.toDomain(), suggested.getValue(it.id)) }
                    .sortedWith(
                        compareBy<ChannelHealthSuggestion>(
                            { it.channel.categoryName.orEmpty().lowercase() },
                            { it.channel.name.lowercase() },
                        )
                    )
            }
        }

    override suspend fun startScan(
        providerId: Long,
        categoryIds: Set<Long>?,
        scope: ChannelHealthScanScope,
    ): Result<Unit> {
        val snapshot = snapshots.getSnapshot(providerId)
            ?: return Result.error("Provider not found")
        val config = snapshot.configuration as? XtreamConfig
            ?: return Result.error("Channel health scans only support Xtream Codes providers")
        if (scope != ChannelHealthScanScope.ALL) {
            val document = documentWithBaseline(providerId)
            val anything = listedChannels(providerId).any { selectsChannel(scope, it.id, document) }
            if (document.knownIds.isEmpty() || !anything) return Result.error("Nothing to scan")
        }
        if (!scanner.allowsHls(providerId, config)) {
            return Result.error("The account does not offer HLS (m3u8) output, which the scan needs")
        }
        scanner.markQueued(providerId)
        scheduler.enqueue(providerId, categoryIds, results.now(), scope)
        return Result.success(Unit)
    }

    override suspend fun getScopeCounts(providerId: Long): ChannelHealthScopeCounts {
        val document = documentWithBaseline(providerId)
        if (document.knownIds.isEmpty()) return ChannelHealthScopeCounts()
        val channels = listedChannels(providerId)
        return ChannelHealthScopeCounts(
            newChannels = channels.count { selectsChannel(ChannelHealthScanScope.NEW_ONLY, it.id, document) },
            badChannels = channels.count { selectsChannel(ChannelHealthScanScope.BAD_ONLY, it.id, document) },
        )
    }

    override suspend fun exportSnapshot(providerId: Long, label: String): Result<String> {
        val document = documentWithBaseline(providerId)
        if (document.knownIds.isEmpty()) return Result.error("Nothing has been scanned yet")
        val channels = listedChannels(providerId).map { SnapshotChannel(it.id, it.streamId, it.name) }
        val snapshot = ChannelHealthSnapshots.build(channels, document, label, results.now())
        return Result.success(ChannelHealthSnapshots.encode(snapshot))
    }

    override suspend fun importSnapshot(providerId: Long, text: String): Result<ChannelHealthImportSummary> {
        val snapshot = ChannelHealthSnapshots.decode(text) ?: return Result.error("Not a channel health snapshot")
        val channels = listedChannels(providerId).map { SnapshotChannel(it.id, it.streamId, it.name) }
        val plan = ChannelHealthSnapshots.planImport(snapshot, channels, documentWithBaseline(providerId))
        results.applyImport(providerId, plan)
        val summary = plan.summary
        return Result.success(
            ChannelHealthImportSummary(
                working = summary.okImported,
                failed = summary.badImported,
                skipped = summary.alreadyKnown + summary.unknown + summary.nameMismatch,
            )
        )
    }

    private suspend fun listedChannels(providerId: Long) =
        channelDao.getByProviderSync(providerId).filter { it.streamId > 0L }

    /** The stored results, after taking a scan saved before known channels were tracked as the baseline. */
    private suspend fun documentWithBaseline(providerId: Long): ChannelHealthDocument {
        results.ensureBaseline(providerId, listedChannels(providerId).mapTo(HashSet()) { it.id })
        return results.read(providerId)
    }

    override suspend fun cancelScan(providerId: Long) {
        scheduler.cancel(providerId)
        scanner.markStopped(providerId)
    }

    override suspend fun hideChannels(providerId: Long, channelIds: Set<Long>) {
        preferences.addHiddenChannelIds(providerId, channelIds)
    }

    override suspend fun clearResults(providerId: Long) {
        results.clear(providerId)
        scanner.clearGaveUp(providerId)
    }
}
