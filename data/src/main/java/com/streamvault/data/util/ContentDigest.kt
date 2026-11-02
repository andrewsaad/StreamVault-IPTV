package com.streamvault.data.util

import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest

/** SHA-256 helpers for detecting provider payloads that did not change since the last commit. */
internal object ContentDigest {
    private const val HEX_DIGITS = "0123456789abcdef"

    fun newSha256(): MessageDigest = MessageDigest.getInstance("SHA-256")

    /**
     * Starts a digest with [seedParts] so the same payload parsed under different settings
     * (timezone, mapping profile, app version) never compares as unchanged.
     */
    fun seeded(vararg seedParts: String): MessageDigest = newSha256().apply {
        seedParts.forEach { part ->
            update(part.toByteArray(Charsets.UTF_8))
            update(0)
        }
    }

    fun hex(digest: MessageDigest): String = toHex(digest.digest())

    fun sha256Hex(vararg parts: String): String = hex(seeded(*parts))

    fun toHex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        bytes.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            chars[index * 2] = HEX_DIGITS[value ushr 4]
            chars[index * 2 + 1] = HEX_DIGITS[value and 0xf]
        }
        return String(chars)
    }
}

/**
 * Feeds every byte read through it into [digest]. [drainToEnd] consumes what a parser left
 * unread, so the digest always covers the whole payload rather than only the parsed prefix.
 */
internal class DigestingInputStream(
    input: InputStream,
    private val digest: MessageDigest
) : FilterInputStream(input) {
    override fun read(): Int = super.read().also { if (it >= 0) digest.update(it.toByte()) }

    override fun read(bytes: ByteArray, off: Int, len: Int): Int =
        super.read(bytes, off, len).also { if (it > 0) digest.update(bytes, off, it) }

    override fun skip(n: Long): Long {
        // Skipped bytes must still be hashed.
        val buffer = ByteArray(minOf(n, 8_192L).toInt().coerceAtLeast(1))
        var remaining = n
        while (remaining > 0) {
            val read = read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            if (read <= 0) break
            remaining -= read
        }
        return n - remaining
    }

    override fun markSupported(): Boolean = false

    fun drainToEnd() {
        val buffer = ByteArray(8_192)
        while (read(buffer, 0, buffer.size) >= 0) Unit
    }
}
