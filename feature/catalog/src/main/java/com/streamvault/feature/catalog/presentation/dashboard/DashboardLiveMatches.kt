package com.streamvault.feature.catalog.presentation.dashboard

import com.streamvault.feature.catalog.api.CatalogLiveMatch
import com.streamvault.feature.catalog.api.CatalogLiveMatchesPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

internal const val LIVE_MATCHES_REFRESH_MS = 60_000L

/** Lets the first dashboard frame and its own shelves load before the guide is touched. */
internal const val LIVE_MATCHES_FIRST_LOAD_DELAY_MS = 1_500L

/**
 * Polls [port] every [refreshMs] for the current live-source providers. Collected through
 * `WhileSubscribed`, so the polling only runs while the dashboard is on screen. Emits only
 * when the list actually changes; a failed read keeps the previous row.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun liveMatchesTicker(
    providerIds: Flow<List<Long>>,
    port: CatalogLiveMatchesPort,
    nowMs: () -> Long,
    refreshMs: Long = LIVE_MATCHES_REFRESH_MS,
    firstLoadDelayMs: Long = LIVE_MATCHES_FIRST_LOAD_DELAY_MS,
): Flow<List<CatalogLiveMatch>> = providerIds
    .distinctUntilChanged()
    .flatMapLatest { ids ->
        if (ids.isEmpty()) {
            flowOf(emptyList())
        } else {
            flow {
                if (firstLoadDelayMs > 0) delay(firstLoadDelayMs)
                var last: List<CatalogLiveMatch>? = null
                while (true) {
                    val matches = try {
                        port.liveMatches(ids, nowMs())
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        last ?: emptyList()
                    }
                    if (matches !== last) {
                        last = matches
                        emit(matches)
                    }
                    delay(refreshMs)
                }
            }
        }
    }
    .distinctUntilChanged()

internal sealed interface LiveMatchStatus {
    data object Live : LiveMatchStatus
    data class StartsIn(val minutes: Int) : LiveMatchStatus
}

/** "LIVE" once kicked off, otherwise whole minutes to kick-off rounded up (never "in 0 min"). */
internal fun liveMatchStatus(match: CatalogLiveMatch, nowMs: Long): LiveMatchStatus {
    if (match.startTimeMs <= nowMs) return LiveMatchStatus.Live
    val minutes = ((match.startTimeMs - nowMs + 59_999L) / 60_000L).toInt()
    return LiveMatchStatus.StartsIn(minutes.coerceAtLeast(1))
}
