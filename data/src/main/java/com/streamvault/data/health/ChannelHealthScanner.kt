package com.streamvault.data.health

import android.util.Log
import com.streamvault.data.local.dao.ChannelDao
import com.streamvault.data.remote.http.USER_AGENT_HEADER
import com.streamvault.data.remote.http.buildGenericProviderRequestProfile
import com.streamvault.data.remote.xtream.XtreamApiService
import com.streamvault.data.remote.xtream.XtreamStreamKind
import com.streamvault.data.remote.xtream.XtreamUrlFactory
import com.streamvault.domain.model.ChannelHealthPauseReason
import com.streamvault.domain.model.ChannelHealthScanScope
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

/** Fewest OK answers in the retry pass that show the provider was serving while it refused other channels. */
private const val MIN_RETRY_PASS_OK = 3

/** OK answers in the main pass that make a healthy provider certain enough for a small retry queue. */
private const val MIN_MAIN_PASS_OK = 50

/**
 * Whether channels the provider refused with a 5xx on both passes count as EMPTY. A 5xx says nothing about the
 * channel while the provider itself is struggling, so it needs proof of a healthy provider: either the retry
 * pass alone ([retryOk] at least 3 and fewer than half of its [retryAttempts] refused), or a main pass with at
 * least 50 OK answers plus one OK answer in the retry pass. [refused] is the number of repeated 5xx answers.
 */
internal fun shouldStoreRefusals(mainOk: Int, retryOk: Int, retryAttempts: Int, refused: Int): Boolean {
    if (refused <= 0) return false
    val retryPassHealthy = retryOk >= MIN_RETRY_PASS_OK && refused.toLong() * 2 < retryAttempts
    val mainPassHealthy = mainOk >= MIN_MAIN_PASS_OK && retryOk >= 1
    return retryPassHealthy || mainPassHealthy
}

/**
 * Whether a scan of [scope] probes the channel [channelId]. New channels are the ones with no settled answer
 * yet (including those that only ever answered UNCLEAR); bad ones are the channels with a failure record,
 * including failures imported as hints.
 */
internal fun selectsChannel(scope: ChannelHealthScanScope, channelId: Long, document: ChannelHealthDocument): Boolean =
    when (scope) {
        ChannelHealthScanScope.ALL -> true
        ChannelHealthScanScope.NEW_ONLY -> channelId !in document.knownIds
        ChannelHealthScanScope.BAD_ONLY -> channelId in document.records
    }

/**
 * One log line for a scan event. Only the event name, the scope and counts go in: never a server address,
 * login or stream URL, so the lines are safe to keep and share.
 */
internal fun formatScanLogLine(
    event: String,
    scope: ChannelHealthScanScope,
    scanned: Int,
    total: Int,
    elapsedMs: Long,
    ok: Int? = null,
): String = buildString {
    append("scan ").append(event)
    append(" scope=").append(scope.name)
    append(" scanned=").append(scanned).append('/').append(total)
    if (ok != null) append(" ok=").append(ok)
    append(" elapsedSec=").append(elapsedMs / 1_000L)
}

