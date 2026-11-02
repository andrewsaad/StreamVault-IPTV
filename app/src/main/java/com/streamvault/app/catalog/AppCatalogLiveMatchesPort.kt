package com.streamvault.app.catalog

import com.streamvault.feature.catalog.api.CatalogLiveMatch
import com.streamvault.feature.catalog.api.CatalogLiveMatchesPort
import com.streamvault.feature.live.sports.HomeLiveMatch
import com.streamvault.feature.live.sports.HomeLiveMatchesSource
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppCatalogLiveMatchesPort internal constructor(
    private val load: suspend (List<Long>, Long) -> List<HomeLiveMatch>,
) : CatalogLiveMatchesPort {
    @Inject
    constructor(source: HomeLiveMatchesSource) : this(source::load)

    private var lastSource: List<HomeLiveMatch>? = null
    private var lastMapped: List<CatalogLiveMatch> = emptyList()

    /** The source returns the same list instance when nothing changed; so does this port. */
    override suspend fun liveMatches(providerIds: List<Long>, nowMs: Long): List<CatalogLiveMatch> {
        val matches = load(providerIds, nowMs)
        synchronized(this) {
            if (matches === lastSource) return lastMapped
        }
        val mapped = matches.map { match ->
            CatalogLiveMatch(
                channel = match.channel,
                title = match.title,
                league = match.league,
                startTimeMs = match.startTimeMs,
                endTimeMs = match.endTimeMs,
                commentator = match.commentator,
            )
        }
        synchronized(this) {
            lastSource = matches
            lastMapped = mapped
        }
        return mapped
    }
}
