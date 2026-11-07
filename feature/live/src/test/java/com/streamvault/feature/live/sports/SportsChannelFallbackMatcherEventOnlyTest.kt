package com.streamvault.feature.live.sports

import com.streamvault.domain.model.Channel
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsChannelFallbackMatcherEventOnlyTest {
    private val utc = ZoneId.of("UTC")

    @Test
    fun mlsEventOnlyChannelsAreOfferedForAnMlsMatch() {
        val channels = (1L..3L).map { id -> channel(id, "|US-Vip| MLS LIVE $id | Event Only |") }
        val index = SportsChannelIndex.build(channels.map(::entry))

        val matches = SportsChannelFallbackMatcher.findMatches(mlsEvent(), index, zoneId = utc)

        assertEquals(listOf(1L, 2L, 3L), matches.map { it.entry.channel.id })
        assertTrue(matches.all { SportsChannelMatchReason.EVENT_ONLY_LEAGUE in it.reasons })
        assertTrue(matches.all { it.score >= 70 })
    }

    @Test
    fun mlsEventOnlyChannelsAreNotOfferedForAnNbaGame() {
        val index = SportsChannelIndex.build(
            listOf(channel(1, "|US-Vip| MLS LIVE 1 | Event Only |"), channel(2, "|US-Vip| Event Only 2 |")).map(::entry),
        )
        val nba = event(
            title = "Los Angeles Lakers vs Golden State Warriors",
            league = "NBA",
            sport = SportType.BASKETBALL,
            competitors = listOf("Los Angeles Lakers", "Golden State Warriors"),
        )

        assertTrue(SportsChannelFallbackMatcher.findMatches(nba, index, zoneId = utc).isEmpty())
    }

    @Test
    fun leagueChannelWithoutEventOnlyMarkerIsStillNotACandidate() {
        val index = SportsChannelIndex.build(listOf(entry(channel(1, "MLS Season Pass HD"))))

        assertTrue(SportsChannelFallbackMatcher.findMatches(mlsEvent(), index, zoneId = utc).isEmpty())
    }

    @Test
    fun eventOnlyChannelsRankBelowTeamPairChannelsAndRespectTheLimit() {
        val eventOnly = (1L..10L).map { id -> channel(id, "|US-Vip| MLS LIVE $id | Event Only |") }
        val teamPair = channel(99, "MLS | Inter Miami vs San Diego FC")
        val index = SportsChannelIndex.build((eventOnly + teamPair).map(::entry))

        val matches = SportsChannelFallbackMatcher.findMatches(mlsEvent(), index, zoneId = utc)

        assertEquals(8, matches.size)
        assertEquals(99L, matches.first().entry.channel.id)
        assertTrue(SportsChannelMatchReason.TEAM_PAIR in matches.first().reasons)
        assertTrue(SportsChannelMatchReason.EVENT_ONLY_LEAGUE !in matches.first().reasons)
        assertTrue(matches.drop(1).all { SportsChannelMatchReason.EVENT_ONLY_LEAGUE in it.reasons })
        assertTrue(matches.zipWithNext().all { (first, second) -> first.score >= second.score })
    }

    @Test
    fun eventWithoutLeagueGetsNoEventOnlyCandidates() {
        val index = SportsChannelIndex.build(listOf(entry(channel(1, "|US-Vip| MLS LIVE 1 | Event Only |"))))
        val noLeague = mlsEvent().copy(league = null)

        assertTrue(SportsChannelFallbackMatcher.findMatches(noLeague, index, zoneId = utc).isEmpty())
    }

    private fun mlsEvent() = event(
        title = "Inter Miami vs San Diego FC",
        league = "Major League Soccer",
        sport = SportType.FOOTBALL,
        competitors = listOf("Inter Miami", "San Diego FC"),
    )

    private fun event(
        title: String,
        league: String?,
        sport: SportType,
        competitors: List<String>,
    ): SportsEvent {
        val start = LocalDateTime.of(2026, 10, 18, 23, 0)
        return SportsEvent(
            id = "event-us",
            title = title,
            league = league,
            categoryLabel = null,
            description = "",
            sport = sport,
            startTime = start.toInstant(ZoneOffset.UTC).toEpochMilli(),
            endTime = start.plusHours(2).toInstant(ZoneOffset.UTC).toEpochMilli(),
            broadcasts = emptyList(),
            confidence = 0.95f,
            competitors = competitors.map(::SportsCompetitor),
            session = null,
            eventType = SportsEventType.SCHEDULED_EVENT,
            rawProgrammeRefs = emptyList(),
        )
    }

    private fun entry(channel: Channel) = SportsChannelIndexEntry(channel, null)

    private fun channel(id: Long, name: String) = Channel(
        id = id,
        name = name,
        canonicalName = name,
        streamUrl = "https://example.invalid/live/$id.m3u8",
        epgChannelId = "epg-$id",
        providerId = 1,
    )
}
