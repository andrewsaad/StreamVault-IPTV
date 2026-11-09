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
    /** First wait while the account has no free connection; doubles up to [maxPlaybackPollMs]. */
    val playbackPollMs: Long = 15_000L,
    val maxPlaybackPollMs: Long = 5L * 60_000L,
    /** Total wait for a free connection before the scan gives up. */
    val maxPauseMs: Long = 60L * 60_000L,
    val unclearInARowBeforeBackoff: Int = 3,
    /** Non-OK answers of any kind in a row that count as a provider problem, not as dead channels. */
    val massFailureStreak: Int = 10,
    /** Backoffs in a row with no OK answer in between before the scan gives up. */
    val maxBackoffsInARow: Int = 3,
    val flushEvery: Int = 20,
)

private data class AccountLimits(
    val maxConnections: Int,
    val activeConnections: Int,
    /** False only when the account lists its output formats and HLS (m3u8) is not among them. */
    val allowsHls: Boolean = true,
) {
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

    /** False when the account says it does not serve HLS (m3u8), which is the only format the probe uses. */
    internal suspend fun allowsHls(providerId: Long, config: XtreamConfig): Boolean =
        readAccount(providerId, config, previous = null).allowsHls

    /** Shows the scan as running as soon as it is queued, before the worker starts. */
    internal fun markQueued(providerId: Long) {
        setState(providerId) { ChannelHealthScanState(running = true) }
    }

    /** Clears the running flag of a scan that was cancelled before or while it ran. */
    internal fun markStopped(providerId: Long) {
        setState(providerId) { it.copy(running = false, paused = null, gaveUp = false) }
    }

    /** Forgets that the last scan gave up, e.g. when the user clears the results. */
    internal fun clearGaveUp(providerId: Long) {
        setState(providerId) { if (it.gaveUp) it.copy(gaveUp = false) else it }
    }

    /**
     * Probes the provider's live channels. When the same scan ([startedAt]) ran before, the channels it
     * had settled are skipped, so a scan the system stopped and WorkManager restarted carries on.
     * Channels the provider could not answer for (UNCLEAR) are probed once more after the main pass,
     * following one provider backoff; the scan only counts as finished after that retry pass.
     * Returns false when the scan could not run or gave up; the reason is never the login. A scan that
     * gave up is flagged in its state ([ChannelHealthScanState.gaveUp]) so the user can be told.
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
        val resume = results.read(providerId).resume?.takeIf { it.startedAt == startedAt }
        val headers = requestHeaders(providerId, config)
        val pending = ArrayList<ChannelProbeOutcome>()
        // Non-OK answers since the last OK; kept back from the store until an OK shows they are isolated.
        val streak = ArrayList<ChannelProbeOutcome>()
        // Channels that answered UNCLEAR in the main pass, ascending; they get one more try afterwards.
        val retryIds = sortedSetOf<Long>()
        resume?.retryIds?.let { retryIds.addAll(it) }
        // Of those, the ones whose answer was an HTTP 5xx (not a timeout or IO error).
        val serverErrorIds = sortedSetOf<Long>()
        resume?.serverErrorIds?.let { serverErrorIds.addAll(it) }
        // Retried channels the provider refused with a 5xx again: they count as EMPTY once settled.
        val refusedIds = HashSet<Long>()
        var retrying = resume?.retryAfterChannelId != null
        var queue = if (retrying) channels.filter { it.id in retryIds } else channels
        // Ids up to this one are already done in the current pass (null: none).
        var skipThrough = if (retrying) resume?.retryAfterChannelId else resume?.afterChannelId
        var total = if (retrying) channels.size + queue.size else channels.size
        var scanned = if (retrying) channels.size else 0
        var lastProbedId = resume?.afterChannelId ?: 0L
        var retryAfterId: Long? = if (retrying) resume?.retryAfterChannelId else null
        var finished = false
        var gaveUp = false
        // Channels up to this id are settled; a held-back streak is not, so it is probed again after a restart.
        fun resumePoint(): ChannelHealthResume {
            val heldBack = streak.firstOrNull()?.let { it.channelId - 1 }
            val retryAfter = retryAfterId
            if (retryAfter != null) {
                return ChannelHealthResume(
                    startedAt,
                    lastProbedId,
                    retryIds.toList(),
                    heldBack ?: retryAfter,
                    serverErrorIds.toList(),
                )
            }
            val point = heldBack ?: lastProbedId
            return ChannelHealthResume(
                startedAt,
                point,
                retryIds.filter { it <= point },
                serverErrorIds = serverErrorIds.filter { it <= point },
            )
        }
        // A channel the provider refused with a 5xx on both passes counts as EMPTY; any other UNCLEAR stays unstored.
        fun settled(outcomes: List<ChannelProbeOutcome>) = outcomes.map {
            if (it.channelId in refusedIds) it.copy(status = ChannelHealthStatus.EMPTY) else it
        }
        var saved = resume
        suspend fun flush() {
            val point = resumePoint()
            if (pending.isEmpty() && point == saved) return
            results.record(providerId, pending.toList(), point)
            pending.clear()
            saved = point
        }
        setState(providerId) { ChannelHealthScanState(running = true, total = total, scanned = scanned) }
        try {
            var account = readAccount(providerId, config, previous = null)
            if (!account.allowsHls) {
                gaveUp = true
                return false
            }
            var unclearInARow = 0
            var backoffsInARow = 0
            while (true) {
                for (channel in queue) {
                    currentCoroutineContext().ensureActive()
                    if (skipThrough != null && channel.id <= skipThrough) {
                        scanned++
                        setState(providerId) { it.copy(scanned = scanned) }
                        continue
                    }
                    account = waitForFreeConnection(providerId, config, account) ?: run {
                        gaveUp = true
                        return false
                    }
                    val url = XtreamUrlFactory.buildPlaybackUrl(
                        serverUrl = config.serverUrl,
                        username = config.username,
                        password = config.password,
                        kind = XtreamStreamKind.LIVE,
                        streamId = channel.streamId,
                        containerExtension = "m3u8",
                    )
                    val result = probe.probe(url, headers)
                    val status = result.status
                    val outcome = ChannelProbeOutcome(channel.id, status, results.now())
                    if (retrying) {
                        retryAfterId = channel.id
                    } else {
                        lastProbedId = channel.id
                        if (status == ChannelHealthStatus.UNCLEAR && retryIds.size < MAX_RETRY_CHANNELS) {
                            retryIds += channel.id
                            if (result.serverError) serverErrorIds += channel.id
                        }
                    }
                    if (retrying && status == ChannelHealthStatus.UNCLEAR && result.serverError &&
                        channel.id in serverErrorIds
                    ) {
                        refusedIds += channel.id
                    }
                    scanned++
                    setState(providerId) { it.copy(scanned = scanned) }
                    if (status == ChannelHealthStatus.OK) {
                        // A good answer shows the failures before it were the channels' own, so keep them.
                        pending += settled(streak)
                        pending += outcome
                        streak.clear()
                        unclearInARow = 0
                        backoffsInARow = 0
                    } else {
                        streak += outcome
                        if (status == ChannelHealthStatus.UNCLEAR) unclearInARow++ else unclearInARow = 0
                    }
                    if (pending.size >= timings.flushEvery) flush()
                    if (streak.size >= timings.massFailureStreak) {
                        // Everything failing at once points at the provider or the account, not the channels.
                        streak.clear()
                        if (++backoffsInARow >= timings.maxBackoffsInARow) {
                            gaveUp = true
                            return false
                        }
                        backOff(providerId)
                        unclearInARow = 0
                        account = readAccount(providerId, config, previous = account)
                    } else if (unclearInARow >= timings.unclearInARowBeforeBackoff) {
                        flush()
                        if (++backoffsInARow >= timings.maxBackoffsInARow) {
                            gaveUp = true
                            return false
                        }
                        backOff(providerId)
                        unclearInARow = 0
                        account = readAccount(providerId, config, previous = account)
                    } else if (scanned < total) {
                        delay(timings.pauseBetweenChannelsMs)
                    }
                }
                if (retrying || retryIds.isEmpty()) break
                // Main pass over: settle what it held back, give the provider one backoff, then ask the
                // UNCLEAR channels once more.
                pending += settled(streak)
                streak.clear()
                flush()
                backOff(providerId)
                account = readAccount(providerId, config, previous = account)
                retrying = true
                retryAfterId = 0L
                skipThrough = null
                unclearInARow = 0
                backoffsInARow = 0
                queue = channels.filter { it.id in retryIds }
                total = channels.size + queue.size
                setState(providerId) { it.copy(total = total) }
            }
            finished = true
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            withContext(NonCancellable) {
                // A scan that gave up saw a provider in trouble, so its 5xx answers say nothing about the channels.
                pending += if (gaveUp) streak.filterNot { it.channelId in refusedIds } else settled(streak)
                streak.clear()
                flush()
                if (finished) results.markFinished(providerId)
                setState(providerId) { it.copy(running = false, paused = null, gaveUp = gaveUp) }
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
    ): AccountLimits? {
        var account = current
        var poll = timings.playbackPollMs
        var waited = 0L
        while (true) {
            val localPlayback = account.maxConnections <= 1 && AutomaticSyncActivity.hasPlayback
            if (!localPlayback && !account.saturated) {
                setState(providerId) { if (it.paused == null) it else it.copy(paused = null) }
                return account
            }
            // Give up on a connection that never frees up instead of polling the provider for ever.
            if (waited >= timings.maxPauseMs) return null
            setState(providerId) { it.copy(paused = ChannelHealthPauseReason.PLAYBACK_ACTIVE) }
            delay(poll)
            waited += poll
            poll = minOf(poll * 2, timings.maxPlaybackPollMs)
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
                allowsHls = info.allowedOutputFormats.isEmpty() ||
                    info.allowedOutputFormats.any { it.trim().equals("m3u8", ignoreCase = true) },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Class name only: the message of a failed request can include the login url.
            Log.w(TAG, "Account info unavailable (${error.javaClass.simpleName})")
            previous ?: AccountLimits(maxConnections = 1, activeConnections = 0)
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

        /** Most UNCLEAR channels remembered for the retry pass, to keep the stored document small. */
        const val MAX_RETRY_CHANNELS = 1_000
    }
}
