package com.streamvault.data.health

import com.streamvault.data.local.dao.ChannelDao
import com.streamvault.data.mapper.toDomain
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.ChannelHealthRules
import com.streamvault.domain.model.ChannelHealthScanState
import com.streamvault.domain.model.ChannelHealthSuggestion
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.XtreamConfig
import com.streamvault.domain.repository.ChannelHealthRepository
import com.streamvault.domain.repository.ProviderSnapshotRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
                        compareBy<ChannelHealthSuggestion>(String.CASE_INSENSITIVE_ORDER) { it.channel.categoryName.orEmpty() }
                            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.channel.name }
                    )
            }
        }

    override suspend fun startScan(providerId: Long, categoryIds: Set<Long>?): Result<Unit> {
        val snapshot = snapshots.getSnapshot(providerId)
            ?: return Result.error("Provider not found")
        val config = snapshot.configuration as? XtreamConfig
            ?: return Result.error("Channel health scans only support Xtream Codes providers")
        if (!scanner.allowsHls(providerId, config)) {
            return Result.error("The account does not offer HLS (m3u8) output, which the scan needs")
        }
        scanner.markQueued(providerId)
        scheduler.enqueue(providerId, categoryIds, results.now())
        return Result.success(Unit)
    }

    override suspend fun cancelScan(providerId: Long) {
        scheduler.cancel(providerId)
        scanner.markStopped(providerId)
    }

    override suspend fun hideChannels(providerId: Long, channelIds: Set<Long>) {
        if (channelIds.isEmpty()) return
        val hidden = preferences.getHiddenChannelIds(providerId).first()
        preferences.setHiddenChannelIds(providerId, hidden + channelIds)
    }

    override suspend fun clearResults(providerId: Long) {
        results.clear(providerId)
    }
}
