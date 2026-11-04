package com.streamvault.feature.playback.player

import android.text.format.DateFormat
import androidx.lifecycle.viewModelScope
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.sync.OtherViewer
import com.streamvault.feature.playback.R
import com.streamvault.player.PlaybackState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Date

/** Buffering this long on live TV before asking which other TV holds the account. */
internal const val OTHER_VIEWER_LOOKUP_AFTER_MS = 3_000L
internal const val OTHER_VIEWER_RELOOKUP_MS = 10_000L

internal fun PlayerViewModel.isNowPlayingEligible(): Boolean =
    currentContentType == ContentType.LIVE && !isCatchUpPlayback()

/** What the reporting loop should tell the household board for the current playback. */
internal sealed interface NowPlayingReport {
    data class Playing(val channelName: String) : NowPlayingReport
    data object Stopped : NowPlayingReport
    /** Loading or stalled: leave the entry as it is rather than flapping it. */
    data object Unchanged : NowPlayingReport
}

internal fun nowPlayingReport(
    eligible: Boolean,
    channelName: String?,
    state: PlaybackState,
    isPlaying: Boolean
): NowPlayingReport = when {
    !eligible -> NowPlayingReport.Stopped
    channelName.isNullOrBlank() -> NowPlayingReport.Unchanged
    state == PlaybackState.READY && isPlaying -> NowPlayingReport.Playing(channelName)
    // Paused live TV is not holding the account in any useful way; say so.
    state == PlaybackState.READY -> NowPlayingReport.Stopped
    else -> NowPlayingReport.Unchanged
}

/** True while the line naming the other TV should be looked up; false once playback recovers; null keeps it. */
internal fun otherViewerLookupWanted(state: PlaybackState): Boolean? = when (state) {
    PlaybackState.BUFFERING, PlaybackState.ERROR -> true
    PlaybackState.READY -> false
    // Retries pass through IDLE; keep the line up until playback actually recovers.
    PlaybackState.IDLE, PlaybackState.ENDED -> null
}

/**
 * Give the account up only when this TV was playing and then lost it. Opening a channel or
 * pressing Retry starts a new session (not yet ready), which is a deliberate takeover.
 */
internal fun shouldYieldToOtherViewer(wasPlayingThisSession: Boolean, state: PlaybackState): Boolean =
    wasPlayingThisSession && otherViewerLookupWanted(state) == true

/** Runs from the 500 ms reporting loop; the registry throttles uploads to one per refresh period. */
internal fun PlayerViewModel.reportNowPlaying(
    state: PlaybackState = playerEngine.playbackState.value,
    isPlaying: Boolean = playerEngine.isPlaying.value
) {
    when (val report = nowPlayingReport(isNowPlayingEligible(), currentChannelFlow.value?.name, state, isPlaying)) {
        is NowPlayingReport.Playing -> nowPlayingRegistry.reportPlaying(report.channelName)
        NowPlayingReport.Stopped -> nowPlayingRegistry.reportStopped()
        NowPlayingReport.Unchanged -> Unit
    }
}

/**
 * While live TV is stuck buffering or failed, look up (and keep re-checking) which other TV is
 * playing. Once one is found this TV gives the account up instead of retrying: every retry would
 * kick the other TV off again, and the two would keep taking turns. Retry on the notice takes it back.
 */
internal fun PlayerViewModel.onNowPlayingPlaybackState(state: PlaybackState) {
    when (otherViewerLookupWanted(state)) {
        true -> {
            if (!isNowPlayingEligible() || otherViewerLookupJob?.isActive == true) return
            otherViewerLookupJob = viewModelScope.launch {
                delay(OTHER_VIEWER_LOOKUP_AFTER_MS)
                while (isActive) {
                    val viewer = nowPlayingRegistry.otherViewer()
                    otherViewerFlow.value = viewer
                    if (viewer != null && shouldYieldToOtherViewer(livePlaybackReadyForCurrentSession, playerEngine.playbackState.value)) {
                        yieldToOtherViewer(viewer)
                        return@launch
                    }
                    delay(OTHER_VIEWER_RELOOKUP_MS)
                }
            }
        }
        false -> clearOtherViewer()
        null -> Unit
    }
}

internal fun PlayerViewModel.clearOtherViewer() {
    otherViewerLookupJob?.cancel()
    otherViewerLookupJob = null
    otherViewerFlow.value = null
}

private fun PlayerViewModel.yieldToOtherViewer(viewer: OtherViewer) {
    appendRecoveryAction("Account in use by another TV")
    playerEngine.stop()
    nowPlayingRegistry.reportStopped()
    val since = DateFormat.getTimeFormat(appContext).format(Date(viewer.sinceMs))
    showPlayerNotice(
        message = appContext.getString(R.string.player_account_in_use, viewer.deviceName, viewer.channelName, since),
        recoveryType = PlayerRecoveryType.SOURCE,
        actions = listOf(PlayerNoticeAction.RETRY),
        durationMs = ACCOUNT_IN_USE_NOTICE_MS
    )
}

/** Long enough to read from the sofa; Retry stays available from the controls afterwards. */
private const val ACCOUNT_IN_USE_NOTICE_MS = 10 * 60_000L
