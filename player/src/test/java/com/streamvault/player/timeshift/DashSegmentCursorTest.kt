package com.streamvault.player.timeshift

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DashSegmentCursorTest {
    private fun segments(range: LongRange) = range.map { number ->
        DashMediaSegmentRef(uri = "seg-$number.m4s", durationMs = 2_000L, position = number, startMs = number * 2_000L)
    }

    private fun DashSegmentCursor.poll(advertised: List<DashMediaSegmentRef>): List<Long> {
        onManifest(advertised)
        return advertised.filter { take(it) }.map { it.position }
    }

    @Test
    fun `a stale manifest listing older segments takes nothing`() {
        val cursor = DashSegmentCursor()
        assertThat(cursor.poll(segments(10L..20L))).isEqualTo((10L..20L).toList())

        // A lagging copy of the manifest (8 to 18) arrives after the fresh one (10 to 20).
        assertThat(cursor.poll(segments(8L..18L))).isEmpty()

        // The next fresh manifest only adds what is newer than 20: nothing is fetched twice or out of order.
        assertThat(cursor.poll(segments(12L..22L))).containsExactly(21L, 22L).inOrder()
    }

    @Test
    fun `segments repeated in the next manifest are not taken again`() {
        val cursor = DashSegmentCursor()
        cursor.poll(segments(1L..5L))

        assertThat(cursor.poll(segments(3L..7L))).containsExactly(6L, 7L).inOrder()
    }

    @Test
    fun `a restart far behind the mark is captured again`() {
        val cursor = DashSegmentCursor()
        cursor.poll(segments(1_000L..1_010L))

        // Positions jumped back by about 2,000 s: the encoder restarted or a new period began.
        assertThat(cursor.poll(segments(0L..3L))).containsExactly(0L, 1L, 2L, 3L).inOrder()
    }

    @Test
    fun `an empty manifest changes nothing`() {
        val cursor = DashSegmentCursor()
        cursor.poll(segments(1L..5L))
        cursor.poll(emptyList())

        assertThat(cursor.poll(segments(4L..6L))).containsExactly(6L)
    }
}
