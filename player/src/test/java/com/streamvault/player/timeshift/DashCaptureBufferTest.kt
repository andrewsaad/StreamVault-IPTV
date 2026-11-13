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

    @Test
    fun `a failed media download is retried on the next poll`() = runTest {
        val harness = Harness()
        harness.poll(1L..3L)

        val failure = runCatching { harness.poll(2L..5L, failing = setOf("seg-4.m4s")) }
        assertThat(failure.isFailure).isTrue()

        harness.poll(3L..6L)

        assertThat(harness.buffered())
            .containsExactlyElementsIn(listOf("init.mp4") + (1L..6L).map { "seg-$it.m4s" })
            .inOrder()
        assertThat(harness.fetched.count { it == "seg-4.m4s" }).isEqualTo(2)
    }

    @Test
    fun `a failed init download is retried before any media is captured`() = runTest {
        val harness = Harness()

        val failure = runCatching { harness.poll(1L..3L, failing = setOf("init.mp4")) }
        assertThat(failure.isFailure).isTrue()
        assertThat(harness.buffered()).isEmpty()

        harness.poll(1L..3L)

        assertThat(harness.buffered())
            .containsExactlyElementsIn(listOf("init.mp4") + (1L..3L).map { "seg-$it.m4s" })
            .inOrder()
    }

    @Test
    fun `media after a switch back to an earlier init URL plays against that init again`() = runTest {
        val harness = Harness()
        harness.poll(1L..2L, initUrl = "init-a.mp4")
        harness.poll(3L..4L, initUrl = "init-b.mp4")
        harness.poll(5L..6L, initUrl = "init-a.mp4")

        assertThat(harness.buffered()).containsExactly(
            "init-a.mp4", "seg-1.m4s", "seg-2.m4s",
            "init-b.mp4", "seg-3.m4s", "seg-4.m4s",
            "init-a.mp4", "seg-5.m4s", "seg-6.m4s"
        ).inOrder()
    }

    @Test
    fun `an init whose media were all pruned is dropped`() = runTest {
        // 10 s window: five 2 s segments.
        val harness = Harness(maxDurationMs = 10_000L)
        harness.poll(1L..3L, initUrl = "init-a.mp4")
        harness.poll(4L..10L, initUrl = "init-b.mp4")

        assertThat(harness.buffered())
            .containsExactlyElementsIn(listOf("init-b.mp4") + (6L..10L).map { "seg-$it.m4s" })
            .inOrder()
        assertThat(harness.removed).contains("init-a.mp4")
    }

    @Test
    fun `an init replaced before any media followed it is dropped`() = runTest {
        val harness = Harness()
        harness.poll(LongRange.EMPTY, initUrl = "init-a.mp4")
        harness.poll(1L..2L, initUrl = "init-b.mp4")

        assertThat(harness.buffered()).containsExactly("init-b.mp4", "seg-1.m4s", "seg-2.m4s").inOrder()
        assertThat(harness.removed).containsExactly("init-a.mp4")
    }
}
