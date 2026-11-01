package com.streamvault.surround

import com.google.common.truth.Truth.assertThat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Test

class UpmixPipelineTest {

    private fun pcm16(vararg samples: Int): ByteBuffer =
        ByteBuffer.allocate(samples.size * 2).order(ByteOrder.nativeOrder()).apply {
            samples.forEach { putShort(it.toShort()) }
            flip()
        }

    private fun pcmFloat(vararg samples: Float): ByteBuffer =
        ByteBuffer.allocate(samples.size * 4).order(ByteOrder.nativeOrder()).apply {
            samples.forEach { putFloat(it) }
            flip()
        }

    @Test
    fun fullBlockBecomesOneFrameStampedWithItsFirstSample() {
        val enc = FakeEncoder(blockFrames = 4)
        val p = UpmixPipeline(enc, 48_000, floatInput = false)
        val input = pcm16(16384, 0, 0, 0, 0, 0, 0, 0)   // 4 stereo frames, first L = 0.5

        assertThat(p.consume(input, 1_000_000L, 0)).isTrue()
        assertThat(input.hasRemaining()).isFalse()
        assertThat(p.pendingTimeUs).isEqualTo(1_000_000L)
        assertThat(p.pendingFrame!!.remaining()).isEqualTo(8)
        assertThat(p.pendingFrame!!.get(1)).isEqualTo(50.toByte())
        // Upmixed 5.1 reached the encoder: FL, FR, FC, LFE, SL, SR of frame 0.
        assertThat(enc.blocks.single().take(6)).containsExactly(0.5f, 0f, 0.25f, 0f, 0.5f, 0f).inOrder()
    }

    @Test
    fun blockSpanningTwoBuffersTakesTimeFromWhereItStarted() {
        val p = UpmixPipeline(FakeEncoder(blockFrames = 4), 48_000, floatInput = false)
        // Buffer A: 6 frames at t=0. First block = frames 0-3, second block starts at frame 4 (4/48000 s).
        val a = pcm16(*IntArray(12))
        assertThat(p.consume(a, 0L, 0)).isTrue()
        assertThat(p.pendingTimeUs).isEqualTo(0L)
        p.takeFrame()
        assertThat(p.consume(a, 0L, 0)).isFalse()       // frames 4-5 buffered
        assertThat(a.hasRemaining()).isFalse()
        // Buffer B continues; the block completes after 2 more frames and keeps buffer A's timing.
        val b = pcm16(*IntArray(8))
        assertThat(p.consume(b, 125L, 0)).isTrue()
        assertThat(p.pendingTimeUs).isEqualTo(4 * 1_000_000L / 48_000)
        assertThat(b.remaining()).isEqualTo(2 * 4)       // 2 frames left for the next block
    }

    @Test
    fun resumedBufferUsesItsOriginalStartPosition() {
        val p = UpmixPipeline(FakeEncoder(blockFrames = 2), 44_100, floatInput = false)
        val buf = pcm16(*IntArray(8))                    // 4 frames
        p.consume(buf, 10_000L, 0)
        p.takeFrame()
        p.consume(buf, 10_000L, 0)                       // second block starts at frame 2 of the same buffer
        assertThat(p.pendingTimeUs).isEqualTo(10_000L + 2 * 1_000_000L / 44_100)
    }

    @Test
    fun floatInputIsReadAsFloat() {
        val enc = FakeEncoder(blockFrames = 1)
        val p = UpmixPipeline(enc, 48_000, floatInput = true)
        p.consume(pcmFloat(0.25f, -0.75f), 0L, 0)
        assertThat(enc.blocks.single().toList()).containsExactly(0.25f, -0.75f, -0.25f, 0f, 0.25f, -0.75f).inOrder()
    }

    @Test
    fun finishPadsPartialBlockWithSilence() {
        val enc = FakeEncoder(blockFrames = 4)
        val p = UpmixPipeline(enc, 48_000, floatInput = false)
        p.consume(pcm16(16384, 16384), 500L, 0)          // 1 of 4 frames
        assertThat(p.pendingFrame).isNull()
        assertThat(p.finish()).isTrue()
        assertThat(p.pendingTimeUs).isEqualTo(500L)
        assertThat(enc.blocks.single().drop(6).all { it == 0f }).isTrue()
        p.takeFrame()
        assertThat(p.finish()).isFalse()                 // nothing left
    }

    @Test
    fun resetDropsBufferedInputAndPendingFrame() {
        val enc = FakeEncoder(blockFrames = 2)
        val p = UpmixPipeline(enc, 48_000, floatInput = false)
        p.consume(pcm16(1, 1, 2, 2), 0L, 0)
        p.reset()
        assertThat(p.pendingFrame).isNull()
        p.consume(pcm16(3, 3), 9_000L, 0)                // half a block after reset
        assertThat(p.pendingFrame).isNull()
        p.consume(pcm16(4, 4), 9_999L, 0)
        assertThat(p.pendingTimeUs).isEqualTo(9_000L)
    }

    @Test(expected = IllegalStateException::class)
    fun consumingWhileFramePendingIsAnError() {
        val p = UpmixPipeline(FakeEncoder(blockFrames = 1), 48_000, floatInput = false)
        p.consume(pcm16(1, 1, 2, 2), 0L, 0)
        p.consume(pcm16(1, 1), 0L, 0)
    }
}
