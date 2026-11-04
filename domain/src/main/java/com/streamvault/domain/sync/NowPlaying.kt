package com.streamvault.domain.sync

/** Another TV in the household playing live TV on the shared provider account. */
data class OtherViewer(
    val deviceName: String,
    val channelName: String,
    val sinceMs: Long
)

/**
 * Household "who is watching" board, shared through the home sync server. The provider account
 * allows one connection, so when this TV's stream fails the player can name the TV that took it.
 * Every call is best effort: no sync pairing or no server simply means nothing is reported.
 */
interface NowPlayingRegistry {
    /** Start or refresh this TV's entry. Returns at once; the upload runs in the background. */
    fun reportPlaying(channelName: String)

    /** Remove this TV's entry. Returns at once; the upload runs in the background. */
    fun reportStopped()

    /** The most recent other TV seen playing within the last few minutes, or null. */
    suspend fun otherViewer(): OtherViewer?
}
