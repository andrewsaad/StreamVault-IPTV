package com.streamvault.feature.live.sports

import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.EpgRepository
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A football match airing now or starting soon, for the home "Live & starting soon" row. */
data class HomeLiveMatch(
    val channel: Channel,
    val title: String,
    val league: String?,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val commentator: String?,
)

/** One classified football programme on one channel; built once per guide load, reused every tick. */
internal data class HomeMatchCandidate(
    val channel: Channel,
    val title: String,
    val league: String?,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val isBein: Boolean,
    val beinNumber: Int?,
    val eventKey: String,
)

internal object HomeLiveMatchSelector {
    const val SOON_WINDOW_MS: Long = 30L * 60_000L
    const val LOOKBACK_MS: Long = 3L * 60L * 60_000L

    private val nonWord = Regex("[^\\p{L}\\p{N}]+")

    fun isBeinChannel(name: String): Boolean = name.contains("bein", ignoreCase = true)

    /** Only real competitive football events count: no studio shows, replays, highlights or other sports. */
    fun candidateOf(
        programTitle: String,
        startTimeMs: Long,
        endTimeMs: Long,
        channel: Channel,
        classification: SportsClassification,
    ): HomeMatchCandidate? {
        if (!classification.isCompetitiveEvent || classification.sport != SportType.FOOTBALL) return null
        if (endTimeMs <= startTimeMs) return null
        val title = classification.displayTitle.ifBlank { programTitle.trim() }
        if (title.isBlank()) return null
        return HomeMatchCandidate(
            channel = channel,
            title = title,
            league = classification.league,
            startTimeMs = startTimeMs,
            endTimeMs = endTimeMs,
            isBein = isBeinChannel(channel.name),
            beinNumber = SportsCommentatorMatcher.beinChannelNumber(channel.name),
            eventKey = title.lowercase().replace(nonWord, " ").trim() + "@" + startTimeMs / 60_000L,
        )
    }

    private val order = compareByDescending<Pair<HomeMatchCandidate, Boolean>> { it.first.isBein }
        .thenByDescending { it.second }
        .thenBy { if (it.first.beinNumber != null) 0 else 1 }
        .thenBy { it.first.startTimeMs }
        .thenBy { it.first.channel.number }
        .thenBy { it.first.channel.id }

    /**
     * Matches live at [nowMs] (start <= now < end) or starting within 30 minutes (start <= now+30m),
     * beIN channels first, then live before upcoming, Arabic beIN feeds before regional ones,
     * earliest first. A match carried by several
     * channels (HD/FHD/backup feeds) is shown once, on the best-ranked channel.
     */
    fun select(candidates: List<HomeMatchCandidate>, nowMs: Long, limit: Int): List<HomeMatchCandidate> {
        if (candidates.isEmpty() || limit <= 0) return emptyList()
        val soonLimit = nowMs + SOON_WINDOW_MS
        val oldestStart = nowMs - LOOKBACK_MS
        val seen = HashSet<String>()
        return candidates.asSequence()
            .filter { it.endTimeMs > nowMs && it.startTimeMs <= soonLimit && it.startTimeMs >= oldestStart }
            .map { it to (it.startTimeMs <= nowMs) }
            .sortedWith(order)
            .map { it.first }
            .filter { seen.add(it.eventKey) }
            .take(limit)
            .toList()
    }
}

/** Loads classified football programmes for the sports/beIN channels of one provider. */
internal fun interface HomeMatchCandidateLoader {
    suspend fun load(providerId: Long, windowStartMs: Long, windowEndMs: Long): List<HomeMatchCandidate>
}

internal class RepositoryHomeMatchCandidateLoader(
    private val channelRepository: ChannelRepository,
    private val epgRepository: EpgRepository,
    private val preferencesRepository: PreferencesRepository,
) : HomeMatchCandidateLoader {
    override suspend fun load(providerId: Long, windowStartMs: Long, windowEndMs: Long): List<HomeMatchCandidate> {
        val hiddenCategoryIds = preferencesRepository.getHiddenCategoryIds(providerId, ContentType.LIVE).first()
        val channels = channelRepository.getSportsChannelCandidates(providerId, MAX_CHANNELS)
            .filter { channel -> channel.categoryId == null || channel.categoryId !in hiddenCategoryIds }
        if (channels.isEmpty()) return emptyList()
        val programsByKey = epgRepository.getResolvedProgramsForChannels(
            providerId = providerId,
            channelIds = channels.map(Channel::id),
            startTime = windowStartMs,
            endTime = windowEndMs,
        )
        if (programsByKey.isEmpty()) return emptyList()
        return withContext(Dispatchers.Default) {
            buildList {
                channels.forEach { channel ->
                    val key = channel.streamId.takeIf { it > 0L }?.toString()
                        ?: channel.epgChannelId?.trim()?.takeIf(String::isNotEmpty)
                        ?: return@forEach
                    val programs = programsByKey[key].orEmpty()
                    if (programs.isEmpty()) return@forEach
                    val context = SportsProgrammeClassifier.prepareChannel(channel)
                    programs.forEach { program ->
                        if (program.endTime <= windowStartMs || program.startTime >= windowEndMs) return@forEach
                        val classification = SportsProgrammeClassifier.classify(program, context)
                        HomeLiveMatchSelector.candidateOf(
                            programTitle = program.title,
                            startTimeMs = program.startTime,
                            endTimeMs = program.endTime,
                            channel = channel,
                            classification = classification,
                        )?.let(::add)
                    }
                }
            }
        }
    }

    private companion object {
        /** beIN-first; a typical Arabic list carries ~100-200 beIN/sport feeds. */
        const val MAX_CHANNELS = 300
    }
}

