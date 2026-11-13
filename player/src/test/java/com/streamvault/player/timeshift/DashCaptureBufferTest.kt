package com.streamvault.player.timeshift

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Restart, failure and init handling in [DashCaptureBuffer], the bookkeeping the DASH session runs. */
class DashCaptureBufferTest {
    private class Segment(val uri: String, val durationMs: Long)

    private class Harness(maxDurationMs: Long = 30L * 60_000L) {
        val removed = mutableListOf<String>()
        val fetched = mutableListOf<String>()
        val windows = mutableListOf<Long>()
        val buffer = DashCaptureBuffer<Segment>(
            maxDurationMs = maxDurationMs,
            maxBytes = null,
            durationOf = { it.durationMs },
            bytesOf = { 1L },
            onRemoved = { removed += it.uri }
        )

        /** One poll of 2 s segments numbered [numbers]; segment n starts at n * 2 s. */
        suspend fun poll(numbers: LongRange, initUrl: String? = "init.mp4", failing: Set<String> = emptySet()) {
            buffer.ingest(
                initUrl = initUrl,
                advertised = numbers.map { n ->
                    DashMediaSegmentRef(uri = "seg-$n.m4s", durationMs = 2_000L, position = n, startMs = n * 2_000L)
                },
                fetch = { uri, durationMs, _ ->
                    fetched += uri
                    if (uri in failing) throw java.io.IOException("HTTP 503")
                    Segment(uri, durationMs)
                },
                onWindow = { windows += it }
            )
        }

        suspend fun buffered() = buffer.snapshot().map { it.uri }
    }

    @Test
    fun `a restart drops the old timeline instead of appending the new one after it`() = runTest {
        val harness = Harness()
        harness.poll(1_000L..1_010L)

        // The stream restarted: positions jumped back about 2,000 s.
        harness.poll(0L..3L)

        assertThat(harness.buffered())
            .containsExactlyElementsIn(listOf("init.mp4") + (0L..3L).map { "seg-$it.m4s" })
            .inOrder()
        assertThat(harness.removed)
            .containsExactlyElementsIn(listOf("init.mp4") + (1_000L..1_010L).map { "seg-$it.m4s" })
        // The init is fetched again for the new timeline, and the window restarts from the new segments.
        assertThat(harness.fetched.count { it == "init.mp4" }).isEqualTo(2)
        assertThat(harness.windows.last()).isEqualTo(8_000L)
    }
}
