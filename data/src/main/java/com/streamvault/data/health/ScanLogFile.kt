package com.streamvault.data.health

import java.io.File
import java.time.Instant

/** Where the scanner writes the lines that explain how a scan went; never given a login or url. */
internal fun interface ScanLogSink {
    fun write(line: String)

    companion object {
        val None = ScanLogSink { }
    }
}

/**
 * Keeps the scan's story in a small text file, because logcat is rotated within minutes and a scan runs for hours.
 * When the file passes [maxBytes] only its newest half is kept. A failed write is ignored: the log must never
 * stop a scan.
 */
internal class ScanLogFile(
    private val file: File,
    private val nowMillis: () -> Long,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) : ScanLogSink {
    @Synchronized
    override fun write(line: String) {
        try {
            if (file.length() > maxBytes) trim()
            file.appendText("${Instant.ofEpochMilli(nowMillis())} $line\n")
        } catch (_: Exception) {
            // Nothing to do: losing a log line is better than losing the scan.
        }
    }

    private fun trim() {
        val lines = file.readLines()
        val keep = lines.drop(lines.size / 2)
        file.writeText(keep.joinToString(separator = "\n", postfix = "\n"))
    }

    internal companion object {
        const val DEFAULT_MAX_BYTES = 64L * 1024L
        const val FILE_NAME = "channel-scan.log"
    }
}
