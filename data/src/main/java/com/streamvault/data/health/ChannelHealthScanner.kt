package com.streamvault.data.health

import android.util.Log
import com.streamvault.data.local.dao.ChannelDao
import com.streamvault.data.remote.http.USER_AGENT_HEADER
import com.streamvault.data.remote.http.buildGenericProviderRequestProfile
import com.streamvault.data.remote.xtream.XtreamApiService
import com.streamvault.data.remote.xtream.XtreamStreamKind
import com.streamvault.data.remote.xtream.XtreamUrlFactory
import com.streamvault.domain.model.ChannelHealthPauseReason
import com.streamvault.domain.model.ChannelHealthScanState
import com.streamvault.domain.model.ChannelHealthStatus
import com.streamvault.domain.model.XtreamConfig
import com.streamvault.domain.repository.ProviderSnapshotRepository
import com.streamvault.domain.sync.AutomaticSyncActivity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

internal data class ChannelHealthTimings(
    val pauseBetweenChannelsMs: Long = 6_000L,
    val backoffMs: Long = 5L * 60_000L,
    val playbackPollMs: Long = 15_000L,
    val unclearInARowBeforeBackoff: Int = 3,
    /** Non-OK answers of any kind in a row that count as a provider problem, not as dead channels. */
    val massFailureStreak: Int = 10,
    /** Backoffs in a row with no OK answer in between before the scan gives up. */
    val maxBackoffsInARow: Int = 3,
    val flushEvery: Int = 20,
)

private data class AccountLimits(val maxConnections: Int, val activeConnections: Int) {
    /** No free connection left for a probe. */
    val saturated: Boolean get() = activeConnections >= maxConnections
}

/**
 * Walks a provider's live channels one at a time with the provider's own login, keeping to the
 * connections the account allows, and records what the [ChannelHealthProbe] finds. Nothing is
 * logged that could carry the login.
 */
