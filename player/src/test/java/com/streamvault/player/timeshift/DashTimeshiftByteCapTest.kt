package com.streamvault.player.timeshift

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Drives [DashCaptureBuffer], the bookkeeping the DASH session runs on every manifest poll. */
class DashTimeshiftByteCapTest {
    private class Segment(val uri: String, val durationMs: Long, val bytes: Long)

    private val cap = TimeshiftConfig.MAX_MEMORY_BACKEND_BYTES
    private val config = TimeshiftConfig()
    private val depthMs = config.effectiveDepthMs(LiveTimeshiftBackend.MEMORY)

    private class Harness(maxBytes: Long?, maxDurationMs: Long, private val bytesPerSegment: Long, private val initBytes: Long) {
        val removed = mutableListOf<Segment>()
        val fetched = mutableListOf<String>()
        val windows = mutableListOf<Long>()
        val buffer = DashCaptureBuffer<Segment>(
            maxDurationMs = maxDurationMs,
            maxBytes = maxBytes,
            durationOf = { it.durationMs },
            bytesOf = { it.bytes },
            onRemoved = { removed += it }
        )

        suspend fun poll(numbers: LongRange, initUrl: String? = "init.mp4") {
            buffer.ingest(
                initUrl = initUrl,
                advertised = numbers.map { number ->
                    DashMediaSegmentRef(uri = "seg-$number.m4s", durationMs = 2_000L, position = number, startMs = number * 2_000L)
                },
                fetch = { uri, durationMs, isInit ->
                    fetched += uri
                    Segment(uri, durationMs, if (isInit) initBytes else bytesPerSegment)
                },
                onWindow = { windows += it }
            )
        }
    }

    private fun memoryHarness(bytesPerSegment: Long, initBytes: Long = 4_096L) = Harness(
        maxBytes = config.effectiveMaxBytes(LiveTimeshiftBackend.MEMORY),
        maxDurationMs = depthMs,
        bytesPerSegment = bytesPerSegment,
        initBytes = initBytes
    )

    @Test
    fun `high bitrate DASH input is trimmed to the cap dropping the oldest media first and keeping init`() = runTest {
        // 5 minutes of 2 MB, 2 s segments = about 300 MB, within the time cap but far over the byte cap.
        val harness = memoryHarness(bytesPerSegment = 2L * 1024 * 1024)

        harness.poll(1L..150L)

        val buffered = harness.buffer.snapshot()
        assertThat(buffered.first().uri).isEqualTo("init.mp4")
        assertThat(buffered.last().uri).isEqualTo("seg-150.m4s")
        assertThat(buffered.sumOf { it.bytes }).isAtMost(cap)
        assertThat(harness.removed.map { it.uri })
            .containsExactlyElementsIn((1L..harness.removed.size.toLong()).map { "seg-$it.m4s" })
            .inOrder()
        assertThat(buffered.size + harness.removed.size).isEqualTo(151)
        assertThat(harness.windows.last()).isEqualTo(buffered.sumOf { it.durationMs })
    }

    @Test
    fun `DASH time cap prunes past the init segment at the head`() = runTest {
        // Before the fix the init segment at the head stopped all pruning, so DASH grew without bound.
        val harness = Harness(maxBytes = null, maxDurationMs = depthMs, bytesPerSegment = 1_000L, initBytes = 1_000L)

        harness.poll(1L..200L)

        val buffered = harness.buffer.snapshot()
        assertThat(buffered.first().uri).isEqualTo("init.mp4")
        assertThat(buffered.sumOf { it.durationMs }).isAtMost(depthMs)
        assertThat(buffered.sumOf { it.durationMs }).isGreaterThan(depthMs - 2_000L)
    }

    @Test
    fun `low bitrate DASH input within both caps is unchanged`() = runTest {
        val harness = memoryHarness(bytesPerSegment = 500_000L)

        harness.poll(1L..150L)

        assertThat(harness.removed).isEmpty()
        assertThat(harness.buffer.snapshot()).hasSize(151)
    }

    @Test
    fun `an init segment alone over the cap is kept and does not loop`() = runTest {
        val harness = memoryHarness(bytesPerSegment = 1_000L, initBytes = cap + 1L)

        harness.poll(1L..3L)

        assertThat(harness.buffer.snapshot().map { it.uri }).containsExactly("init.mp4")
        assertThat(harness.removed).hasSize(3)
    }

    @Test
    fun `a stale manifest after a fresh one fetches nothing and keeps capture order`() = runTest {
        val harness = memoryHarness(bytesPerSegment = 1_000L)

        harness.poll(10L..20L)
        harness.poll(8L..18L)
        harness.poll(12L..22L)

        assertThat(harness.fetched)
            .containsExactlyElementsIn(listOf("init.mp4") + (10L..22L).map { "seg-$it.m4s" })
            .inOrder()
        assertThat(harness.buffer.snapshot().map { it.uri })
            .containsExactlyElementsIn(listOf("init.mp4") + (10L..22L).map { "seg-$it.m4s" })
            .inOrder()
    }

    @Test
    fun `segments still listed after the byte cap pruned them are not fetched again`() = runTest {
        val harness = memoryHarness(bytesPerSegment = 2L * 1024 * 1024)

        harness.poll(1L..150L)
        val fetchedAfterFirstPoll = harness.fetched.size
        harness.poll(2L..151L)

        assertThat(harness.fetched.size).isEqualTo(fetchedAfterFirstPoll + 1)
        assertThat(harness.fetched.last()).isEqualTo("seg-151.m4s")
        assertThat(harness.buffer.snapshot().last().uri).isEqualTo("seg-151.m4s")
    }

    @Test
    fun `the DASH session buffer gets the memory byte cap and the disk buffer gets none`() {
        val memory = dashCaptureBufferFor<Segment>(config, LiveTimeshiftBackend.MEMORY, { it.durationMs }, { it.bytes }, {})
        val disk = dashCaptureBufferFor<Segment>(config, LiveTimeshiftBackend.DISK, { it.durationMs }, { it.bytes }, {})

        assertThat(memory.maxBytes).isEqualTo(cap)
        assertThat(memory.maxDurationMs).isEqualTo(depthMs)
        assertThat(disk.maxBytes).isNull()
        assertThat(disk.maxDurationMs).isEqualTo(config.effectiveDepthMs(LiveTimeshiftBackend.DISK))
    }
}
