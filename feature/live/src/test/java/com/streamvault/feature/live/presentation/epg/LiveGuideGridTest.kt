package com.streamvault.feature.live.presentation.epg

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Channel
import org.junit.Test

class LiveGuideGridTest {
    @Test
    fun `initial focus index resolves a requested channel`() {
        val channels = listOf(
            Channel(id = 10L, name = "News"),
            Channel(id = 20L, name = "Sports")
        )

        assertThat(liveGuideInitialFocusIndex(channels, 20L)).isEqualTo(1)
    }

    @Test
    fun `initial focus index falls back to the first channel`() {
        val channels = listOf(Channel(id = 10L, name = "News"))

        assertThat(liveGuideInitialFocusIndex(channels, 99L)).isEqualTo(0)
        assertThat(liveGuideInitialFocusIndex(channels, null)).isEqualTo(0)
    }

    @Test
    fun `paging in more channels does not change the initial scroll key`() {
        val firstPage = (1L..50L).map { Channel(id = it, name = "C$it") }
        val twoPages = (1L..100L).map { Channel(id = it, name = "C$it") }

        assertThat(liveGuideInitialScrollKey(twoPages, 1L)).isEqualTo(liveGuideInitialScrollKey(firstPage, 1L))
    }

    @Test
    fun `the initial scroll key changes when the list first appears or the target changes`() {
        val channels = listOf(Channel(id = 10L, name = "News"))

        assertThat(liveGuideInitialScrollKey(channels, 10L)).isNotEqualTo(liveGuideInitialScrollKey(emptyList(), 10L))
        assertThat(liveGuideInitialScrollKey(channels, 10L)).isNotEqualTo(liveGuideInitialScrollKey(channels, 20L))
    }
}
