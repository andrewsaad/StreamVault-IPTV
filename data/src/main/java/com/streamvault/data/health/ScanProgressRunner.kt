package com.streamvault.data.health

import android.util.Log
import com.streamvault.domain.model.ChannelHealthScanState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Runs [scan] while keeping the foreground notification current. [promote] shows or refreshes the
 * notification and returns false when Android refuses it; the scan then runs as an ordinary job with no
 * progress updates. A failing progress display never aborts the scan, and the progress coroutine is
 * cancelled when the scan ends. Returns what [scan] returned.
 */
internal suspend fun runScanWithProgress(
    promote: suspend (scanned: Int, total: Int) -> Boolean,
    states: Flow<ChannelHealthScanState>,
    now: () -> Long,
    scan: suspend () -> Boolean,
): Boolean = coroutineScope {
    val foreground = promote(0, 0)
    val progress = if (foreground) launch { keepNotificationCurrent(promote, states, now) } else null
    try {
        scan()
    } finally {
        progress?.cancel()
    }
}

private suspend fun keepNotificationCurrent(
    promote: suspend (scanned: Int, total: Int) -> Boolean,
    states: Flow<ChannelHealthScanState>,
    now: () -> Long,
) {
    var lastRefreshAt: Long? = null
    var shown = 0 to 0
    try {
        states.collect { state ->
            val at = now()
            val current = state.scanned to state.total
            if (state.running && ChannelScanNotification.shouldRefresh(lastRefreshAt, at, current != shown)) {
                lastRefreshAt = at
                shown = current
                promote(current.first, current.second)
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        // A broken progress display must never abort the scan itself; class name only.
        Log.w(ChannelHealthScanWorker.LOG_TAG, "Scan notification updates stopped (${error.javaClass.simpleName})")
    }
}
