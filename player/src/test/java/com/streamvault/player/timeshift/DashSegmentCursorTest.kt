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

    /** SegmentTimeline refs whose `$Time$` values are in [timescale] ticks, 2 s apart. */
    private fun timeline(timescale: Long, firstSecond: Long, lastSecond: Long) =
        (firstSecond..lastSecond step 2L).map { second ->
            val t = second * timescale
            DashMediaSegmentRef(uri = "seg-$t.m4s", durationMs = 2_000L, position = t, startMs = second * 1_000L)
        }

    @Test
    fun `a timescale change between polls keeps capturing new segments`() {
        val cursor = DashSegmentCursor()
        assertThat(cursor.poll(timeline(timescale = 90_000L, firstSecond = 100L, lastSecond = 120L))).hasSize(11)

        // The same stream now advertises $Time$ in milliseconds: raw values drop from about 10.8 million
        // to about 120 thousand while the media time keeps going. Only the two new segments are taken.
        assertThat(cursor.poll(timeline(timescale = 1_000L, firstSecond = 104L, lastSecond = 124L)))
            .containsExactly(122_000L, 124_000L)
            .inOrder()
        assertThat(cursor.poll(timeline(timescale = 1_000L, firstSecond = 108L, lastSecond = 128L)))
            .containsExactly(126_000L, 128_000L)
            .inOrder()
    }

    @Test
    fun `the same segment rounded to a different millisecond start is not taken twice`() {
        val cursor = DashSegmentCursor()
        cursor.poll(listOf(DashMediaSegmentRef("a.m4s", durationMs = 2_000L, position = 9_000_045L, startMs = 100_000L)))

        assertThat(
            cursor.poll(listOf(DashMediaSegmentRef("b.m4s", durationMs = 2_000L, position = 100_001L, startMs = 100_001L)))
        ).isEmpty()
    }

    @Test
    fun `an empty manifest changes nothing`() {
        val cursor = DashSegmentCursor()
        cursor.poll(segments(1L..5L))
        cursor.poll(emptyList())

        assertThat(cursor.poll(segments(4L..6L))).containsExactly(6L)
    }
}
