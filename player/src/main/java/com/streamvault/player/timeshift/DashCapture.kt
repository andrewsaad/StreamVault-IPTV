package com.streamvault.player.timeshift

import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The DASH capture's bookkeeping, kept free of Android and HTTP so tests run the same code the
 * session does: which advertised segments to fetch, the buffered segments, and pruning to the
 * duration limit and, for the memory backend, the byte limit (oldest media segments first). Init
 * segments are stored with no duration; one is kept while any media segment after it still needs
 * it, and dropped once the next init follows it directly. A stream restart drops everything
 * buffered, so old and new timelines are never mixed in one rewind window.
 */
internal class DashCaptureBuffer<T : Any>(
    private val maxDurationMs: Long,
    private val maxBytes: Long?,
    private val durationOf: (T) -> Long,
    private val bytesOf: (T) -> Long,
    private val onRemoved: (T) -> Unit
) {
    private val segments = ArrayDeque<T>()
    private val mutex = Mutex()
    /**
     * URL of the init segment newest in the buffer, which every media segment captured from now on
     * plays against. A manifest with a different init URL (even one seen before) fetches that init
     * again, so media never follows a stale init. Set only after the download succeeds, so a failed
     * init download is retried on the next poll.
     */
    private var currentInitUrl: String? = null
    private val cursor = DashSegmentCursor()
    private var runningDurationMs = 0L

    /**
     * Takes in one manifest poll. [fetch] downloads a segment (an init segment when `isInit`);
     * [onWindow] receives the buffered media duration after each media segment is added.
     * Must be called from a single capture coroutine at a time.
     */
    suspend fun ingest(
        initUrl: String?,
        advertised: List<DashMediaSegmentRef>,
        fetch: suspend (uri: String, durationMs: Long, isInit: Boolean) -> T,
        onWindow: (Long) -> Unit
    ) {
        if (cursor.onManifest(advertised)) {
            // The stream restarted: the buffered media belongs to the old timeline and would play
            // before the new segments with a time jump and repeated content. Start over, init included.
            mutex.withLock {
                segments.forEach(onRemoved)
                segments.clear()
                runningDurationMs = 0L
            }
            currentInitUrl = null
            onWindow(0L)
        }

        // Download the init segment whenever its URL changes.
        if (initUrl != null && initUrl != currentInitUrl) {
            val init = fetch(initUrl, 0L, true)
            currentInitUrl = initUrl
            mutex.withLock {
                segments += init
                pruneOrphanInitsLocked()
            }
        }

        for (segment in advertised) {
            currentCoroutineContext().ensureActive()
            if (!cursor.isNew(segment)) continue
            // A failed download throws before the mark moves, so the segment is tried again next poll.
            val retained = fetch(segment.uri, segment.durationMs, false)
            cursor.markTaken(segment)
            val windowDurationMs = mutex.withLock {
                runningDurationMs += durationOf(retained)
                segments += retained
                runningDurationMs = pruneOldestToLimits(
                    items = segments,
                    runningDurationMs = runningDurationMs,
                    maxDurationMs = maxDurationMs,
                    maxBytes = maxBytes,
                    durationOf = durationOf,
                    bytesOf = bytesOf,
                    // The init segment sits at the head; it is kept and pruning continues past it.
                    canPrune = { isDashMediaSegment(durationOf(it)) },
                    onRemoved = onRemoved
                )
                pruneOrphanInitsLocked()
                runningDurationMs
            }
            onWindow(windowDurationMs)
        }
    }

    /**
     * Drops each init segment directly followed by another init: no media segment plays against it
     * any more (its media were pruned, or none was captured before the init URL changed). The last
     * init is kept even with nothing after it, since new media will follow it. Hold [mutex].
     */
    private fun pruneOrphanInitsLocked() {
        var index = 0
        while (index < segments.size - 1) {
            val isInit = !isDashMediaSegment(durationOf(segments[index]))
            val nextIsInit = !isDashMediaSegment(durationOf(segments[index + 1]))
            if (isInit && nextIsInit) onRemoved(segments.removeAt(index)) else index++
        }
    }

    /** The buffered segments in capture order, init segments included. */
    suspend fun snapshot(): List<T> = mutex.withLock { segments.toList() }
}

