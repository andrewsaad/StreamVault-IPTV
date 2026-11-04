package com.streamvault.player.diagnostics

/** Likely reason for the current buffering, shown under the spinner with a suggestion. */
enum class BufferingHint {
    /**
     * HTTP 401/403/406: the provider refused the stream. On a single-connection Xtream account a
     * device that was kicked off by another one gets 406 on every request.
     */
    ACCOUNT_REFUSED,
    /** HTTP 404/410: this channel's stream is missing. */
    CHANNEL_OFFLINE,
    /** HTTP 5xx: the provider's server is overloaded or failing. */
    SERVER_BUSY,
    /** Weak Wi-Fi or a slow local link. */
    WEAK_NETWORK,
    /** Frames dropped or the decoder stalled while video was buffered. */
    DEVICE_DECODE,
    /** Timeouts, or the link is fast but the provider is not sending fast enough. */
    PROVIDER_SLOW
}

/** What the engine knows about the current buffering stretch. All values are already tracked; nothing is fetched. */
internal data class BufferingSignals(
    val bufferingForMs: Long,
    val recentHttpCodes: List<Int>,
    val recentTimeouts: Int,
    val estimateBps: Long,
    val bufferMs: Long,
    val rssiDbm: Int?,
    val recentDroppedFrames: Int,
    val recentDecoderStall: Boolean
)

/**
 * Heuristic, ordered from most to least certain: an HTTP status names the cause outright;
 * otherwise network evidence is weighed before blaming the provider. Returns null rather than guess.
 */
internal object BufferingDiagnosis {
    const val SHOW_AFTER_MS = 3_000L
    const val WEAK_RSSI_DBM = -70
    const val SLOW_LINK_BPS = 3_000_000L
    const val FAST_LINK_BPS = 10_000_000L
    const val HEALTHY_BUFFER_MS = 2_000L
    const val DROPPED_FRAMES_BURST = 10

    fun classify(signals: BufferingSignals): BufferingHint? {
        if (signals.bufferingForMs < SHOW_AFTER_MS) return null
        val codes = signals.recentHttpCodes
        if (codes.any { it == 401 || it == 403 || it == 406 }) return BufferingHint.ACCOUNT_REFUSED
        if (codes.any { it == 404 || it == 410 }) return BufferingHint.CHANNEL_OFFLINE
        if (codes.any { it in 500..599 }) return BufferingHint.SERVER_BUSY
        if (signals.bufferMs >= HEALTHY_BUFFER_MS &&
            (signals.recentDecoderStall || signals.recentDroppedFrames >= DROPPED_FRAMES_BURST)
        ) return BufferingHint.DEVICE_DECODE
        val weakWifi = signals.rssiDbm != null && signals.rssiDbm <= WEAK_RSSI_DBM
        if (weakWifi || (signals.estimateBps in 1 until SLOW_LINK_BPS)) return BufferingHint.WEAK_NETWORK
        if (signals.recentTimeouts > 0 || signals.estimateBps >= FAST_LINK_BPS) return BufferingHint.PROVIDER_SLOW
        return null
    }
}
