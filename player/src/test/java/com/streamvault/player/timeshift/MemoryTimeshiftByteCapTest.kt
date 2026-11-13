package com.streamvault.player.timeshift

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MemoryTimeshiftByteCapTest {
    private class Chunk(val durationMs: Long, val bytes: Long)

    private val cap = TimeshiftConfig.MAX_MEMORY_BACKEND_BYTES
    private val depthMs = TimeshiftConfig().effectiveDepthMs(LiveTimeshiftBackend.MEMORY)

    private fun prune(chunks: ArrayDeque<Chunk>, maxBytes: Long?): List<Chunk> {
        val removed = mutableListOf<Chunk>()
        pruneOldestToLimits(
            items = chunks,
            runningDurationMs = chunks.sumOf { it.durationMs },
            maxDurationMs = depthMs,
            maxBytes = maxBytes,
            durationOf = { it.durationMs },
            bytesOf = { it.bytes },
            onRemoved = { removed += it }
        )
        return removed
    }

    @Test
    fun `memory backend has a byte cap and other backends do not`() {
        val config = TimeshiftConfig()
        assertThat(config.effectiveMaxBytes(LiveTimeshiftBackend.MEMORY)).isEqualTo(cap)
        assertThat(config.effectiveMaxBytes(LiveTimeshiftBackend.DISK)).isNull()
    }

    @Test
    fun `high bitrate input is trimmed to the cap dropping the oldest first`() {
        // 5 minutes of 2 MB, 2 s chunks = about 300 MB, within the time cap but far over the byte cap.
        val chunks = ArrayDeque((0 until 150).map { Chunk(durationMs = 2_000L, bytes = 2L * 1024 * 1024) })
        val newest = chunks.last()
        val oldest = chunks.first()

        val removed = prune(chunks, cap)

        assertThat(chunks.sumOf { it.bytes }).isAtMost(cap)
        assertThat(chunks.last()).isSameInstanceAs(newest)
        assertThat(removed.first()).isSameInstanceAs(oldest)
        assertThat(chunks.size + removed.size).isEqualTo(150)
        assertThat(chunks.sumOf { it.durationMs }).isLessThan(depthMs)
    }

    @Test
    fun `low bitrate input within both caps is unchanged`() {
        // 5 minutes of 2 Mbps = about 75 MB.
        val chunks = ArrayDeque((0 until 150).map { Chunk(durationMs = 2_000L, bytes = 500_000L) })

        val removed = prune(chunks, cap)

        assertThat(removed).isEmpty()
        assertThat(chunks).hasSize(150)
    }

    @Test
    fun `time cap still applies without a byte cap`() {
        val chunks = ArrayDeque((0 until 200).map { Chunk(durationMs = 2_000L, bytes = 1_000L) })

        prune(chunks, maxBytes = null)

        assertThat(chunks.sumOf { it.durationMs }).isAtMost(depthMs)
    }
}
