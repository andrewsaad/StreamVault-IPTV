package com.streamvault.data.health

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.ChannelHealthScanScope
import org.junit.Test

class ChannelHealthScanLogTest {
    @Test
    fun `a finish line carries scope, counts, ok answers and seconds`() {
        val line = formatScanLogLine("finish", ChannelHealthScanScope.NEW_ONLY, 4541, 4541, 7_890_000L, ok = 4200)
        assertThat(line).isEqualTo("scan finish scope=NEW_ONLY scanned=4541/4541 ok=4200 elapsedSec=7890")
    }

    @Test
    fun `the ok count is left out when not given`() {
        val line = formatScanLogLine("start", ChannelHealthScanScope.ALL, 0, 5533, 0L)
        assertThat(line).isEqualTo("scan start scope=ALL scanned=0/5533 elapsedSec=0")
    }

    @Test
    fun `the end of a scan is named by how it ended`() {
        assertThat(scanOutcomeEvent(finished = true, gaveUp = false, cancelled = false)).isEqualTo("finish")
        assertThat(scanOutcomeEvent(finished = false, gaveUp = true, cancelled = false)).isEqualTo("give-up")
        assertThat(scanOutcomeEvent(finished = false, gaveUp = false, cancelled = true)).isEqualTo("cancel")
    }

    @Test
    fun `an unexpected failure is logged as error, not as a cancel`() {
        assertThat(scanOutcomeEvent(finished = false, gaveUp = false, cancelled = false)).isEqualTo("error")
    }
}
