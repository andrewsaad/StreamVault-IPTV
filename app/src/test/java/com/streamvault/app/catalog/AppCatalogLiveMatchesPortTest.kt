package com.streamvault.app.catalog

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Channel
import com.streamvault.feature.live.sports.HomeLiveMatch
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AppCatalogLiveMatchesPortTest {

    @Test
    fun `maps every field and reuses the mapped list while the source list is unchanged`() = runTest {
        val channel = Channel(id = 4L, name = "beIN Sport 1 FHD Q", providerId = 2L)
        val unchanged = listOf(
            HomeLiveMatch(channel, "Liverpool vs Arsenal", "Premier League", 10L, 20L, "عصام الشوالي")
        )
        var current = unchanged
        val requests = mutableListOf<Pair<List<Long>, Long>>()
        val port = AppCatalogLiveMatchesPort { providerIds, nowMs ->
            requests += providerIds to nowMs
            current
        }

        val first = port.liveMatches(listOf(2L), 100L)
        val second = port.liveMatches(listOf(2L), 160L)
        current = emptyList()
        val third = port.liveMatches(listOf(2L), 220L)

        assertThat(first.single().channel).isEqualTo(channel)
        assertThat(first.single().title).isEqualTo("Liverpool vs Arsenal")
        assertThat(first.single().league).isEqualTo("Premier League")
        assertThat(first.single().startTimeMs).isEqualTo(10L)
        assertThat(first.single().endTimeMs).isEqualTo(20L)
        assertThat(first.single().commentator).isEqualTo("عصام الشوالي")
        assertThat(second).isSameInstanceAs(first)
        assertThat(third).isEmpty()
        assertThat(requests).containsExactly(listOf(2L) to 100L, listOf(2L) to 160L, listOf(2L) to 220L).inOrder()
    }
}
