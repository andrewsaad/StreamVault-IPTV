package com.streamvault.feature.playback.player

import com.streamvault.domain.model.ContentType
import com.streamvault.player.timeshift.LiveTimeshiftStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Stops a live stream that stays paused in the background, so it stops holding the
 * provider's connection (many IPTV accounts allow only one) and the next foreground start
 * gets a fresh stream URL instead of a stale session the provider may reject.
 *
 * A quick return inside [delayMs] cancels the release and resumes the paused stream as before.
 */
internal class BackgroundStreamReleaser(
    private val scope: CoroutineScope,
    private val delayMs: Long = DEFAULT_DELAY_MS
) {
    private var releaseJob: Job? = null
    private var released = false

    fun onBackgrounded(eligible: Boolean, release: () -> Unit) {
        cancel()
        if (!eligible) return
        releaseJob = scope.launch {
            delay(delayMs)
            release()
            released = true
        }
    }

    /** Returns true when the stream was released and must be prepared again. */
    fun onForegrounded(): Boolean {
        val wasReleased = released
        cancel()
        return wasReleased
    }

    fun cancel() {
        releaseJob?.cancel()
        releaseJob = null
        released = false
    }

    companion object {
        const val DEFAULT_DELAY_MS = 30_000L
    }
}

/**
 * Only plain live playback is released: catch-up and timeshift behind live would lose
 * the viewer's position, and other content types resume from a saved position anyway.
 */
internal fun isBackgroundReleaseEligible(
    contentType: ContentType,
    isCatchUpPlayback: Boolean,
    timeshiftStatus: LiveTimeshiftStatus
): Boolean =
    contentType == ContentType.LIVE &&
        !isCatchUpPlayback &&
        timeshiftStatus != LiveTimeshiftStatus.PAUSED_BEHIND_LIVE &&
        timeshiftStatus != LiveTimeshiftStatus.PLAYING_BEHIND_LIVE
