package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.player.PlaybackState
import org.junit.Test

class PlayerNowPlayingSupportTest {
    @Test
    fun `live TV that is actually playing is reported with its channel`() {
        assertThat(nowPlayingReport(eligible = true, channelName = "beIN 1", state = PlaybackState.READY, isPlaying = true))
            .isEqualTo(NowPlayingReport.Playing("beIN 1"))
    }

    @Test
    fun `buffering or unnamed live TV leaves the entry alone and pausing takes it off`() {
        assertThat(nowPlayingReport(true, "beIN 1", PlaybackState.READY, isPlaying = false)).isEqualTo(NowPlayingReport.Stopped)
        assertThat(nowPlayingReport(true, "beIN 1", PlaybackState.BUFFERING, isPlaying = true)).isEqualTo(NowPlayingReport.Unchanged)
        assertThat(nowPlayingReport(true, " ", PlaybackState.READY, isPlaying = true)).isEqualTo(NowPlayingReport.Unchanged)
        assertThat(nowPlayingReport(true, null, PlaybackState.READY, isPlaying = true)).isEqualTo(NowPlayingReport.Unchanged)
    }

    @Test
    fun `movies, series and catch-up take this TV off the board`() {
        assertThat(nowPlayingReport(eligible = false, channelName = "beIN 1", state = PlaybackState.READY, isPlaying = true))
            .isEqualTo(NowPlayingReport.Stopped)
    }

    @Test
    fun `the other viewer is looked up while stuck and cleared only when playback recovers`() {
        assertThat(otherViewerLookupWanted(PlaybackState.BUFFERING)).isTrue()
        assertThat(otherViewerLookupWanted(PlaybackState.ERROR)).isTrue()
        assertThat(otherViewerLookupWanted(PlaybackState.READY)).isFalse()
        assertThat(otherViewerLookupWanted(PlaybackState.IDLE)).isNull()
        assertThat(otherViewerLookupWanted(PlaybackState.ENDED)).isNull()
    }

    @Test
    fun `the account is given up only after this TV was playing and then lost it`() {
        assertThat(shouldYieldToOtherViewer(wasPlayingThisSession = true, state = PlaybackState.BUFFERING)).isTrue()
        assertThat(shouldYieldToOtherViewer(wasPlayingThisSession = true, state = PlaybackState.ERROR)).isTrue()
        // Opening a channel or pressing Retry is a deliberate takeover.
        assertThat(shouldYieldToOtherViewer(wasPlayingThisSession = false, state = PlaybackState.BUFFERING)).isFalse()
        assertThat(shouldYieldToOtherViewer(wasPlayingThisSession = true, state = PlaybackState.READY)).isFalse()
    }
}
