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
        assertThat(
            shouldWritePlaybackSupportSnapshot(
                hasStream = true,
                isLowMemoryDevice = false,
                isBuffering = true,
                hasFirstFrame = false
            )
        ).isTrue()
        assertThat(
            shouldWritePlaybackSupportSnapshot(
                hasStream = true,
                isLowMemoryDevice = true,
                isBuffering = true,
                hasFirstFrame = false
            )
        ).isFalse()
        assertThat(
            shouldWritePlaybackSupportSnapshot(
                hasStream = true,
                isLowMemoryDevice = true,
                isBuffering = true,
                hasFirstFrame = true
            )
        ).isTrue()
        assertThat(
            shouldWritePlaybackSupportSnapshot(
                hasStream = true,
                isLowMemoryDevice = true,
                isBuffering = false,
                hasFirstFrame = false
            )
        ).isTrue()
    }
}
