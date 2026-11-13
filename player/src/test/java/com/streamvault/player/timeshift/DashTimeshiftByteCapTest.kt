package com.streamvault.player.timeshift

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DashTimeshiftByteCapTest {
    private class Segment(val uri: String, val durationMs: Long, val bytes: Long)

    private val cap = TimeshiftConfig.MAX_MEMORY_BACKEND_BYTES
    private val depthMs = TimeshiftConfig().effectiveDepthMs(LiveTimeshiftBackend.MEMORY)

    private fun init(bytes: Long = 4_096L) = Segment("init.mp4", durationMs = 0L, bytes = bytes)

    private fun media(count: Int, bytes: Long, durationMs: Long = 2_000L) =
        (0 until count).map { Segment("seg-$it.m4s", durationMs = durationMs, bytes = bytes) }

    /** Same call the DASH session makes: init segments (no duration) are never pruned. */
    private fun prune(segments: ArrayDeque<Segment>, maxBytes: Long?): List<Segment> {
        val removed = mutableListOf<Segment>()
        pruneOldestToLimits(
            items = segments,
            runningDurationMs = segments.sumOf { it.durationMs },
            maxDurationMs = depthMs,
            maxBytes = maxBytes,
            durationOf = { it.durationMs },
            bytesOf = { it.bytes },
            canPrune = { isDashMediaSegment(it.durationMs) },
            onRemoved = { removed += it }
        )
        return removed
    }

    @Test
    fun `high bitrate DASH input is trimmed to the cap dropping the oldest media first and keeping init`() {
        // 5 minutes of 2 MB, 2 s segments = about 300 MB, within the time cap but far over the byte cap.
        val initSegment = init()
        val mediaSegments = media(count = 150, bytes = 2L * 1024 * 1024)
        val segments = ArrayDeque(listOf(initSegment) + mediaSegments)

        val removed = prune(segments, cap)

        assertThat(segments.first()).isSameInstanceAs(initSegment)
        assertThat(segments.last()).isSameInstanceAs(mediaSegments.last())
        assertThat(segments.sumOf { it.bytes }).isAtMost(cap)
        assertThat(removed).doesNotContain(initSegment)
        assertThat(removed).containsExactlyElementsIn(mediaSegments.take(removed.size)).inOrder()
        assertThat(segments.size + removed.size).isEqualTo(151)
    }

    @Test
    fun `DASH time cap prunes past the init segment at the head`() {
        // Before the fix the init segment at the head stopped all pruning, so DASH grew without bound.
        val initSegment = init()
        val segments = ArrayDeque(listOf(initSegment) + media(count = 200, bytes = 1_000L))

        prune(segments, maxBytes = null)

        assertThat(segments.first()).isSameInstanceAs(initSegment)
        assertThat(segments.sumOf { it.durationMs }).isAtMost(depthMs)
        assertThat(segments.sumOf { it.durationMs }).isGreaterThan(depthMs - 2_000L)
    }

    @Test
    fun `low bitrate DASH input within both caps is unchanged`() {
        val segments = ArrayDeque(listOf(init()) + media(count = 150, bytes = 500_000L))

        val removed = prune(segments, cap)

        assertThat(removed).isEmpty()
        assertThat(segments).hasSize(151)
    }

    @Test
    fun `an init segment alone over the cap is kept and does not loop`() {
        val initSegment = init(bytes = cap + 1L)
        val segments = ArrayDeque(listOf(initSegment) + media(count = 3, bytes = 1_000L))

        val removed = prune(segments, cap)

        assertThat(segments).containsExactly(initSegment)
        assertThat(removed).hasSize(3)
    }

    @Test
    fun `pruned segments stay seen while the manifest still lists them`() {
        val seen = linkedSetOf("${DASH_INIT_SEEN_KEY_PREFIX}init.mp4", "seg-1.m4s", "seg-2.m4s", "seg-3.m4s")

        retainAdvertisedDashSegments(seen, advertisedUris = listOf("seg-2.m4s", "seg-3.m4s", "seg-4.m4s"))

        // seg-2 may already be pruned from the buffer, but it is still listed: it must not be fetched again.
        assertThat(seen).containsExactly("${DASH_INIT_SEEN_KEY_PREFIX}init.mp4", "seg-2.m4s", "seg-3.m4s")
    }

    @Test
    fun `an empty manifest segment list leaves the seen set alone`() {
        val seen = linkedSetOf("${DASH_INIT_SEEN_KEY_PREFIX}init.mp4", "seg-1.m4s")

        retainAdvertisedDashSegments(seen, advertisedUris = emptyList())

        assertThat(seen).containsExactly("${DASH_INIT_SEEN_KEY_PREFIX}init.mp4", "seg-1.m4s")
    }
}