/** The event a scan ends with in the log: an unexpected failure is "error", never mistaken for a user cancel. */
internal fun scanOutcomeEvent(finished: Boolean, gaveUp: Boolean, cancelled: Boolean): String = when {
    finished -> "finish"
    gaveUp -> "give-up"
    cancelled -> "cancel"
    else -> "error"
}

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
     * Channels the provider refused with a 5xx on both passes are only stored as EMPTY when
     * [shouldStoreRefusals] finds the provider healthy; otherwise they stay unchecked.
     * Returns false when the scan could not run or gave up; the reason is never the login. A scan that
     * gave up is flagged in its state ([ChannelHealthScanState.gaveUp]) so the user can be told.
     */
    internal suspend fun run(
        providerId: Long,
        categoryIds: Set<Long>?,
        startedAt: Long,
        scope: ChannelHealthScanScope = ChannelHealthScanScope.ALL,
    ): Boolean {
        val config = snapshots.getSnapshot(providerId)?.configuration as? XtreamConfig
        if (config == null) {
            markStopped(providerId)
            return false
        }
        val document = results.read(providerId)
        val channels = channelDao.getByProviderSync(providerId)
            .filter { it.streamId > 0L && (categoryIds == null || it.categoryId in categoryIds) }
            .filter { selectsChannel(scope, it.id, document) }
            .sortedBy { it.id }
        val resume = document.resume?.takeIf { it.startedAt == startedAt }
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
        // Retried channels the provider refused with a 5xx again, by channel id. They are held back until the
        // retry pass is over and only count as EMPTY if [shouldStoreRefusals] says the provider was healthy.
        val refused = LinkedHashMap<Long, ChannelProbeOutcome>()
        // OK answers in the main pass, and OK answers, attempts and repeated 5xx answers in the retry pass.
        // They only cover the part of the scan this run saw, so a restarted scan judges more strictly.
        var mainOk = 0
        var retryOk = resume?.retryOk ?: 0
        var retryAttempts = resume?.retryAttempts ?: 0
        // Refusals saved by the run that was stopped; the ones still in the mass-failure streak count toward it again.
        resume?.refused?.forEach { saved ->
            val outcome = ChannelProbeOutcome(saved.channelId, ChannelHealthStatus.UNCLEAR, saved.checkedAt)
            refused[saved.channelId] = outcome
            if (saved.inStreak) streak += outcome
        }
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
        // Channels up to this id are settled. A held-back streak of undecided answers is not, so it is probed again
        // after a restart; that costs at most massFailureStreak - 1 channels per restart, so progress still advances.
        // Refused channels are NOT held back: they are saved with the resume point (see [ChannelRefusal]), because
        // holding the point back for them made every restart re-probe the same stretch and the scan never finished.
        fun resumePoint(): ChannelHealthResume {
            val heldBack = streak.firstOrNull { it.channelId !in refused }?.let { it.channelId - 1 }
            val retryAfter = retryAfterId
            val point = if (retryAfter != null) heldBack ?: retryAfter else heldBack ?: lastProbedId
            val streakIds = streak.mapTo(HashSet()) { it.channelId }
            // A refusal past the point is probed (and counted) again, so it is not saved.
            val savedRefusals = refused.values.filter { it.channelId <= point }
                .map { ChannelRefusal(it.channelId, it.checkedAt, inStreak = it.channelId in streakIds) }
            if (retryAfter != null) {
                return ChannelHealthResume(
                    startedAt,
                    lastProbedId,
                    retryIds.toList(),
                    point,
                    serverErrorIds.toList(),
                    savedRefusals,
                    retryOk,
                    retryAttempts - streak.count { it.channelId > point },
                )
            }
            return ChannelHealthResume(
                startedAt,
                point,
                retryIds.filter { it <= point },
                serverErrorIds = serverErrorIds.filter { it <= point },
                refused = savedRefusals,
            )
        }
        // Refused channels are decided once, at the end of the retry pass; every other outcome is kept as it is.
        fun settled(outcomes: List<ChannelProbeOutcome>) = outcomes.filterNot { it.channelId in refused }
        var saved = resume
        suspend fun flush() {
            val point = resumePoint()
            if (pending.isEmpty() && point == saved) return
            results.record(providerId, pending.toList(), point)
            pending.clear()
            saved = point
        }
        setState(providerId) { ChannelHealthScanState(running = true, total = total, scanned = scanned) }
        val beganAt = results.now()
        // The ok count only covers what this run saw, so the start line leaves it out.
        fun logScan(event: String, ok: Int? = null) =
            Log.i(TAG, formatScanLogLine(event, scope, scanned, total, results.now() - beganAt, ok))
        logScan(if (resume != null) "resume" else "start")
        var cancelled = false
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
                    if (retrying) {
                        retryAttempts++
                        if (status == ChannelHealthStatus.OK) {
                            retryOk++
                        } else if (status == ChannelHealthStatus.UNCLEAR && result.serverError &&
                            channel.id in serverErrorIds
                        ) {
                            refused[channel.id] = outcome
                        }
                    } else if (status == ChannelHealthStatus.OK) {
                        mainOk++
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
                        streak.forEach { refused.remove(it.channelId) }
                        // The storm's attempts are not evidence about the provider's health either way.
                        if (retrying) retryAttempts -= streak.size
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
            if (shouldStoreRefusals(mainOk, retryOk, retryAttempts, refused.size)) {
                pending += refused.values.map { it.copy(status = ChannelHealthStatus.EMPTY) }
            }
            finished = true
            return true
        } catch (stopped: CancellationException) {
            cancelled = true
            throw stopped
        } finally {
            logScan(scanOutcomeEvent(finished, gaveUp, cancelled), mainOk + retryOk)
            withContext(NonCancellable) {
                // A scan that gave up saw a provider in trouble, so its 5xx answers are never stored.
                // Refused channels stay in the streak so the saved resume state still marks them (see [ChannelRefusal]).
                pending += settled(streak)
                streak.removeAll { it.channelId !in refused }
                flush()
                if (finished) results.markFinished(providerId)
                setState(providerId) { it.copy(running = false, paused = null, gaveUp = gaveUp) }
            }
        }
    }

    private suspend fun backOff(providerId: Long) {
        Log.i(TAG, "scan backoff waitSec=${timings.backoffMs / 1_000L}")
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
