package com.streamvault.feature.settings.presentation

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.ChannelHealthPauseReason
import org.junit.Test

class ChannelCleanupKeepScreenOnTest {
    @Test
    fun `a running scan keeps the screen on`() {
        assertThat(shouldKeepScreenOn(ChannelCleanupScanUi.Running(scanned = 10, total = 100))).isTrue()
    }

    @Test
    fun `a paused scan keeps the screen on so it can resume`() {
        val paused = ChannelCleanupScanUi.Paused(ChannelHealthPauseReason.PLAYBACK_ACTIVE, scanned = 10, total = 100)
        assertThat(shouldKeepScreenOn(paused)).isTrue()
    }

    @Test
    fun `an idle or finished scan lets the screen sleep`() {
        assertThat(shouldKeepScreenOn(ChannelCleanupScanUi.Idle(lastFinishedAt = null))).isFalse()
        assertThat(shouldKeepScreenOn(ChannelCleanupScanUi.Idle(lastFinishedAt = 123L, gaveUp = true))).isFalse()
    }

    @Test
    fun `the scan state mapper agrees with the keep awake rule`() {
        val running = com.streamvault.domain.model.ChannelHealthScanState(running = true, scanned = 1, total = 5)
        assertThat(shouldKeepScreenOn(mapChannelCleanupScan(running))).isTrue()
        assertThat(shouldKeepScreenOn(mapChannelCleanupScan(running.copy(running = false)))).isFalse()
    }
}