/**
 * One media segment a DASH manifest advertises. [position] is the segment's `$Time$`
 * (SegmentTimeline) or `$Number$` (fixed duration), which only grows during a live stream;
 * [startMs] is the same point in milliseconds.
 */
internal data class DashMediaSegmentRef(
    val uri: String,
    val durationMs: Long,
    val position: Long,
    val startMs: Long
)

/**
 * Remembers the newest DASH media segment already taken, like the HLS lastProcessedSequence, so a
 * stale or lagging manifest cannot make the capture fetch older segments again and append them out
 * of order. It holds two numbers, so it stays bounded however long the session runs.
 *
 * The mark is the segment start in milliseconds, not the raw `$Time$`/`$Number$` [position]: raw
 * `$Time$` values are in the representation's timescale, which can change between polls (90000 to
 * 1000, say), and a raw mark would then sit far above every new segment and skip them all.
 */
internal class DashSegmentCursor(private val restartGapMs: Long = DASH_RESTART_GAP_MS) {
    private var lastStartMs: Long? = null
    private var lastDurationMs = 0L

    /**
     * Call once per manifest, before [take]. When every advertised segment starts more than
     * [restartGapMs] before the mark, the stream restarted or began a new period: the mark is
     * cleared so the new segments are captured, and this returns true so the caller can drop what
     * it buffered from the old timeline. A manifest only slightly behind is a lagging copy and is
     * ignored.
     */
    fun onManifest(advertised: List<DashMediaSegmentRef>): Boolean {
        val mark = lastStartMs ?: return false
        val newest = advertised.maxByOrNull { it.startMs } ?: return false
        if (mark - newest.startMs <= restartGapMs) return false
        lastStartMs = null
        return true
    }

    /**
     * True when [segment] is newer than every segment taken so far; the mark then moves to it. A
     * segment counts as newer when it starts more than half the last segment's duration past the
     * mark, so the same segment re-advertised in another timescale (its millisecond start rounded
     * one way or the other) is not taken twice.
     */
    fun take(segment: DashMediaSegmentRef): Boolean {
        if (!isNew(segment)) return false
        markTaken(segment)
        return true
    }

    /** True when [segment] is newer than every segment taken so far. Does not move the mark. */
    fun isNew(segment: DashMediaSegmentRef): Boolean {
        val mark = lastStartMs ?: return true
        return segment.startMs > mark + lastDurationMs / 2
    }

    /** Moves the mark to [segment]; call once it has actually been captured. */
    fun markTaken(segment: DashMediaSegmentRef) {
        lastStartMs = segment.startMs
        lastDurationMs = segment.durationMs
    }

    companion object {
        /** A manifest more than a minute behind what was captured is a restart, not a lagging copy. */
        const val DASH_RESTART_GAP_MS = 60_000L
    }
}

internal data class DashSnapshotPlaylistSegment(
    val fileName: String,
    val durationMs: Long,
    val isInit: Boolean
)

/**
 * Builds the static HLS playlist for a DASH snapshot. Each media segment plays against the init
 * segment captured most recently before it: the first init becomes the opening `EXT-X-MAP`, and a
 * later init (a new representation or period) starts a new `EXT-X-MAP` behind an
 * `EXT-X-DISCONTINUITY`, so no segment is decoded with another init's codec setup or timestamps.
 */
internal fun buildDashSnapshotPlaylist(
    targetDurationSeconds: Int,
    segments: List<DashSnapshotPlaylistSegment>
): String = buildString {
    appendLine("#EXTM3U")
    appendLine("#EXT-X-VERSION:7")
    appendLine("#EXT-X-TARGETDURATION:$targetDurationSeconds")
    appendLine("#EXT-X-MEDIA-SEQUENCE:0")
    var pendingInit: String? = null
    var mediaWritten = false
    for (segment in segments) {
        if (segment.isInit) {
            pendingInit = segment.fileName
            continue
        }
        pendingInit?.let { initFileName ->
            if (mediaWritten) appendLine("#EXT-X-DISCONTINUITY")
            appendLine("#EXT-X-MAP:URI=\"$initFileName\"")
            pendingInit = null
        }
        appendLine("#EXTINF:${"%.3f".format(Locale.US, segment.durationMs / 1000.0)},")
        appendLine(segment.fileName)
        mediaWritten = true
    }
    appendLine("#EXT-X-ENDLIST")
}
