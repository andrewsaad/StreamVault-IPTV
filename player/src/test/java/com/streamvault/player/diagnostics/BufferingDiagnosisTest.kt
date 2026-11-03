package com.streamvault.player.diagnostics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BufferingDiagnosisTest {
    private val base = BufferingSignals(
        bufferingForMs = 5_000L,
        recentHttpCodes = emptyList(),
        recentTimeouts = 0,
        estimateBps = 0L,
        bufferMs = 0L,
        rssiDbm = null,
        recentDroppedFrames = 0,
        recentDecoderStall = false
    )

    private fun classify(signals: BufferingSignals) = BufferingDiagnosis.classify(signals)

    @Test
    fun `stays silent during the first three seconds`() {
        assertThat(classify(base.copy(bufferingForMs = 2_999L, recentHttpCodes = listOf(503)))).isNull()
        assertThat(classify(base.copy(bufferingForMs = 3_000L, recentHttpCodes = listOf(503))))
            .isEqualTo(BufferingHint.SERVER_BUSY)
    }

    @Test
    fun `http status names the cause and refusal wins over server errors`() {
        assertThat(classify(base.copy(recentHttpCodes = listOf(503, 403)))).isEqualTo(BufferingHint.ACCOUNT_REFUSED)
        assertThat(classify(base.copy(recentHttpCodes = listOf(401)))).isEqualTo(BufferingHint.ACCOUNT_REFUSED)
        assertThat(classify(base.copy(recentHttpCodes = listOf(404)))).isEqualTo(BufferingHint.CHANNEL_OFFLINE)
        assertThat(classify(base.copy(recentHttpCodes = listOf(410)))).isEqualTo(BufferingHint.CHANNEL_OFFLINE)
        assertThat(classify(base.copy(recentHttpCodes = listOf(502)))).isEqualTo(BufferingHint.SERVER_BUSY)
    }

    @Test
    fun `decoder trouble with video buffered blames the device`() {
        assertThat(classify(base.copy(bufferMs = 8_000L, recentDecoderStall = true))).isEqualTo(BufferingHint.DEVICE_DECODE)
        assertThat(classify(base.copy(bufferMs = 8_000L, recentDroppedFrames = 12))).isEqualTo(BufferingHint.DEVICE_DECODE)
        // An empty buffer means the data never arrived, so drops are not the cause.
        assertThat(classify(base.copy(bufferMs = 0L, recentDroppedFrames = 12, estimateBps = 50_000_000L)))
            .isEqualTo(BufferingHint.PROVIDER_SLOW)
    }

    @Test
    fun `weak wifi or a slow link blames the network before the provider`() {
        assertThat(classify(base.copy(rssiDbm = -78, estimateBps = 50_000_000L))).isEqualTo(BufferingHint.WEAK_NETWORK)
        assertThat(classify(base.copy(rssiDbm = -40, estimateBps = 1_500_000L))).isEqualTo(BufferingHint.WEAK_NETWORK)
        assertThat(classify(base.copy(rssiDbm = -78, recentTimeouts = 2))).isEqualTo(BufferingHint.WEAK_NETWORK)
    }

    @Test
    fun `fast link or timeouts with good wifi blames the provider`() {
        // This morning's stall: strong Wi-Fi, 80 Mbps link, provider sending 1.2 Mbps.
        assertThat(classify(base.copy(rssiDbm = -25, estimateBps = 80_000_000L))).isEqualTo(BufferingHint.PROVIDER_SLOW)
        assertThat(classify(base.copy(recentTimeouts = 1))).isEqualTo(BufferingHint.PROVIDER_SLOW)
    }

    @Test
    fun `no evidence gives no hint`() {
        assertThat(classify(base)).isNull()
        assertThat(classify(base.copy(rssiDbm = -40, estimateBps = 6_000_000L))).isNull()
    }
}
