package com.streamvault.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackSupportSnapshotPolicyTest {
    @Test
    fun `idle engine without a stream never writes the shared snapshot`() {
        assertThat(
            shouldWritePlaybackSupportSnapshot(
                hasStream = false,
                isLowMemoryDevice = false,
                isBuffering = false,
                hasFirstFrame = false
            )
        ).isFalse()
    }

    @Test
    fun `engine with a stream writes unless a low memory device is still starting`() {
        assertThat(shouldWritePlaybackSupportSnapshot(true, false, true, false)).isTrue()
        assertThat(shouldWritePlaybackSupportSnapshot(true, true, true, false)).isFalse()
        assertThat(shouldWritePlaybackSupportSnapshot(true, true, true, true)).isTrue()
        assertThat(shouldWritePlaybackSupportSnapshot(true, true, false, false)).isTrue()
    }
}
