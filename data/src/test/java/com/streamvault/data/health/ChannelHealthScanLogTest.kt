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
    fun `a log line never holds a url or a login`() {
        val line = formatScanLogLine("give-up", ChannelHealthScanScope.BAD_ONLY, 10, 245, 5_000L, ok = 1)
        assertThat(line).doesNotContain("http")
        assertThat(line).doesNotContain("password")
    }
}
