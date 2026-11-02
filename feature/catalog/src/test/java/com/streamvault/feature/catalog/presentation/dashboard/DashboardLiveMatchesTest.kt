package com.streamvault.feature.catalog.presentation.dashboard

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Channel
import com.streamvault.feature.catalog.api.CatalogLiveMatch
import com.streamvault.feature.catalog.api.CatalogLiveMatchesPort
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardLiveMatchesTest {
    private val minute = 60_000L

    @Test
    fun `status is LIVE from kick-off and rounds upcoming minutes up`() {
        val now = 1_000_000_000L
        assertThat(liveMatchStatus(match(start = now), now)).isEqualTo(LiveMatchStatus.Live)
        assertThat(liveMatchStatus(match(start = now - minute), now)).isEqualTo(LiveMatchStatus.Live)
        assertThat(liveMatchStatus(match(start = now + 1), now)).isEqualTo(LiveMatchStatus.StartsIn(1))
        assertThat(liveMatchStatus(match(start = now + 10 * minute), now)).isEqualTo(LiveMatchStatus.StartsIn(10))
        assertThat(liveMatchStatus(match(start = now + 29 * minute + 1), now)).isEqualTo(LiveMatchStatus.StartsIn(30))
    }

    @Test
    fun `ticker waits before the first read, polls every minute and skips unchanged lists`() = runTest {
        val calls = mutableListOf<Pair<List<Long>, Long>>()
        val first = listOf(match(start = 0L))
        val second = listOf(match(start = 5L))
        val responses = ArrayDeque(listOf(first, first, second))
        val port = object : CatalogLiveMatchesPort {
            override suspend fun liveMatches(providerIds: List<Long>, nowMs: Long): List<CatalogLiveMatch> {
                calls += providerIds to nowMs
                return responses.removeFirstOrNull() ?: second
            }
        }
        val providers = MutableStateFlow(listOf(3L))
        val emissions = mutableListOf<List<CatalogLiveMatch>>()
        val job = launch {
            liveMatchesTicker(providers, port, nowMs = { testScheduler.currentTime }).collect { emissions += it }
        }

        runCurrent()
        assertThat(calls).isEmpty()
        assertThat(emissions).isEmpty()

        advanceTimeBy(LIVE_MATCHES_FIRST_LOAD_DELAY_MS + 1)
        assertThat(calls).hasSize(1)
        assertThat(emissions).containsExactly(first)

        advanceTimeBy(LIVE_MATCHES_REFRESH_MS)
        assertThat(calls).hasSize(2)
        assertThat(emissions).containsExactly(first)

        advanceTimeBy(LIVE_MATCHES_REFRESH_MS)
        assertThat(calls).hasSize(3)
        assertThat(emissions).containsExactly(first, second).inOrder()
        assertThat(calls.map { it.first }.distinct()).containsExactly(listOf(3L))

        providers.value = emptyList()
        runCurrent()
        assertThat(emissions.last()).isEmpty()
        val callsAfterClear = calls.size
        advanceTimeBy(5 * LIVE_MATCHES_REFRESH_MS)
        assertThat(calls).hasSize(callsAfterClear)
        job.cancel()
    }

    @Test
    fun `a failing port keeps the last row`() = runTest {
        val good = listOf(match(start = 0L))
        var fail = false
        val port = object : CatalogLiveMatchesPort {
            override suspend fun liveMatches(providerIds: List<Long>, nowMs: Long): List<CatalogLiveMatch> {
                if (fail) error("guide unavailable")
                return good
            }
        }
        val emissions = mutableListOf<List<CatalogLiveMatch>>()
        val job = launch {
            liveMatchesTicker(MutableStateFlow(listOf(1L)), port, nowMs = { 0L }, firstLoadDelayMs = 0L)
                .collect { emissions += it }
        }

        runCurrent()
        fail = true
        advanceTimeBy(LIVE_MATCHES_REFRESH_MS + 1)

        assertThat(emissions).containsExactly(good)
        job.cancel()
    }

    private fun match(start: Long) = CatalogLiveMatch(
        channel = Channel(id = 1L, name = "beIN Sport 1 FHD Q"),
        title = "Liverpool vs Arsenal",
        league = "Premier League",
        startTimeMs = start,
        endTimeMs = start + 110 * minute,
        commentator = null,
    )
}
