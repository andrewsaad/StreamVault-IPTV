package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.ContentType
import com.streamvault.player.timeshift.LiveTimeshiftStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundStreamReleaserTest {

    @Test
    fun `releases after the delay and asks for a fresh prepare on return`() = runTest {
        val releaser = BackgroundStreamReleaser(backgroundScope, delayMs = 30_000L)
        var releases = 0

        releaser.onBackgrounded(eligible = true) { releases++ }
        advanceTimeBy(29_999L)
        runCurrent()
        assertThat(releases).isEqualTo(0)

        advanceTimeBy(1L)
        runCurrent()
        assertThat(releases).isEqualTo(1)
        assertThat(releaser.onForegrounded()).isTrue()
        assertThat(releaser.onForegrounded()).isFalse()
    }

    @Test
    fun `quick return cancels the release`() = runTest {
        val releaser = BackgroundStreamReleaser(backgroundScope, delayMs = 30_000L)
        var releases = 0

        releaser.onBackgrounded(eligible = true) { releases++ }
        advanceTimeBy(10_000L)
        assertThat(releaser.onForegrounded()).isFalse()
        advanceTimeBy(60_000L)
        runCurrent()

        assertThat(releases).isEqualTo(0)
    }

    @Test
    fun `ineligible playback is never released`() = runTest {
        val releaser = BackgroundStreamReleaser(backgroundScope, delayMs = 30_000L)
        var releases = 0

        releaser.onBackgrounded(eligible = false) { releases++ }
        advanceTimeBy(60_000L)
        runCurrent()

        assertThat(releases).isEqualTo(0)
        assertThat(releaser.onForegrounded()).isFalse()
    }

    @Test
    fun `cancel drops a pending release and a finished one`() = runTest {
        val releaser = BackgroundStreamReleaser(backgroundScope, delayMs = 30_000L)
        var releases = 0

        releaser.onBackgrounded(eligible = true) { releases++ }
        releaser.cancel()
        advanceTimeBy(60_000L)
        runCurrent()
        assertThat(releases).isEqualTo(0)

        releaser.onBackgrounded(eligible = true) { releases++ }
        advanceTimeBy(60_000L)
        runCurrent()
        releaser.cancel()
        assertThat(releases).isEqualTo(1)
        assertThat(releaser.onForegrounded()).isFalse()
    }

    @Test
    fun `only plain live playback is eligible`() {
        assertThat(isBackgroundReleaseEligible(ContentType.LIVE, false, LiveTimeshiftStatus.DISABLED)).isTrue()
        assertThat(isBackgroundReleaseEligible(ContentType.LIVE, false, LiveTimeshiftStatus.LIVE)).isTrue()
        assertThat(isBackgroundReleaseEligible(ContentType.LIVE, true, LiveTimeshiftStatus.DISABLED)).isFalse()
        assertThat(isBackgroundReleaseEligible(ContentType.LIVE, false, LiveTimeshiftStatus.PAUSED_BEHIND_LIVE)).isFalse()
        assertThat(isBackgroundReleaseEligible(ContentType.LIVE, false, LiveTimeshiftStatus.PLAYING_BEHIND_LIVE)).isFalse()
        assertThat(isBackgroundReleaseEligible(ContentType.MOVIE, false, LiveTimeshiftStatus.DISABLED)).isFalse()
        assertThat(isBackgroundReleaseEligible(ContentType.SERIES_EPISODE, false, LiveTimeshiftStatus.DISABLED)).isFalse()
    }
}