/**
 * Feeds the home row. The guide is read at most every [RELOAD_INTERVAL_MS] (or when the cached
 * window no longer covers "now + 30 min"); each tick in between only re-filters the cached
 * football candidates and returns the previous list instance when nothing changed.
 * Commentators come from [SportsCommentatorRepository]'s cache and its own hourly throttle.
 */
@Singleton
class HomeLiveMatchesSource internal constructor(
    private val loader: HomeMatchCandidateLoader,
    private val commentators: () -> List<SportsCommentatorEntry>,
    private val refreshCommentators: suspend () -> Unit,
) {
    @Inject internal constructor(
        channelRepository: ChannelRepository,
        epgRepository: EpgRepository,
        preferencesRepository: PreferencesRepository,
        commentatorRepository: SportsCommentatorRepository,
    ) : this(
        RepositoryHomeMatchCandidateLoader(channelRepository, epgRepository, preferencesRepository),
        { commentatorRepository.entries.value },
        commentatorRepository::refreshIfStale,
    )

    private class Cache(
        val providerIds: List<Long>,
        val loadedAtMs: Long,
        val windowEndMs: Long,
        val candidates: List<HomeMatchCandidate>,
    )

    private val mutex = Mutex()
    private var cache: Cache? = null
    private var lastCommentators: List<SportsCommentatorEntry>? = null
    private var lastResult: List<HomeLiveMatch> = emptyList()

    /** Never throws (except cancellation); a failed guide read keeps the last row, or yields an empty one. */
    suspend fun load(providerIds: List<Long>, nowMs: Long): List<HomeLiveMatch> = mutex.withLock {
        if (providerIds.isEmpty()) return@withLock emptyList<HomeLiveMatch>().also { lastResult = it }
        val current = cache
        val candidates = if (current == null || current.providerIds != providerIds ||
            nowMs < current.loadedAtMs || nowMs - current.loadedAtMs >= RELOAD_INTERVAL_MS ||
            nowMs + HomeLiveMatchSelector.SOON_WINDOW_MS > current.windowEndMs
        ) {
            val windowEnd = nowMs + HomeLiveMatchSelector.SOON_WINDOW_MS + RELOAD_INTERVAL_MS
            val loaded = try {
                providerIds.flatMap { providerId -> loader.load(providerId, nowMs, windowEnd) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (loaded == null) {
                // F22: a failed read keeps the last good row instead of blanking it, and caches
                // nothing, so the next load tries again.
                current?.takeIf { it.providerIds == providerIds }?.candidates.orEmpty()
            } else {
                cache = Cache(providerIds, nowMs, windowEnd, loaded)
                if (loaded.any { it.beinNumber != null }) {
                    try {
                        refreshCommentators()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Commentators are decoration.
                    }
                }
                loaded
            }
        } else {
            current.candidates
        }

        val selected = HomeLiveMatchSelector.select(candidates, nowMs, MAX_MATCHES)
        val entries = commentators()
        if (entries === lastCommentators && selected.size == lastResult.size &&
            selected.indices.all { index -> sameMatch(selected[index], lastResult[index]) }
        ) {
            return@withLock lastResult
        }
        lastCommentators = entries
        selected.map { candidate ->
            HomeLiveMatch(
                channel = candidate.channel,
                title = candidate.title,
                league = candidate.league,
                startTimeMs = candidate.startTimeMs,
                endTimeMs = candidate.endTimeMs,
                commentator = SportsCommentatorMatcher.commentatorFor(
                    candidate.channel.name,
                    Instant.ofEpochMilli(candidate.startTimeMs),
                    entries,
                ),
            )
        }.also { lastResult = it }
    }

    private fun sameMatch(candidate: HomeMatchCandidate, match: HomeLiveMatch): Boolean =
        candidate.channel.id == match.channel.id &&
            candidate.startTimeMs == match.startTimeMs &&
            candidate.endTimeMs == match.endTimeMs &&
            candidate.title == match.title

    internal companion object {
        const val RELOAD_INTERVAL_MS: Long = 10L * 60_000L
        const val MAX_MATCHES = 20
    }
}