@Singleton
class ChannelHealthScanner internal constructor(
    private val snapshots: ProviderSnapshotRepository,
    private val channelDao: ChannelDao,
    private val xtreamApi: XtreamApiService,
    private val probe: ChannelHealthProbe,
    private val results: ChannelHealthResults,
    private val timings: ChannelHealthTimings,
) {
    @Inject
    internal constructor(
        snapshots: ProviderSnapshotRepository,
        channelDao: ChannelDao,
        xtreamApi: XtreamApiService,
        probe: ChannelHealthProbe,
        results: ChannelHealthResults,
    ) : this(snapshots, channelDao, xtreamApi, probe, results, ChannelHealthTimings())

    private val runtime = MutableStateFlow<Map<Long, ChannelHealthScanState>>(emptyMap())

    fun observeState(providerId: Long): Flow<ChannelHealthScanState> = combine(
        runtime.map { it[providerId] ?: ChannelHealthScanState() }.distinctUntilChanged(),
        results.observe(providerId).map { it.lastFinishedAt }.distinctUntilChanged(),
    ) { state, lastFinishedAt -> state.copy(lastFinishedAt = lastFinishedAt) }

    /** Shows the scan as running as soon as it is queued, before the worker starts. */
    internal fun markQueued(providerId: Long) {
        setState(providerId) { ChannelHealthScanState(running = true) }
    }

    /** Clears the running flag of a scan that was cancelled before or while it ran. */
    internal fun markStopped(providerId: Long) {
        setState(providerId) { it.copy(running = false, paused = null) }
    }

    /**
     * Probes the provider's live channels. Channels probed at or after [startedAt] are skipped, so a
     * scan the system stopped and WorkManager restarted carries on instead of starting over.
     * Returns false when the scan could not run or gave up; the reason is never the login.
     */
    internal suspend fun run(providerId: Long, categoryIds: Set<Long>?, startedAt: Long): Boolean {
        val config = snapshots.getSnapshot(providerId)?.configuration as? XtreamConfig
        if (config == null) {
            markStopped(providerId)
            return false
        }
        val channels = channelDao.getByProviderSync(providerId)
            .filter { it.streamId > 0L && (categoryIds == null || it.categoryId in categoryIds) }
            .sortedBy { it.id }
        val alreadyChecked = results.read(providerId).records
        val headers = requestHeaders(providerId, config)
        val pending = ArrayList<ChannelProbeOutcome>()
        // Non-OK answers since the last OK; kept back from the store until an OK shows they are isolated.
        val streak = ArrayList<ChannelProbeOutcome>()
        var scanned = 0
        var finished = false
        setState(providerId) { ChannelHealthScanState(running = true, total = channels.size) }
        try {
            var account = readAccount(providerId, config, previous = null)
            var unclearInARow = 0
            var backoffsInARow = 0
            for (channel in channels) {
                currentCoroutineContext().ensureActive()
                if ((alreadyChecked[channel.id]?.checkedAt ?: -1L) >= startedAt) {
                    scanned++
                    setState(providerId) { it.copy(scanned = scanned) }
                    continue
                }
                account = waitForFreeConnection(providerId, config, account)
                val url = XtreamUrlFactory.buildPlaybackUrl(
                    serverUrl = config.serverUrl,
                    username = config.username,
                    password = config.password,
                    kind = XtreamStreamKind.LIVE,
                    streamId = channel.streamId,
                    containerExtension = "m3u8",
                )
                val status = probe.probe(url, headers)
                val outcome = ChannelProbeOutcome(channel.id, status, results.now())
                scanned++
                setState(providerId) { it.copy(scanned = scanned) }
                if (status == ChannelHealthStatus.OK) {
                    // A good answer shows the failures before it were the channels' own, so keep them.
                    pending += streak
                    pending += outcome
                    streak.clear()
                    unclearInARow = 0
                    backoffsInARow = 0
                } else {
                    streak += outcome
                    if (status == ChannelHealthStatus.UNCLEAR) unclearInARow++ else unclearInARow = 0
                }
                if (pending.size >= timings.flushEvery) flush(providerId, pending)
                if (streak.size >= timings.massFailureStreak) {
                    // Everything failing at once points at the provider or the account, not the channels.
                    streak.clear()
                    if (++backoffsInARow >= timings.maxBackoffsInARow) return false
                    backOff(providerId)
                    unclearInARow = 0
                    account = readAccount(providerId, config, previous = account)
                } else if (unclearInARow >= timings.unclearInARowBeforeBackoff) {
                    flush(providerId, pending)
                    if (++backoffsInARow >= timings.maxBackoffsInARow) return false
                    backOff(providerId)
                    unclearInARow = 0
                    account = readAccount(providerId, config, previous = account)
                } else if (scanned < channels.size) {
                    delay(timings.pauseBetweenChannelsMs)
                }
            }
            finished = true
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            withContext(NonCancellable) {
                pending += streak
                flush(providerId, pending)
                if (finished) results.markFinished(providerId)
                setState(providerId) { it.copy(running = false, paused = null) }
            }
        }
    }

    private suspend fun backOff(providerId: Long) {
        setState(providerId) { it.copy(paused = ChannelHealthPauseReason.PROVIDER_BACKOFF) }
        delay(timings.backoffMs)
        setState(providerId) { it.copy(paused = null) }
    }

    /** Yields while the account has no free connection: local playback on a one-connection account, or others using it. */
    private suspend fun waitForFreeConnection(
        providerId: Long,
        config: XtreamConfig,
        current: AccountLimits,
    ): AccountLimits {
        var account = current
        while (true) {
            val localPlayback = account.maxConnections <= 1 && AutomaticSyncActivity.hasPlayback
            if (!localPlayback && !account.saturated) {
                setState(providerId) { if (it.paused == null) it else it.copy(paused = null) }
                return account
            }
            setState(providerId) { it.copy(paused = ChannelHealthPauseReason.PLAYBACK_ACTIVE) }
            delay(timings.playbackPollMs)
            // Look again once nothing of ours is playing, so a stale "in use" count does not stick.
            if (!AutomaticSyncActivity.hasPlayback) account = readAccount(providerId, config, previous = account)
        }
    }

    /** Max and active connections from the account info call; keeps [previous] when the call fails. */
    private suspend fun readAccount(providerId: Long, config: XtreamConfig, previous: AccountLimits?): AccountLimits =
        try {
            val info = xtreamApi.authenticate(
                XtreamUrlFactory.buildPlayerApiUrl(config.serverUrl, config.username, config.password),
                buildGenericProviderRequestProfile("provider:$providerId/health", config.httpUserAgent, config.httpHeaders),
            ).userInfo
            AccountLimits(
                maxConnections = info.maxConnections.trim().toIntOrNull()?.takeIf { it > 0 } ?: 1,
                activeConnections = info.activeConnections.trim().toIntOrNull()?.takeIf { it >= 0 } ?: 0,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Class name only: the message of a failed request can include the login url.
            Log.w(TAG, "Account info unavailable (${error.javaClass.simpleName})")
            previous ?: AccountLimits(maxConnections = 1, activeConnections = 0)
        }

    private suspend fun flush(providerId: Long, pending: MutableList<ChannelProbeOutcome>) {
        if (pending.isEmpty()) return
        results.record(providerId, pending.toList())
        pending.clear()
    }

    private fun requestHeaders(providerId: Long, config: XtreamConfig): Map<String, String> {
        val profile = buildGenericProviderRequestProfile("provider:$providerId/health", config.httpUserAgent, config.httpHeaders)
        return buildMap {
            putAll(profile.headers)
            profile.userAgent?.let { put(USER_AGENT_HEADER, it) }
        }
    }

    private fun setState(providerId: Long, transform: (ChannelHealthScanState) -> ChannelHealthScanState) {
        runtime.update { states -> states + (providerId to transform(states[providerId] ?: ChannelHealthScanState())) }
    }

    private companion object {
        const val TAG = "ChannelHealthScanner"
    }
}
