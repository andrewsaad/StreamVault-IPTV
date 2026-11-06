package com.streamvault.feature.live.presentation.epg

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Program
import org.junit.Test

class LiveGuideGridRowsTest {
    @Test
    fun `current program selects the program containing now`() {
        val programs = listOf(
            Program(channelId = "news", title = "Earlier", startTime = 0L, endTime = 100L),
            Program(channelId = "news", title = "Current", startTime = 100L, endTime = 200L),
            Program(channelId = "news", title = "Later", startTime = 200L, endTime = 300L)
        )

        assertThat(programs.liveCurrentProgramAt(150L)?.title).isEqualTo("Current")
    }

    @Test
    fun `current program excludes the end boundary`() {
        val program = Program(channelId = "news", title = "Current", startTime = 100L, endTime = 200L)

        assertThat(listOf(program).liveCurrentProgramAt(200L)).isNull()
    }

    private fun hourly(count: Int) = (0 until count).map {
        Program(channelId = "c", title = "P$it", startTime = it * 3_600_000L, endTime = (it + 1) * 3_600_000L)
    }

    @Test
    fun `visible slice keeps programs near viewport and drops far ones`() {
        // 24h window, 3h viewport => 1000px viewport, 8000px total
        val programs = hourly(24)
        val slice = programs.liveGuideProgramsForBucket(
            bucket = 0, viewportPx = 1000f, totalTimelinePx = 8000f,
            windowStart = 0L, windowEnd = 24 * 3_600_000L
        )
        assertThat(slice.first().title).isEqualTo("P0")
        assertThat(slice.map { it.title }).contains("P5")
        assertThat(slice.map { it.title }).doesNotContain("P12")
    }

    @Test
    fun `visible slice always includes a long program spanning the viewport`() {
        val long = Program(channelId = "c", title = "Long", startTime = 0L, endTime = 24 * 3_600_000L)
        val slice = listOf(long).liveGuideProgramsForBucket(
            bucket = 6, viewportPx = 1000f, totalTimelinePx = 8000f,
            windowStart = 0L, windowEnd = 24 * 3_600_000L
        )
        assertThat(slice).containsExactly(long)
    }

    @Test
    fun `scroll bucket advances every half viewport`() {
        assertThat(liveGuideScrollBucket(0, 1000f)).isEqualTo(0)
        assertThat(liveGuideScrollBucket(499, 1000f)).isEqualTo(0)
        assertThat(liveGuideScrollBucket(500, 1000f)).isEqualTo(1)
        assertThat(liveGuideScrollBucket(-5, 1000f)).isEqualTo(0)
    }
}
