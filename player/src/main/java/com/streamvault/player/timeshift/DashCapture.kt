package com.streamvault.player.timeshift

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
