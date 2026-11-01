package com.streamvault.surround

/** Encodes fixed-size blocks of interleaved 5.1 float PCM into compressed frames (AC-3 in production). */
interface BlockEncoder : AutoCloseable {
    /** Frames per block (1536 for AC-3). */
    val blockFrames: Int

    /** Largest frame [encode] can write. */
    val maxFrameBytes: Int

    /** Encodes one block; returns the bytes written to [out], 0 if nothing came out yet. Throws on failure. */
    fun encode(pcm51: FloatArray, out: ByteArray): Int
}
