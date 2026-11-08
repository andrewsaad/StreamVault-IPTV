package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.player.PlaybackState
import com.streamvault.player.PlayerEngine
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class PlayerLifecycleBackgroundPauseTest {

    private fun engine(isPlaying: Boolean, state: PlaybackState, playWhenReady: Boolean): PlayerEngine {
        val engine: PlayerEngine = mock()
        whenever(engine.isPlaying).thenReturn(MutableStateFlow(isPlaying))
        whenever(engine.playbackState).thenReturn(MutableStateFlow(state))
        whenever(engine.playWhenReady).thenReturn(playWhenReady)
        return engine
    }

    @Test
    fun `buffering stream with play intent is paused on background`() {
        assertThat(shouldPauseOnBackground(engine(isPlaying = false, state = PlaybackState.BUFFERING, playWhenReady = true)))
            .isTrue()
    }

    @Test
    fun `playing stream is paused on background`() {
        assertThat(shouldPauseOnBackground(engine(isPlaying = true, state = PlaybackState.READY, playWhenReady = true)))
            .isTrue()
    }

    @Test
    fun `buffering stream the user paused is not paused or resumed on background`() {
        assertThat(shouldPauseOnBackground(engine(isPlaying = false, state = PlaybackState.BUFFERING, playWhenReady = false)))
            .isFalse()
    }

    @Test
    fun `errored or ended stream with stale play intent is not resumed on foreground`() {
        assertThat(shouldPauseOnBackground(engine(isPlaying = false, state = PlaybackState.ERROR, playWhenReady = true)))
            .isFalse()
        assertThat(shouldPauseOnBackground(engine(isPlaying = false, state = PlaybackState.ENDED, playWhenReady = true)))
            .isFalse()
    }

    @Test
    fun `idle engine is not paused on background`() {
        assertThat(shouldPauseOnBackground(engine(isPlaying = false, state = PlaybackState.IDLE, playWhenReady = false)))
            .isFalse()
    }
}
