package com.streamvault.surround

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Collects stereo PCM into encoder-sized blocks, upmixes each block to 5.1 and encodes it.
 * Pure JVM (no android.*) so it is unit-tested with a fake [BlockEncoder].
 *
 * Timestamps: each output frame carries the presentation time of its block's first input frame, derived from
 * the input buffer's timestamp plus the frames already taken from that buffer.
 */
class UpmixPipeline(
    private val encoder: BlockEncoder,
    private val sampleRate: Int,
    private val floatInput: Boolean
) {
    private val bytesPerFrame = if (floatInput) 8 else 4
    private val block = encoder.blockFrames
    private val stereo = FloatArray(block * 2)
    private val surround = FloatArray(block * StereoUpmixer.OUTPUT_CHANNELS)
    private val encoded = ByteArray(encoder.maxFrameBytes)
    private var filled = 0
    private var blockStartUs = UNSET

    /**
     * Linear gain applied before encoding. The player's volume (and mute) can't act on AC-3 passthrough, so it is
     * applied here instead.
     */
    var gain: Float = 1f

    /** Encoded frame waiting to be handed on, positioned for reading; null when there is none. */
    var pendingFrame: ByteBuffer? = null
        private set
    var pendingTimeUs: Long = UNSET
        private set

    /**
     * Reads PCM from [input] (stereo, native order) until a block completes or [input] runs out.
     * [inputTimeUs] is the timestamp of the frame at [inputStartPosition]. Returns true when a frame became pending.
     * Must not be called while [pendingFrame] is set.
     */
    fun consume(input: ByteBuffer, inputTimeUs: Long, inputStartPosition: Int): Boolean {
        check(pendingFrame == null) { "previous frame not taken" }
        val src = input.duplicate().order(ByteOrder.nativeOrder())
        while (src.remaining() >= bytesPerFrame && filled < block) {
            if (filled == 0) {
                val framesIntoInput = (src.position() - inputStartPosition) / bytesPerFrame
                blockStartUs = inputTimeUs + framesIntoInput * 1_000_000L / sampleRate
            }
            val i = 2 * filled
            if (floatInput) {
                stereo[i] = src.getFloat() * gain
                stereo[i + 1] = src.getFloat() * gain
            } else {
                stereo[i] = src.getShort() / 32768f * gain
                stereo[i + 1] = src.getShort() / 32768f * gain
            }
            filled++
        }
        input.position(src.position())
        return if (filled == block) encodeBlock() else false
    }

    /** Pads a partial block with silence and encodes it (end of stream). Returns true when a frame became pending. */
    fun finish(): Boolean {
        if (filled == 0 || pendingFrame != null) return false
        stereo.fill(0f, 2 * filled)
        filled = block
        return encodeBlock()
    }

    /** Marks the pending frame as handed on. */
    fun takeFrame() {
        pendingFrame = null
        pendingTimeUs = UNSET
    }

    /** Drops buffered input and any pending frame (seek, flush, reconfigure). */
    fun reset() {
        filled = 0
        blockStartUs = UNSET
        takeFrame()
    }

    private fun encodeBlock(): Boolean {
        StereoUpmixer.upmix(stereo, block, surround)
        val n = encoder.encode(surround, encoded)
        val startUs = blockStartUs
        filled = 0
        blockStartUs = UNSET
        if (n <= 0) return false
        pendingFrame = ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder()).apply {
            put(encoded, 0, n)
            flip()
        }
        pendingTimeUs = startUs
        return true
    }

    companion object {
        const val UNSET = Long.MIN_VALUE
    }
}
