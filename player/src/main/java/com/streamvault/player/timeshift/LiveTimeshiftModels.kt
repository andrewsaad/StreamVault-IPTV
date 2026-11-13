package com.streamvault.player.timeshift

import com.streamvault.domain.model.TimeshiftBackendPreference

data class TimeshiftConfig(
    val enabled: Boolean = false,
    val depthMinutes: Int = 30,
    val backendPreference: TimeshiftBackendPreference = TimeshiftBackendPreference.AUTOMATIC
) {
    val depthMs: Long = depthMinutes.coerceIn(15, 60) * 60_000L

    fun effectiveDepthMs(backend: LiveTimeshiftBackend): Long =
        if (backend == LiveTimeshiftBackend.MEMORY)
            depthMinutes.coerceIn(1, MAX_MEMORY_BACKEND_DEPTH_MINUTES) * 60_000L
        else
            depthMs

    /** Byte cap for the in-memory backend; null for backends that are not held on the heap. */
    fun effectiveMaxBytes(backend: LiveTimeshiftBackend): Long? =
        if (backend == LiveTimeshiftBackend.MEMORY) MAX_MEMORY_BACKEND_BYTES else null

    companion object {
        const val MAX_MEMORY_BACKEND_DEPTH_MINUTES = 5

        /**
         * 96 MiB: the memory backend keeps every chunk on the Java heap, and the 5 minute time cap alone
         * allows about 300 MB at 8 Mbps, which can exhaust the heap on low-RAM TV devices. 96 MiB leaves the
         * full 5 minutes for streams up to about 2.5 Mbps and shortens the rewind window (oldest dropped
         * first) for anything heavier.
         */
        const val MAX_MEMORY_BACKEND_BYTES = 96L * 1024L * 1024L
    }
}

/**
 * Drops the oldest items until both the duration limit and the optional byte limit hold, and
 * returns the remaining duration. Items for which [canPrune] is false (such as a DASH init
 * segment) stay in place, still count toward the byte limit, and pruning continues past them.
 */
internal fun <T> pruneOldestToLimits(
    items: ArrayDeque<T>,
    runningDurationMs: Long,
    maxDurationMs: Long,
    maxBytes: Long?,
    durationOf: (T) -> Long,
    bytesOf: (T) -> Long,
    canPrune: (T) -> Boolean = { true },
    onRemoved: (T) -> Unit
): Long {
    var durationMs = runningDurationMs
    var bytes = if (maxBytes != null) items.sumOf(bytesOf) else 0L
    var index = 0
    while (index < items.size && (durationMs > maxDurationMs || (maxBytes != null && bytes > maxBytes))) {
        if (!canPrune(items[index])) {
            index++
            continue
        }
        val removed = items.removeAt(index)
        durationMs -= durationOf(removed)
        bytes -= bytesOf(removed)
        onRemoved(removed)
    }
    return durationMs
}

/** A DASH init segment is stored with no duration and must be kept for every later media segment. */
internal fun isDashMediaSegment(durationMs: Long): Boolean = durationMs > 0L

/** Seen-set key for a DASH init segment, kept apart from media segment addresses. */
internal const val DASH_INIT_SEEN_KEY_PREFIX = "__init__:"

/**
 * Keeps only the seen keys the latest manifest still lists (init keys are always kept). A segment
 * dropped by pruning stays "seen" while it is still advertised, so it is not downloaded again and
 * appended out of order, and the set cannot grow without bound. An empty list (a manifest that
 * could not be resolved) changes nothing, so buffered segments are not fetched a second time.
 */
internal fun retainAdvertisedDashSegments(seen: MutableSet<String>, advertisedUris: Collection<String>) {
    if (advertisedUris.isEmpty()) return
    val advertised = advertisedUris.toHashSet()
    seen.retainAll { it.startsWith(DASH_INIT_SEEN_KEY_PREFIX) || it in advertised }
}

enum class LiveTimeshiftBackend {
    NONE,
    DISK,
    MEMORY
}

internal fun resolveLiveTimeshiftBackend(
    preference: TimeshiftBackendPreference,
    snapshotStorageAvailable: Boolean,
    diskStorageAvailable: Boolean
): LiveTimeshiftBackend? {
    if (!snapshotStorageAvailable) return null

    return when (preference) {
        TimeshiftBackendPreference.AUTOMATIC ->
            if (diskStorageAvailable) LiveTimeshiftBackend.DISK else LiveTimeshiftBackend.MEMORY

        TimeshiftBackendPreference.STORAGE ->
            LiveTimeshiftBackend.DISK.takeIf { diskStorageAvailable }

        TimeshiftBackendPreference.MEMORY -> LiveTimeshiftBackend.MEMORY
    }
}

enum class LiveTimeshiftStatus {
    DISABLED,
    UNSUPPORTED,
    PREPARING,
    LIVE,
    PAUSED_BEHIND_LIVE,
    PLAYING_BEHIND_LIVE,
    BUFFERING,
    FAILED
}

data class LiveTimeshiftState(
    val enabled: Boolean = false,
    val supported: Boolean = false,
    val backend: LiveTimeshiftBackend = LiveTimeshiftBackend.NONE,
    val status: LiveTimeshiftStatus = LiveTimeshiftStatus.DISABLED,
    val bufferStartMs: Long = 0L,
    val bufferEndMs: Long = 0L,
    val liveEdgePositionMs: Long = 0L,
    val currentOffsetFromLiveMs: Long = 0L,
    val bufferedDurationMs: Long = 0L,
    val message: String? = null
) {
    val canSeekToLive: Boolean = supported && currentOffsetFromLiveMs > 1_000L
    val isActive: Boolean = enabled && supported && status != LiveTimeshiftStatus.DISABLED && status != LiveTimeshiftStatus.UNSUPPORTED
}

internal data class LiveTimeshiftSnapshot(
    val url: String,
    val durationMs: Long,
    val backend: LiveTimeshiftBackend
)
