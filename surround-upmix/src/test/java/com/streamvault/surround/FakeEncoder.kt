package com.streamvault.surround

/** Encodes each block to [frameBytes] bytes: byte 0 = block counter, byte 1 = first sample's FL * 100. */
class FakeEncoder(override val blockFrames: Int = 4, private val frameBytes: Int = 8) : BlockEncoder {
    val blocks = mutableListOf<FloatArray>()
    var closed = false

    override val maxFrameBytes: Int = frameBytes

    override fun encode(pcm51: FloatArray, out: ByteArray): Int {
        blocks += pcm51.copyOf(blockFrames * StereoUpmixer.OUTPUT_CHANNELS)
        out.fill(0, 0, frameBytes)
        out[0] = blocks.size.toByte()
        out[1] = Math.round(pcm51[0] * 100).toByte()
        return frameBytes
    }

    override fun close() {
        closed = true
    }
}
