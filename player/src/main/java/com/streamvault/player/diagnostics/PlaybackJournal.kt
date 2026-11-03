package com.streamvault.player.diagnostics

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.streamvault.player.PlaybackState
import com.streamvault.player.PlayerStats
import com.streamvault.player.playback.PlaybackLogSanitizer
import com.streamvault.player.stats.PlaybackBufferingTracker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Persistent, low-overhead record of foreground playback health (startup, rebuffering,
 * load/player errors, stall recovery, periodic network samples) so a stall can be explained
 * after the fact. Files live in app-specific external storage so `adb shell` can read them
 * without run-as: `<external-files>/playback-journal/journal.log` plus one rotated file.
 *
 * Threading: every public method is called on the engine's main thread, keeps only small
 * session counters and builds one line per event. Lines go into a bounded channel with
 * trySend; a single IO consumer does all file work. A full channel drops the line instead
 * of blocking playback, and journal failures are logged once and otherwise ignored.
 */
@Singleton
class PlaybackJournal internal constructor(
    private val directoryProvider: () -> File?,
    private val wallClockMs: () -> Long,
    private val elapsedMs: () -> Long,
    private val zone: () -> ZoneId,
    private val networkProbe: () -> String,
    writerScope: CoroutineScope,
    private val maxFileBytes: Long = MAX_FILE_BYTES,
    capacity: Int = CHANNEL_CAPACITY,
    private val failureLog: (String, Throwable) -> Unit = { message, error -> Log.w(TAG, message, error) }
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        // Resolved lazily on the writer thread: getExternalFilesDir can touch storage.
        directoryProvider = {
            (context.getExternalFilesDir(null) ?: context.filesDir)?.let { File(it, DIRECTORY_NAME) }
        },
        wallClockMs = System::currentTimeMillis,
        elapsedMs = SystemClock::elapsedRealtime,
        zone = ZoneId::systemDefault,
        networkProbe = PlaybackNetworkProbe(context)::describe,
        writerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    )

    internal class Entry(
        val wallMs: Long,
        val body: String,
        val flush: Boolean,
        val appendNetwork: Boolean
    )

    private val entries = Channel<Entry>(capacity)
    private val droppedEntries = AtomicInteger(0)
    @Volatile
    private var failureLogged = false

    // Session bookkeeping; main thread only.
    private var sessionActive = false
    private var sessionStartElapsedMs = 0L
    private var playbackStartedOnce = false
    private var activeBufferingCause: PlaybackBufferingTracker.Cause? = null
    private var bufferingStartElapsedMs = 0L
    private var stallCount = 0
    private var stallTotalMs = 0L
    private var worstStallMs = 0L
    private var sampleTicks = 0
    private var lastVideoBitrate = -1
    private var lastVideoWidth = -1
    private var lastVideoHeight = -1

    internal val stallCountForTest: Int get() = stallCount
    internal val stallTotalMsForTest: Long get() = stallTotalMs

    init {
        writerScope.launch { runWriter() }
    }

    @Suppress("LongParameterList")
    fun startSession(
        channelName: String?,
        mediaId: String,
        streamType: String,
        url: String?,
        decoderPolicy: String,
        audioOutputPath: String,
        upmix: Boolean,
        bufferPolicy: String?
    ) = guard {
        if (sessionActive) endSession("replaced")
        sessionActive = true
        sessionStartElapsedMs = elapsedMs()
        playbackStartedOnce = false
        activeBufferingCause = null
        stallCount = 0
        stallTotalMs = 0L
        worstStallMs = 0L
        sampleTicks = 0
        lastVideoBitrate = -1
        lastVideoWidth = -1
        lastVideoHeight = -1
        enqueue(
            "session-start channel=${quote(channelName.orEmpty())} mediaId=$mediaId type=$streamType " +
                "target=${journalTarget(url)} decoderPolicy=$decoderPolicy audioPath=$audioOutputPath " +
                "upmix=$upmix policy=${quote(bufferPolicy.orEmpty())}",
            flush = true,
            appendNetwork = true
        )
    }

    /** A retry or recovery re-prepare of the same media keeps the session and its counters. */
    fun onReprepare(reason: String, streamType: String) = guard {
        if (!sessionActive) return@guard
        enqueue("reprepare reason=$reason type=$streamType")
    }

    fun onFirstFrame(ttffMs: Long) = guard {
        if (!sessionActive) return@guard
        enqueue("first-frame ttffMs=$ttffMs")
    }

    fun onPlaybackStarted() {
        playbackStartedOnce = true
    }

    internal fun onBufferingEvent(event: PlaybackBufferingTracker.Event, bufferMs: Long, stats: PlayerStats) = guard {
        if (!sessionActive) return@guard
        val now = elapsedMs()
        if (event.outcome == PlaybackBufferingTracker.Outcome.STARTED) {
            activeBufferingCause = event.cause
            bufferingStartElapsedMs = now
            // Startup and seeks are expected waits; only their end is worth a line.
            if (event.cause == PlaybackBufferingTracker.Cause.REBUFFER ||
                event.cause == PlaybackBufferingTracker.Cause.RECOVERY
            ) {
                enqueue(
                    "buffering-start cause=${event.cause.name.lowercase()} ${statsFields(bufferMs, stats)}",
                    flush = true,
                    appendNetwork = true
                )
            }
            return@guard
        }
        val cause = activeBufferingCause ?: event.cause
        activeBufferingCause = null
        val durationMs = (now - bufferingStartElapsedMs).coerceAtLeast(0L)
        val outcome = event.outcome.name.lowercase()
        when (cause) {
            PlaybackBufferingTracker.Cause.STARTUP -> enqueue(
                "startup-buffering outcome=$outcome durationMs=$durationMs " +
                    "sinceSessionStartMs=${now - sessionStartElapsedMs} ttffMs=${stats.ttffMs} " +
                    statsFields(bufferMs, stats),
                flush = true
            )
            PlaybackBufferingTracker.Cause.SEEK -> enqueue(
                "seek-buffering outcome=$outcome durationMs=$durationMs ${statsFields(bufferMs, stats)}"
            )
            PlaybackBufferingTracker.Cause.REBUFFER, PlaybackBufferingTracker.Cause.RECOVERY -> {
                stallCount++
                stallTotalMs += durationMs
                if (durationMs > worstStallMs) worstStallMs = durationMs
                enqueue(
                    "buffering-end cause=${cause.name.lowercase()} outcome=$outcome durationMs=$durationMs " +
                        statsFields(bufferMs, stats),
                    flush = true,
                    appendNetwork = true
                )
            }
        }
    }

    fun onLoadError(httpCode: Int?, errorClass: String, message: String?, dataType: Int) = guard {
        if (!sessionActive) return@guard
        enqueue(
            "load-error http=${httpCode ?: "none"} error=$errorClass dataType=$dataType " +
                "message=${quote(journalMessage(message))}",
            flush = true,
            appendNetwork = true
        )
    }

    fun onPlayerError(errorCodeName: String, category: String, message: String?) = guard {
        if (!sessionActive) return@guard
        enqueue(
            "player-error code=$errorCodeName category=$category message=${quote(journalMessage(message))}",
            flush = true,
            appendNetwork = true
        )
    }

    fun onRetry(attempt: Int, maxAttempts: Int, delayMs: Long, reason: String) = guard {
        if (!sessionActive) return@guard
        enqueue("retry attempt=$attempt/$maxAttempts delayMs=$delayMs reason=${quote(reason)}", flush = true)
    }

    /** [action] is a short token such as `detected`, `live-seek` or `decoder-fallback`. */
    fun onVideoStall(action: String, stallCount: Int, decoder: String, bufferMs: Long) = guard {
        if (!sessionActive) return@guard
        enqueue(
            "video-stall action=$action count=$stallCount decoder=${quote(decoder)} bufferMs=$bufferMs",
            flush = true
        )
    }

    fun onDroppedFrames(droppedFrames: Int, elapsedMs: Long) = guard {
        if (!sessionActive || droppedFrames < DROPPED_FRAMES_BURST) return@guard
        enqueue("dropped-frames count=$droppedFrames windowMs=$elapsedMs")
    }

    fun onVideoDecoder(decoderName: String) = guard {
        if (!sessionActive) return@guard
        enqueue("video-decoder name=${quote(decoderName)}")
    }

    fun onAudioDecoder(decoderName: String, audioOutputPath: String) = guard {
        if (!sessionActive) return@guard
        enqueue("audio-decoder name=${quote(decoderName)} audioPath=$audioOutputPath")
    }

    fun onVideoFormat(bitrate: Int, width: Int, height: Int) = guard {
        if (!sessionActive) return@guard
        if (bitrate == lastVideoBitrate && width == lastVideoWidth && height == lastVideoHeight) return@guard
        val previous = if (lastVideoWidth < 0) "none" else "${lastVideoWidth}x$lastVideoHeight@$lastVideoBitrate"
        lastVideoBitrate = bitrate
        lastVideoWidth = width
        lastVideoHeight = height
        enqueue("format-change from=$previous to=${width}x$height@$bitrate")
    }

    /**
     * Called from the engine's existing one-second stats loop. Counts only ticks spent
     * playing or buffering and writes a sample every [SAMPLE_EVERY_TICKS] of them, so no
     * extra timer exists and an idle or paused player writes nothing.
     */
    fun onStatsTick(state: PlaybackState, isPlaying: Boolean, stats: PlayerStats) = guard {
        if (!sessionActive) return@guard
        val active = state == PlaybackState.BUFFERING || (state == PlaybackState.READY && isPlaying)
        if (!active) return@guard
        sampleTicks++
        if (sampleTicks < SAMPLE_EVERY_TICKS) return@guard
        sampleTicks = 0
        enqueue(
            "sample state=${if (state == PlaybackState.BUFFERING) "buffering" else "playing"} " +
                "${statsFields(stats.bufferedDurationMs, stats)} rebuffers=${stats.rebufferCount} " +
                "drops=${stats.droppedFrames}",
            flush = true,
            appendNetwork = true
        )
    }

    fun endSession(reason: String) = guard {
        if (!sessionActive) return@guard
        val now = elapsedMs()
        // A stop during a stall still counts that stall toward the session totals.
        val openStall = activeBufferingCause
        if (openStall == PlaybackBufferingTracker.Cause.REBUFFER || openStall == PlaybackBufferingTracker.Cause.RECOVERY) {
            val durationMs = (now - bufferingStartElapsedMs).coerceAtLeast(0L)
            stallCount++
            stallTotalMs += durationMs
            if (durationMs > worstStallMs) worstStallMs = durationMs
        }
        activeBufferingCause = null
        sessionActive = false
        enqueue(
            "session-end reason=$reason watchMs=${now - sessionStartElapsedMs} started=$playbackStartedOnce " +
                "rebuffers=$stallCount rebufferMs=$stallTotalMs worstStallMs=$worstStallMs",
            flush = true
        )
    }

    /** Stops accepting lines and lets the writer drain, flush and close its file. */
    internal fun close() {
        entries.close()
    }

    private fun statsFields(bufferMs: Long, stats: PlayerStats): String =
        "bufferMs=$bufferMs estimateBps=${stats.bandwidthEstimate} deliveredBps=${stats.measuredBitrate} " +
            "selectedBps=${stats.videoBitrate} size=${stats.width}x${stats.height}"

    private fun enqueue(body: String, flush: Boolean = false, appendNetwork: Boolean = false) {
        val result = entries.trySend(Entry(wallClockMs(), body, flush, appendNetwork))
        if (result.isFailure && !result.isClosed) droppedEntries.incrementAndGet()
    }

    private inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            reportFailure("playback journal event skipped", error)
        }
    }

    private fun reportFailure(message: String, error: Throwable) {
        if (failureLogged) return
        failureLogged = true
        runCatching { failureLog(message, error) }
    }

    private suspend fun runWriter() {
        val writer = JournalFileWriter(directoryProvider, maxFileBytes)
        var lastFlushWallMs = wallClockMs()
        try {
            for (entry in entries) {
                try {
                    val dropped = droppedEntries.getAndSet(0)
                    if (dropped > 0) {
                        writer.append(formatLine(entry.wallMs, "journal-overflow dropped=$dropped"))
                    }
                    val body = if (entry.appendNetwork) "${entry.body} ${probeNetwork()}" else entry.body
                    writer.append(formatLine(entry.wallMs, body))
                    val now = wallClockMs()
                    if (entry.flush || now - lastFlushWallMs >= FLUSH_INTERVAL_MS) {
                        writer.flush()
                        lastFlushWallMs = now
                    }
                } catch (error: Exception) {
                    reportFailure("playback journal write failed", error)
                    writer.reset()
                }
            }
        } finally {
            runCatching { writer.close() }
        }
    }

    private fun probeNetwork(): String =
        runCatching(networkProbe).getOrElse { "net=unknown" }

    internal fun formatLine(wallMs: Long, body: String): String =
        "${TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(wallMs).atZone(zone()))} $body\n"

    internal companion object {
        const val TAG = "PlaybackJournal"
        const val DIRECTORY_NAME = "playback-journal"
        const val CURRENT_FILE = "journal.log"
        const val ROTATED_FILE = "journal.1.log"
        const val MAX_FILE_BYTES = 512L * 1024L
        const val CHANNEL_CAPACITY = 256
        const val SAMPLE_EVERY_TICKS = 30
        const val DROPPED_FRAMES_BURST = 10
        const val FLUSH_INTERVAL_MS = 30_000L
        private const val MAX_MESSAGE_CHARS = 160
        private val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        // Any scheme: rtmp/rtsp/udp stream URLs can carry credentials too.
        private val urlRegex = Regex("""[a-z][a-z0-9+.-]*://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
        private val mediaSegmentRegex = Regex("""\d+(\.[A-Za-z0-9]{1,5})?|[\w-]+\.(m3u8|mpd|ts|m4s|mp4|mkv|aac)""", RegexOption.IGNORE_CASE)

        /**
         * Host plus a credential-free path. A path the shared sanitizer left untouched may still
         * hold a login (e.g. `host/user/pass` or unusual Xtream shapes), so any unredacted path
         * with two or more segments is collapsed to its last one.
         */
        fun journalTarget(url: String?): String {
            val sanitized = PlaybackLogSanitizer.sanitizeUrl(url)
            if ('<' in sanitized) return sanitized
            val slash = sanitized.indexOf('/')
            if (slash < 0) return sanitized
            val segments = sanitized.substring(slash + 1).split('/').filter(String::isNotEmpty)
            if (segments.size < 2) return sanitized
            // Keep the last part only when it is a media file or numeric id; in `host/user/pass` it is the password.
            val last = segments.last().takeIf { mediaSegmentRegex.matches(it) } ?: "<redacted>"
            return "${sanitized.substring(0, slash)}/<redacted>/$last"
        }

        fun journalMessage(message: String?): String {
            val sanitized = PlaybackLogSanitizer.sanitizeMessage(message)
            return urlRegex.replace(sanitized) { match -> journalTarget(match.value) }
                .take(MAX_MESSAGE_CHARS)
        }

        /** Values with spaces stay one token; quotes, backslashes and newlines are escaped. */
        fun quote(value: String): String {
            val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace('\n', ' ').replace('\r', ' ')
            return "\"$escaped\""
        }
    }
}

/** Writer-thread only. Appends UTF-8 lines and keeps at most two files of [maxFileBytes]. */
internal class JournalFileWriter(
    private val directoryProvider: () -> File?,
    private val maxFileBytes: Long
) {
    private var output: OutputStream? = null
    private var currentBytes = 0L
    private var directory: File? = null

    fun append(line: String) {
        val bytes = line.toByteArray(Charsets.UTF_8)
        val stream = openIfNeeded() ?: return
        val target = if (currentBytes > 0L && currentBytes + bytes.size > maxFileBytes) rotate() else stream
        target.write(bytes)
        currentBytes += bytes.size
    }

    fun flush() {
        output?.flush()
    }

    /** Drops the stream after an I/O failure; the next line reopens the file. */
    fun reset() {
        runCatching { output?.close() }
        output = null
    }

    fun close() {
        output?.let { stream ->
            output = null
            stream.flush()
            stream.close()
        }
    }

    private fun openIfNeeded(): OutputStream? {
        output?.let { return it }
        val dir = directory ?: directoryProvider()?.also { directory = it } ?: return null
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val file = File(dir, PlaybackJournal.CURRENT_FILE)
        currentBytes = if (file.exists()) file.length() else 0L
        return FileOutputStream(file, true).buffered(BUFFER_BYTES).also { output = it }
    }

    private fun rotate(): OutputStream {
        close()
        val dir = requireNotNull(directory)
        val current = File(dir, PlaybackJournal.CURRENT_FILE)
        val rotated = File(dir, PlaybackJournal.ROTATED_FILE)
        if (rotated.exists()) rotated.delete()
        if (!current.renameTo(rotated)) current.delete()
        currentBytes = 0L
        return FileOutputStream(current, false).buffered(BUFFER_BYTES).also { output = it }
    }

    private companion object {
        const val BUFFER_BYTES = 8 * 1024
    }
}
