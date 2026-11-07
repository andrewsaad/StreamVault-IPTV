package com.streamvault.feature.live.sports

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SportsMetadataRegistryUsLeaguesTest {
    @Test
    fun resolvesMlsNflAndMlbLeagues() {
        val expected = listOf(
            "MLS" to ("Major League Soccer" to SportType.FOOTBALL),
            "Major League Soccer" to ("Major League Soccer" to SportType.FOOTBALL),
            "NFL" to ("NFL" to SportType.AMERICAN_FOOTBALL),
            "MLB" to ("MLB" to SportType.BASEBALL),
        )
        expected.forEach { (text, target) ->
            val league = SportsLeagueRegistry.detect(text)
            assertThat(league?.displayName).isEqualTo(target.first)
            assertThat(league?.sport).isEqualTo(target.second)
            assertThat(SportsLeagueRegistry.aliasesFor(target.first)).contains(SportsTextNormalizer.normalize(text))
        }
    }

    @Test
    fun resolvesLeaguesFromProviderChannelStyleText() {
        assertThat(SportsLeagueRegistry.detect("|US-Vip| MLS LIVE 1 | Event Only |")?.displayName)
            .isEqualTo("Major League Soccer")
        assertThat(SportsLeagueRegistry.detect("US: NFL RedZone")?.displayName).isEqualTo("NFL")
    }

    @Test
    fun resolvesInterMiamiAndSanDiegoFcSpellings() {
        assertThat(SportsAliasRegistry.normalizeTeamName("Inter Miami CF"))
            .isEqualTo(SportsAliasRegistry.normalizeTeamName("Inter Miami"))
        assertThat(SportsAliasRegistry.normalizeTeamName("San Diego FC"))
            .isEqualTo(SportsAliasRegistry.normalizeTeamName("San Diego"))
        assertThat(SportsAliasRegistry.aliasesForCanonical("Inter Miami")).contains("inter miami")
        assertThat(SportsAliasRegistry.aliasesForCanonical("San Diego FC")).contains("san diego")
    }

    @Test
    fun resolvesPadresSpellingsToOneTeamSeparateFromSanDiegoFc() {
        val padres = SportsAliasRegistry.normalizeTeamName("Padres")
        assertThat(SportsAliasRegistry.normalizeTeamName("San Diego Padres")).isEqualTo(padres)
        assertThat(SportsAliasRegistry.aliasesForCanonical("San Diego Padres")).contains("padres")
        assertThat(SportsAliasRegistry.aliasesForCanonical("Padres")).contains("san diego padres")
        assertThat(padres).isNotEqualTo(SportsAliasRegistry.normalizeTeamName("San Diego FC"))
    }
}
