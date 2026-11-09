package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RefreshRateGraceTest {
    @Test
    fun `the grace period outlasts the slowest measured frame rate detection`() {
        // Game room TV, Nov 8: first frame to rate request took 6.1 s and 8.8 s from prepare.
        // A shorter grace period releases the mode and then asks for it again on every zap.
        val slowestMeasuredDetectionMs = 9_000L
        assertThat(REFRESH_RATE_GRACE_MS).isGreaterThan(slowestMeasuredDetectionMs)
    }

    @Test
    fun `the grace period still releases the mode for a source that never reports a rate`() {
        assertThat(REFRESH_RATE_GRACE_MS).isAtMost(30_000L)
    }
}
