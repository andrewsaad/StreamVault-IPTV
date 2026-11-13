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

/**
 * Where a scan that started at [startedAt] got to: channels up to [afterChannelId] (ids ascending) are done.
 * [retryIds] are the channels whose answer was UNCLEAR (provider trouble) and get one more try once the
 * main pass is over; [retryAfterChannelId] is null during the main pass and, once the retry pass has
 * started, the last retried id (0 before the first). [serverErrorIds] are the [retryIds] whose UNCLEAR
 * answer was an HTTP 5xx; a second 5xx on the retry makes such a channel count as EMPTY.
 * [refused] are the retried channels the provider refused with a 5xx again (decided only at the end of the
 * retry pass), and [retryOk] and [retryAttempts] the retry-pass counts so far, so a restart neither probes
 * those channels again nor loses the evidence [shouldStoreRefusals] needs. All three default to empty/0, which
 * is what state saved by a build before they existed reads as.
 */
internal data class ChannelHealthResume(
    val startedAt: Long,
    val afterChannelId: Long,
    val retryIds: List<Long> = emptyList(),
    val retryAfterChannelId: Long? = null,
    val serverErrorIds: List<Long> = emptyList(),
    val refused: List<ChannelRefusal> = emptyList(),
    val retryOk: Int = 0,
    val retryAttempts: Int = 0,
)

/** A retried channel refused with a 5xx again; [inStreak] when it still counts toward the mass-failure streak. */
internal data class ChannelRefusal(val channelId: Long, val checkedAt: Long, val inStreak: Boolean = false)

/**
 * Only channels whose latest answer was EMPTY or DEAD have a record; an OK answer removes it.
 * [knownIds] are the channels that have had any settled answer (OK, EMPTY or DEAD), so a channel the
 * provider added later, or one that only ever answered UNCLEAR, is not in it. Empty on results saved
 * by a build before it existed.
 */
internal data class ChannelHealthDocument(
    val records: Map<Long, ChannelHealthRecord> = emptyMap(),
    val lastFinishedAt: Long? = null,
    val resume: ChannelHealthResume? = null,
    val knownIds: Set<Long> = emptySet(),
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
            val known = document.knownIds.toMutableSet()
            outcomes.forEach { outcome ->
                if (outcome.status != ChannelHealthStatus.UNCLEAR) known += outcome.channelId
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
            document.copy(records = records, resume = resume ?: document.resume, knownIds = known)
        }
    }

    /**
     * Results saved before [ChannelHealthDocument.knownIds] existed have a finished scan but no known
     * channels. The channels listed now (minus the failed ones) are taken as that scan's baseline, so
     * "scan new channels" does not start by rescanning everything. Channels the provider added between
     * that scan and now are wrongly assumed fine; they get checked by the next full scan.
     */
    internal suspend fun ensureBaseline(providerId: Long, listedIds: Set<Long>) {
        update(providerId) { document ->
            if (document.knownIds.isNotEmpty() || document.lastFinishedAt == null) {
                document
            } else {
                document.copy(knownIds = listedIds)
            }
        }
    }

    /** Folds an import in: the channels become known and failed ones become hints (see [SnapshotImportPlan]). */
    internal suspend fun applyImport(providerId: Long, plan: SnapshotImportPlan) {
        if (plan.knownIds.isEmpty()) return
        update(providerId) { document ->
            val records = document.records.toMutableMap()
            plan.hints.forEach { hint -> records.putIfAbsent(hint.channelId, hint) }
            document.copy(records = records, knownIds = document.knownIds + plan.knownIds)
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
    private class StoredRefusal(val id: Long, val checkedAt: Long, val inStreak: Boolean = false)

    @Serializable
    private class StoredDocument(
        val lastFinishedAt: Long? = null,
        val records: List<StoredRecord> = emptyList(),
        val resumeStartedAt: Long? = null,
        val resumeAfterId: Long? = null,
        val resumeRetryIds: List<Long> = emptyList(),
        val resumeRetryAfterId: Long? = null,
        val resumeServerErrorIds: List<Long> = emptyList(),
        // Added later: absent in state saved by older builds, which then reads as no refusals and zero counts.
        val resumeRefused: List<StoredRefusal> = emptyList(),
        val resumeRetryOk: Int = 0,
        val resumeRetryAttempts: Int = 0,
        // Added later: absent in state saved by older builds, which then reads as no known channels.
        val knownIds: List<Long> = emptyList(),
    )

    private fun encode(document: ChannelHealthDocument): String = json.encodeToString(
        StoredDocument.serializer(),
        StoredDocument(
            lastFinishedAt = document.lastFinishedAt,
            resumeStartedAt = document.resume?.startedAt,
            resumeAfterId = document.resume?.afterChannelId,
            resumeRetryIds = document.resume?.retryIds.orEmpty(),
            resumeRetryAfterId = document.resume?.retryAfterChannelId,
            resumeServerErrorIds = document.resume?.serverErrorIds.orEmpty(),
            resumeRefused = document.resume?.refused.orEmpty().map { StoredRefusal(it.channelId, it.checkedAt, it.inStreak) },
            resumeRetryOk = document.resume?.retryOk ?: 0,
            resumeRetryAttempts = document.resume?.retryAttempts ?: 0,
            knownIds = document.knownIds.sorted(),
            records =document.records.values.map {
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
            ChannelHealthResume(
                document.resumeStartedAt,
                document.resumeAfterId,
                document.resumeRetryIds,
                document.resumeRetryAfterId,
                document.resumeServerErrorIds,
                document.resumeRefused.map { ChannelRefusal(it.id, it.checkedAt, it.inStreak) },
                document.resumeRetryOk,
                document.resumeRetryAttempts,
            )
        } else {
            null
        }
        return ChannelHealthDocument(records, document.lastFinishedAt, resume, document.knownIds.toSet())
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
