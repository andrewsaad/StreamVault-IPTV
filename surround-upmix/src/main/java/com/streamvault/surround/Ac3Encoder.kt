package com.streamvault.surround

/** FFmpeg's AC-3 encoder (5.1, [bitRate] bit/s) through libsvac3.so. Not thread-safe; one per stream. */
class Ac3Encoder(sampleRate: Int, bitRate: Int = DEFAULT_BIT_RATE) : BlockEncoder {
    private var handle: Long = nativeOpen(sampleRate, bitRate)

    init {
        check(handle != 0L) { "AC-3 encoder could not open at $sampleRate Hz, $bitRate bit/s" }
    }

    override val blockFrames: Int = nativeFrameSize(handle)

    // 640 kbit/s at 32 kHz is the largest AC-3 frame: 3840 bytes (ATSC A/52 table 5.18).
    override val maxFrameBytes: Int = 3840

    override fun encode(pcm51: FloatArray, out: ByteArray): Int {
        check(handle != 0L) { "encoder closed" }
        val n = nativeEncode(handle, pcm51, out)
        check(n >= 0) { "AC-3 encode failed" }
        return n
    }

    override fun close() {
        if (handle != 0L) {
            nativeClose(handle)
            handle = 0L
        }
    }

    companion object {
        const val DEFAULT_BIT_RATE = 640_000
        val SUPPORTED_SAMPLE_RATES = setOf(32_000, 44_100, 48_000)

        /** False when the native library is missing (e.g. an ABI it wasn't built for). */
        val isAvailable: Boolean by lazy {
            try {
                System.loadLibrary("svac3")
                true
            } catch (e: UnsatisfiedLinkError) {
                false
            }
        }

        @JvmStatic private external fun nativeOpen(sampleRate: Int, bitRate: Int): Long
        @JvmStatic private external fun nativeFrameSize(handle: Long): Int
        @JvmStatic private external fun nativeEncode(handle: Long, pcm: FloatArray, out: ByteArray): Int
        @JvmStatic private external fun nativeClose(handle: Long)
    }
}
