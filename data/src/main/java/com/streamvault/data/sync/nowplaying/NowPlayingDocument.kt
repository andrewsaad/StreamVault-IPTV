package com.streamvault.data.sync.nowplaying

import com.streamvault.domain.sync.OtherViewer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The shared "who is watching" board: one entry per TV, keyed by a per-device id. It holds TV
 * names and channel names only, never stream URLs or account details, and is encrypted like the
 * favorites document before it leaves the TV.
 */
@Serializable
data class NowPlayingDocument(
    val schema: Int = SCHEMA,
    val devices: Map<String, NowPlayingEntry> = emptyMap()
) {
    companion object {
        const val SCHEMA = 1
        val EMPTY = NowPlayingDocument()
    }
}

@Serializable
data class NowPlayingEntry(
    val name: String,
    val channel: String,
    val sinceMs: Long,
    val seenMs: Long
)

internal object NowPlayingBoard {
    /** A TV that has not refreshed for this long is treated as gone (crashed, unplugged or offline). */
    const val FRESH_MS = 3 * 60_000L

    /** Entries older than this are dropped on every write so the board cannot grow. */
    const val PRUNE_MS = 60 * 60_000L

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(document: NowPlayingDocument): String = json.encodeToString(NowPlayingDocument.serializer(), document)

    fun decode(text: String): NowPlayingDocument = json.decodeFromString(NowPlayingDocument.serializer(), text)

    /** This TV playing [channel]; keeps the start time while the channel is unchanged. */
    fun upsert(
        document: NowPlayingDocument,
        deviceId: String,
        deviceName: String,
        channel: String,
        nowMs: Long
    ): NowPlayingDocument {
        val previous = document.devices[deviceId]
        val since = previous?.takeIf { it.channel == channel && nowMs - it.seenMs < FRESH_MS }?.sinceMs ?: nowMs
        val entry = NowPlayingEntry(name = deviceName, channel = channel, sinceMs = since, seenMs = nowMs)
        return prune(document.copy(devices = document.devices + (deviceId to entry)), nowMs)
    }

    fun remove(document: NowPlayingDocument, deviceId: String, nowMs: Long): NowPlayingDocument =
        prune(document.copy(devices = document.devices - deviceId), nowMs)

    /** Freshest other TV still playing, or null. */
    fun otherViewer(document: NowPlayingDocument, deviceId: String, nowMs: Long): OtherViewer? =
        document.devices
            .filterKeys { it != deviceId }
            .values
            .filter { nowMs - it.seenMs < FRESH_MS }
            .maxByOrNull { it.sinceMs }
            ?.let { OtherViewer(deviceName = it.name, channelName = it.channel, sinceMs = it.sinceMs) }

    private fun prune(document: NowPlayingDocument, nowMs: Long): NowPlayingDocument =
        document.copy(devices = document.devices.filterValues { nowMs - it.seenMs < PRUNE_MS })
}
