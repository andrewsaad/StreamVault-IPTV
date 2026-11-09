package com.streamvault.data.health

import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.ChannelHealthRecord
import com.streamvault.domain.model.ChannelHealthStatus
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One probe result waiting to be folded into a channel's record. */
internal data class ChannelProbeOutcome(
    val channelId: Long,
    val status: ChannelHealthStatus,
    val checkedAt: Long,
)

/** Where a scan that started at [startedAt] got to: channels up to [afterChannelId] (ids ascending) are done. */
internal data class ChannelHealthResume(val startedAt: Long, val afterChannelId: Long)

/** Only channels whose latest answer was EMPTY or DEAD have a record; an OK answer removes it. */
internal data class ChannelHealthDocument(
    val records: Map<Long, ChannelHealthRecord> = emptyMap(),
    val lastFinishedAt: Long? = null,
    val resume: ChannelHealthResume? = null,
)

/** The failed-days rules; pure so the day counting can be tested with fixed times. */
internal object ChannelHealthTracker {
    /** Returns the record after [status], or [previous] when the probe says nothing (UNCLEAR). */
    fun apply(
        previous: ChannelHealthRecord?,
        channelId: Long,
        status: ChannelHealthStatus,
        checkedAt: Long,
        zone: ZoneId,
    ): ChannelHealthRecord? = when (status) {
        ChannelHealthStatus.UNCLEAR -> previous
        ChannelHealthStatus.OK -> ChannelHealthRecord(channelId, status, checkedAt, failedDays = 0)
        ChannelHealthStatus.EMPTY, ChannelHealthStatus.DEAD -> {
            val failedDays = when {
                previous == null || previous.failedDays <= 0 -> 1
                localDay(checkedAt, zone) > localDay(previous.checkedAt, zone) -> previous.failedDays + 1
                else -> previous.failedDays
            }
            ChannelHealthRecord(channelId, status, checkedAt, failedDays)
        }
    }

    private fun localDay(millis: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay()
}

/**
 * Probe results per provider, kept as one JSON string in the existing preferences DataStore
 * (no database table). Statuses are written as explicit strings so a release build that renames
 * the enum still reads what an earlier build saved.
 */
@Singleton
class ChannelHealthResults internal constructor(
    private val preferences: PreferencesRepository,
    private val clock: Clock,
) {
    @Inject
    constructor(preferences: PreferencesRepository) : this(preferences, Clock.systemDefaultZone())

    private val writeLock = Mutex()

    internal fun now(): Long = clock.millis()

    internal fun observe(providerId: Long): Flow<ChannelHealthDocument> =
        preferences.getChannelHealthResults(providerId).distinctUntilChanged().map(::decode)

    internal suspend fun read(providerId: Long): ChannelHealthDocument = observe(providerId).first()

    internal suspend fun record(
        providerId: Long,
        outcomes: List<ChannelProbeOutcome>,
        resume: ChannelHealthResume? = null,
    ) {
        if (outcomes.isEmpty() && resume == null) return
        update(providerId) { document ->
            val records = document.records.toMutableMap()
            outcomes.forEach { outcome ->
                if (outcome.status == ChannelHealthStatus.OK) {
                    // Same as failedDays back to 0: a channel that works needs no record.
                    records.remove(outcome.channelId)
                    return@forEach
                }
                ChannelHealthTracker.apply(
                    previous = records[outcome.channelId],
                    channelId = outcome.channelId,
                    status = outcome.status,
                    checkedAt = outcome.checkedAt,
                    zone = clock.zone,
                )?.let { records[outcome.channelId] = it }
            }
            document.copy(records = records, resume = resume ?: document.resume)
        }
    }

    internal suspend fun markFinished(providerId: Long) {
        update(providerId) { it.copy(lastFinishedAt = clock.millis(), resume = null) }
    }

    internal suspend fun clear(providerId: Long) {
        writeLock.withLock { preferences.setChannelHealthResults(providerId, null) }
    }

    private suspend fun update(providerId: Long, transform: (ChannelHealthDocument) -> ChannelHealthDocument) {
        writeLock.withLock {
            val updated = transform(read(providerId))
            preferences.setChannelHealthResults(providerId, encode(updated))
        }
    }

    @Serializable
    private class StoredRecord(val id: Long, val status: String, val checkedAt: Long, val failedDays: Int)

    @Serializable
    private class StoredDocument(
        val lastFinishedAt: Long? = null,
        val records: List<StoredRecord> = emptyList(),
        val resumeStartedAt: Long? = null,
        val resumeAfterId: Long? = null,
    )

    private fun encode(document: ChannelHealthDocument): String = json.encodeToString(
        StoredDocument.serializer(),
        StoredDocument(
            lastFinishedAt = document.lastFinishedAt,
            resumeStartedAt = document.resume?.startedAt,
            resumeAfterId = document.resume?.afterChannelId,
            records = document.records.values.map {
                StoredRecord(it.channelId, statusToStored(it.status), it.checkedAt, it.failedDays)
            },
        ),
    )

    private fun decode(stored: String?): ChannelHealthDocument {
        if (stored.isNullOrBlank()) return ChannelHealthDocument()
        val document = runCatching { json.decodeFromString(StoredDocument.serializer(), stored) }
            .getOrNull() ?: return ChannelHealthDocument()
        val records = document.records.mapNotNull { record ->
            val status = statusFromStored(record.status) ?: return@mapNotNull null
            if (status == ChannelHealthStatus.OK) return@mapNotNull null // older builds stored these
            ChannelHealthRecord(record.id, status, record.checkedAt, record.failedDays)
        }.associateBy { it.channelId }
        val resume = if (document.resumeStartedAt != null && document.resumeAfterId != null) {
            ChannelHealthResume(document.resumeStartedAt, document.resumeAfterId)
        } else {
            null
        }
        return ChannelHealthDocument(records, document.lastFinishedAt, resume)
    }

    private fun statusToStored(status: ChannelHealthStatus): String = when (status) {
        ChannelHealthStatus.OK -> "ok"
        ChannelHealthStatus.EMPTY -> "empty"
        ChannelHealthStatus.DEAD -> "dead"
        ChannelHealthStatus.UNCLEAR -> "unclear"
    }

    private fun statusFromStored(stored: String): ChannelHealthStatus? = when (stored) {
        "ok" -> ChannelHealthStatus.OK
        "empty" -> ChannelHealthStatus.EMPTY
        "dead" -> ChannelHealthStatus.DEAD
        "unclear" -> ChannelHealthStatus.UNCLEAR
        else -> null
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
