package com.streamvault.player.timeshift

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The DASH capture's bookkeeping, kept free of Android and HTTP so tests run the same code the
 * session does: which advertised segments to fetch, the buffered segments, and pruning to the
 * duration limit and, for the memory backend, the byte limit (oldest media segments first). Init
 * segments are stored with no duration and are never pruned.
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
    private val seenInitUrls = linkedSetOf<String>()
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
        // Download each init segment once (identified by URL).
        if (initUrl != null && seenInitUrls.add(initUrl)) {
            val init = fetch(initUrl, 0L, true)
            mutex.withLock { segments += init }
        }

        cursor.onManifest(advertised)
        for (segment in advertised) {
            currentCoroutineContext().ensureActive()
            if (!cursor.take(segment)) continue
            val retained = fetch(segment.uri, segment.durationMs, false)
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
                runningDurationMs
            }
            onWindow(windowDurationMs)
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
 */
internal class DashSegmentCursor(private val restartGapMs: Long = DASH_RESTART_GAP_MS) {
    private var lastPosition: Long? = null
    private var lastStartMs = 0L

    /**
     * Call once per manifest, before [take]. When every advertised segment is far behind the mark
     * (more than [restartGapMs]), the stream restarted or began a new period: the mark is cleared so
     * the new segments are captured. A manifest only slightly behind is a lagging copy and is ignored.
     */
    fun onManifest(advertised: List<DashMediaSegmentRef>) {
        val mark = lastPosition ?: return
        val newest = advertised.maxByOrNull { it.position } ?: return
        if (newest.position < mark && lastStartMs - newest.startMs > restartGapMs) {
            lastPosition = null
        }
    }

    /** True when [segment] is newer than every segment taken so far; the mark then moves to it. */
    fun take(segment: DashMediaSegmentRef): Boolean {
        val mark = lastPosition
        if (mark != null && segment.position <= mark) return false
        lastPosition = segment.position
        lastStartMs = segment.startMs
        return true
    }

    companion object {
        /** A manifest a minute or more behind what was captured is a restart, not a lagging copy. */
        const val DASH_RESTART_GAP_MS = 60_000L
    }
}
