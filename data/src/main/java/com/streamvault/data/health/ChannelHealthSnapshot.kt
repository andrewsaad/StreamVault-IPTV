package com.streamvault.data.health

import com.streamvault.domain.model.ChannelHealthRecord
import com.streamvault.domain.model.ChannelHealthStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A shareable record of which live channels of one vendor work and which do not. It is keyed by the
 * provider's own stream id (the same on every device of the vendor), never by this device's database id,
 * and it holds no server address, login or stream URL.
 */
@Serializable
internal data class ChannelHealthSnapshot(
    val format: Int = FORMAT_VERSION,
    /** Free text the user typed for the vendor; never filled in from the account. */
    val label: String = "",
    val createdAt: Long = 0L,
    val ok: List<SnapshotOk> = emptyList(),
    val bad: List<SnapshotBad> = emptyList(),
) {
    companion object {
        const val FORMAT_VERSION = 1
    }
}

/** A channel that worked: [i] is the stream id, [h] a hash of its name that catches a mismatched numbering. */
@Serializable
internal data class SnapshotOk(val i: Long, val h: Int)

/** A channel that failed: stream id, name, status ("empty" or "dead"), failed days and last check time. */
@Serializable
internal data class SnapshotBad(val i: Long, val n: String, val s: String, val d: Int, val t: Long)

/** A channel of this device's provider, reduced to what the snapshot needs. */
internal data class SnapshotChannel(val id: Long, val streamId: Long, val name: String)

internal data class SnapshotImportSummary(
    val okImported: Int = 0,
    val badImported: Int = 0,
    /** The channel already has an answer on this device, which is kept. */
    val alreadyKnown: Int = 0,
    /** The provider does not list this stream id. */
    val unknown: Int = 0,
    /** The stream id exists but under another name, so it is a different channel here. */
    val nameMismatch: Int = 0,
)

/**
 * What an import changes: channels that now count as known, and failed channels that become hints
 * (a record with 0 failed days). A hint is never a hide suggestion; only failures seen on this device are.
 */
internal data class SnapshotImportPlan(
    val knownIds: Set<Long>,
    val hints: List<ChannelHealthRecord>,
    val summary: SnapshotImportSummary,
)

internal object ChannelHealthSnapshots {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Names are compared case- and spacing-insensitively; String.hashCode is specified, so it is the same everywhere. */
    fun nameHash(name: String): Int =
        name.trim().lowercase().replace(Regex("\\s+"), " ").hashCode()

    fun encode(snapshot: ChannelHealthSnapshot): String =
        json.encodeToString(ChannelHealthSnapshot.serializer(), snapshot)

    /** Null when [text] is not a snapshot or comes from a newer format than this build reads. */
    fun decode(text: String): ChannelHealthSnapshot? {
        val snapshot = runCatching { json.decodeFromString(ChannelHealthSnapshot.serializer(), text) }
            .getOrNull() ?: return null
        return snapshot.takeIf { it.format in 1..ChannelHealthSnapshot.FORMAT_VERSION }
    }

    fun build(
        channels: List<SnapshotChannel>,
        document: ChannelHealthDocument,
        label: String,
        now: Long,
    ): ChannelHealthSnapshot {
        val byId = channels.associateBy { it.id }
        val bad = document.records.values
            .filter { it.status == ChannelHealthStatus.EMPTY || it.status == ChannelHealthStatus.DEAD }
            .mapNotNull { record ->
                val channel = byId[record.channelId] ?: return@mapNotNull null
                SnapshotBad(channel.streamId, channel.name, statusName(record.status), record.failedDays, record.checkedAt)
            }
            .distinctBy { it.i }
            .sortedBy { it.i }
        val badStreamIds = bad.mapTo(HashSet()) { it.i }
        val ok = channels
            .filter { it.id in document.knownIds && it.id !in document.records && it.streamId !in badStreamIds }
            .distinctBy { it.streamId }
            .map { SnapshotOk(it.streamId, nameHash(it.name)) }
            .sortedBy { it.i }
        return ChannelHealthSnapshot(label = label.trim().take(MAX_LABEL), createdAt = now, ok = ok, bad = bad)
    }

    fun planImport(
        snapshot: ChannelHealthSnapshot,
        channels: List<SnapshotChannel>,
        document: ChannelHealthDocument,
    ): SnapshotImportPlan {
        val byStream = channels.groupBy { it.streamId }
        val known = HashSet<Long>()
        val hints = ArrayList<ChannelHealthRecord>()
        var okImported = 0
        var badImported = 0
        var alreadyKnown = 0
        var unknown = 0
        var nameMismatch = 0

        // Returns the matching channels that still need an answer, counting every skipped entry once.
        fun candidates(streamId: Long, nameHash: Int): List<SnapshotChannel> {
            val matches = byStream[streamId]
            if (matches == null) {
                unknown++
                return emptyList()
            }
            val sameName = matches.filter { nameHash(it.name) == nameHash }
            if (sameName.isEmpty()) {
                nameMismatch++
                return emptyList()
            }
            val fresh = sameName.filter { it.id !in document.knownIds }
            if (fresh.isEmpty()) alreadyKnown++
            return fresh
        }

        snapshot.ok.forEach { entry ->
            val fresh = candidates(entry.i, entry.h)
            if (fresh.isNotEmpty()) {
                fresh.forEach { known += it.id }
                okImported++
            }
        }
        snapshot.bad.forEach { entry ->
            val status = statusFromName(entry.s) ?: return@forEach
            val fresh = candidates(entry.i, nameHash(entry.n))
            if (fresh.isNotEmpty()) {
                fresh.forEach { channel ->
                    known += channel.id
                    hints += ChannelHealthRecord(channel.id, status, entry.t, failedDays = 0)
                }
                badImported++
            }
        }
        return SnapshotImportPlan(
            knownIds = known,
            hints = hints,
            summary = SnapshotImportSummary(okImported, badImported, alreadyKnown, unknown, nameMismatch),
        )
    }

    private fun statusName(status: ChannelHealthStatus): String =
        if (status == ChannelHealthStatus.EMPTY) "empty" else "dead"

    private fun statusFromName(name: String): ChannelHealthStatus? = when (name) {
        "empty" -> ChannelHealthStatus.EMPTY
        "dead" -> ChannelHealthStatus.DEAD
        else -> null
    }

    private const val MAX_LABEL = 60
}
