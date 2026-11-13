package com.streamvault.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class QualityNetworkChangeFilterTest {
    private val wifi = QualityNetworkChangeFilter.Signature(transportMask = 1, metered = false, validated = true)
    private val cellular = QualityNetworkChangeFilter.Signature(transportMask = 2, metered = true, validated = true)

    @Test
    fun `routine capability callback on the same network does not reset quality state`() {
        val filter = QualityNetworkChangeFilter()
        assertThat(filter.onAvailable("net1")).isTrue()
        assertThat(filter.onCapabilitiesChanged("net1", wifi)).isFalse()

        // DHCP renewal or signal-strength update: same network, same signature.
        assertThat(filter.onCapabilitiesChanged("net1", wifi)).isFalse()
        assertThat(filter.onAvailable("net1")).isFalse()
    }

    @Test
    fun `reset makes the next callback behave like a first sighting`() {
        val filter = QualityNetworkChangeFilter()
        filter.onAvailable("net1")
        filter.onCapabilitiesChanged("net1", wifi)
        assertThat(filter.onAvailable("net1")).isFalse()

        filter.reset()

        assertThat(filter.onAvailable("net1")).isTrue()
    }

    @Test
    fun `transport change, new network and loss reset quality state`() {
        val filter = QualityNetworkChangeFilter()
        filter.onAvailable("net1")
        filter.onCapabilitiesChanged("net1", wifi)

        assertThat(filter.onCapabilitiesChanged("net1", cellular)).isTrue()
        assertThat(filter.onCapabilitiesChanged("net2", wifi)).isTrue()
        assertThat(filter.onLost("net2")).isTrue()
        assertThat(filter.onAvailable("net2")).isTrue()
    }
}
