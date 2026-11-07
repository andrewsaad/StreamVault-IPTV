package com.streamvault.feature.live.sports

import com.streamvault.domain.model.Channel
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsChannelFallbackMatcherAlAhlyTest {
    private val utc = ZoneId.of("UTC")

    @Test
    fun alAhlyMatchOffersTheAlAhlyClubChannels() {
        val hd = channel(1, "Al Ahly [HD]")
        val sd = channel(2, "Al Ahly [SD]")
        val unrelated = channel(3, "Zamalek News")
        val index = SportsChannelIndex.build(listOf(hd, sd, unrelated).map(::entry))

        val matches = SportsChannelFallbackMatcher.findMatches(alAhlyEvent(), index, zoneId = utc)

        assertEquals(setOf(1L, 2L), matches.map { it.entry.channel.id }.toSet())
        assertTrue(matches.all { it.score >= 70 })
    }

    @Test
    fun alAhlySpellingVariantsResolveToTheSameTeam() {
        val canonical = SportsAliasRegistry.normalizeTeamName("Al Ahly")
        listOf("Al Ahly SC", "Al-Ahly", "AL AHLY").forEach { name ->
            assertEquals(canonical, SportsAliasRegistry.normalizeTeamName(name))
        }
    }

    @Test
    fun clubChannelIsNotOfferedForAnotherTeamsMatch() {
        val index = SportsChannelIndex.build(listOf(entry(channel(1, "Al Ahly [HD]"))))
        val otherMatch = event(competitors = listOf("Zamalek", "Pyramids"), title = "Zamalek vs Pyramids")

        assertTrue(SportsChannelFallbackMatcher.findMatches(otherMatch, index, zoneId = utc).isEmpty())
    }

    @Test
    fun teamPairChannelStillOutranksClubChannel() {
        val pair = channel(1, "Egypt | Al Ahly vs Zamalek")
        val club = channel(2, "Al Ahly [HD]")
        val index = SportsChannelIndex.build(listOf(club, pair).map(::entry))

        val matches = SportsChannelFallbackMatcher.findMatches(alAhlyEvent(), index, zoneId = utc)

        assertEquals(listOf(1L, 2L), matches.map { it.entry.channel.id })
    }

    private fun alAhlyEvent() = event(competitors = listOf("Al Ahly", "Zamalek"), title = "Al Ahly vs Zamalek")

    private fun event(competitors: List<String>, title: String): SportsEvent {
        val start = LocalDateTime.of(2026, 10, 18, 18, 0)
        return SportsEvent(
            id = "event-al-ahly",
            title = title,
            league = "Egyptian Premier League",
            categoryLabel = null,
            description = "",
            sport = SportType.FOOTBALL,
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
