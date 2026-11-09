package com.streamvault.domain.model

/** What one probe of a live channel found. */
enum class ChannelHealthStatus {
    /** The playlist and the first piece of video both arrived. */
    OK,

    /** The provider answered with nothing: no source for this channel right now (event channels do this off air). */
    EMPTY,

    /** A clear failure: not found, forbidden, no playlist or no video. */
    DEAD,

    /** A provider error or a timeout; says nothing about the channel. */
    UNCLEAR,
}

/** The latest probe results of one channel, plus how many separate days in a row it has failed. */
data class ChannelHealthRecord(
    val channelId: Long,
    val status: ChannelHealthStatus,
    val checkedAt: Long,
    /** Distinct calendar days, ending with the latest probe, on which the channel was EMPTY or DEAD. */
    val failedDays: Int,
)

/** A channel the user is asked to review: failed on at least [ChannelHealthRules.MIN_FAILED_DAYS] separate days. */
data class ChannelHealthSuggestion(
    val channel: Channel,
    val record: ChannelHealthRecord,
)

enum class ChannelHealthPauseReason {
    /** Something is playing on this account; the scan yields the provider's connection. */
    PLAYBACK_ACTIVE,

    /** The provider answered with errors; the scan is backing off. */
    PROVIDER_BACKOFF,
}

data class ChannelHealthScanState(
    val running: Boolean = false,
    val scanned: Int = 0,
    val total: Int = 0,
    val paused: ChannelHealthPauseReason? = null,
    val lastFinishedAt: Long? = null,
)

object ChannelHealthRules {
    /** A channel is only suggested after failing on this many separate days (event channels are empty between events). */
    const val MIN_FAILED_DAYS = 2

    fun isSuggestion(record: ChannelHealthRecord): Boolean =
        (record.status == ChannelHealthStatus.EMPTY || record.status == ChannelHealthStatus.DEAD) &&
            record.failedDays >= MIN_FAILED_DAYS
}
