package com.streamvault.data.epg

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.streamvault.data.epg.EpgResolutionHarness.PROVIDER_ID
import com.streamvault.data.epg.EpgResolutionHarness.channel
import com.streamvault.data.epg.EpgResolutionHarness.guide
import com.streamvault.data.epg.EpgResolutionHarness.resolve
import com.streamvault.data.local.entity.ChannelEpgMappingEntity
import com.streamvault.domain.model.EpgMatchType
import com.streamvault.domain.model.EpgSourceType
import kotlinx.coroutines.test.runTest
import org.junit.Test

class EpgResolutionEngineLooseMatchTest {

    companion object {
        private const val SOURCE_1 = 10L
        private const val SOURCE_2 = 20L
    }

    @Test
    fun `loose_fillsAnUnresolvedChannel_withLooseTypeAndConfidence`() = runTest {
        val (summary, mappings) = resolve(
            channels = listOf(channel(1, "beIN Sport 1 H265 SD")),
            guideBySource = listOf(SOURCE_1 to listOf(guide(SOURCE_1, "beIN.Sports.1.qa", "beIN SPORTS 1"))),
        )

        val mapping = mappings.single()
        assertThat(mapping.sourceType).isEqualTo(EpgSourceType.EXTERNAL.name)
        assertThat(mapping.epgSourceId).isEqualTo(SOURCE_1)
        assertThat(mapping.xmltvChannelId).isEqualTo("beIN.Sports.1.qa")
        assertThat(mapping.matchType).isEqualTo(EpgMatchType.NORMALIZED_NAME_LOOSE.name)
        assertThat(mapping.confidence).isEqualTo(0.6f)
        assertThat(summary.looseNameMatches).isEqualTo(1)
        assertThat(summary.unresolvedChannels).isEqualTo(0)
    }

    @Test
    fun `loose_skipsAnotherCountrysGuide_andTakesTheRightOneFromALaterSource`() = runTest {
        val (_, onlyWrongCountry) = resolve(
            channels = listOf(channel(1, "TR: Fox")),
            guideBySource = listOf(SOURCE_1 to listOf(guide(SOURCE_1, "Fox.us2", "Fox"))),
        )
        val (_, bothCountries) = resolve(
            channels = listOf(channel(1, "TR: Fox")),
            guideBySource = listOf(
                SOURCE_1 to listOf(guide(SOURCE_1, "Fox.us2", "Fox")),
                SOURCE_2 to listOf(guide(SOURCE_2, "Fox.tr", "Fox")),
            ),
        )

        assertThat(onlyWrongCountry.single().xmltvChannelId).isNull()
        assertThat(bothCountries.single().xmltvChannelId).isEqualTo("Fox.tr")
    }

    @Test
    fun `exactId_beatsLoose`() = runTest {
        val (_, mappings) = resolve(
            channels = listOf(channel(1, "Fox [US]", epgChannelId = "fox.east")),
            guideBySource = listOf(
                SOURCE_1 to listOf(
                    guide(SOURCE_1, "Fox.HD.us2", "Fox HD"),
                    guide(SOURCE_1, "fox.east", "FOX East"),
                )
            ),
        )

        assertThat(mappings.single().matchType).isEqualTo(EpgMatchType.EXACT_ID.name)
        assertThat(mappings.single().xmltvChannelId).isEqualTo("fox.east")
    }

    @Test
    fun `exactName_beatsLoose_evenInALowerPrioritySource`() = runTest {
        val (_, mappings) = resolve(
            channels = listOf(channel(1, "Fox HD")),
            guideBySource = listOf(
                SOURCE_1 to listOf(guide(SOURCE_1, "Fox.us2", "Fox [US]")),
                SOURCE_2 to listOf(guide(SOURCE_2, "fox.hd", "Fox HD")),
            ),
        )

        assertThat(mappings.single().matchType).isEqualTo(EpgMatchType.NORMALIZED_NAME.name)
        assertThat(mappings.single().xmltvChannelId).isEqualTo("fox.hd")
    }

    @Test
    fun `manualOverride_beatsLoose`() = runTest {
        val manual = ChannelEpgMappingEntity(
            id = 5,
            providerChannelId = 1,
            providerId = PROVIDER_ID,
            sourceType = EpgSourceType.EXTERNAL.name,
            epgSourceId = SOURCE_1,
            xmltvChannelId = "picked.by.hand",
            matchType = EpgMatchType.MANUAL.name,
            confidence = 1f,
            isManualOverride = true,
            matchedAt = 1L,
            updatedAt = 1L,
        )
        val (_, mappings) = resolve(
            channels = listOf(channel(1, "beIN Sport 1 H265 SD")),
            guideBySource = listOf(SOURCE_1 to listOf(guide(SOURCE_1, "beIN.Sports.1.qa", "beIN SPORTS 1"))),
            existing = listOf(manual),
        )

        assertThat(mappings.single().matchType).isEqualTo(EpgMatchType.MANUAL.name)
        assertThat(mappings.single().xmltvChannelId).isEqualTo("picked.by.hand")
    }

    @Test
    fun `loose_higherPrioritySourceWins`() = runTest {
        val (_, mappings) = resolve(
            channels = listOf(channel(1, "AR: Nile Sport VIP")),
            guideBySource = listOf(
                SOURCE_1 to listOf(guide(SOURCE_1, "Nile.Sport.ae", "Nile Sport")),
                SOURCE_2 to listOf(guide(SOURCE_2, "Nile.Sport.HD.eg", "Nile Sport HD")),
            ),
        )

        assertThat(mappings.single().epgSourceId).isEqualTo(SOURCE_1)
        assertThat(mappings.single().xmltvChannelId).isEqualTo("Nile.Sport.ae")
    }

    @Test
    fun `loose_tieBreak_prefersClosestQualityWords_thenSmallestId_regardlessOfRowOrder`() = runTest {
        val candidates = listOf(
            guide(SOURCE_1, "z.Fox.HD.us2", "Fox HD"),
            guide(SOURCE_1, "b.Fox.us2", "Fox"),
            guide(SOURCE_1, "a.Fox.us2", "FOX"),
        )
        val channels = listOf(channel(1, "Fox [US] HD"), channel(2, "Fox [US]"))

        val (_, forward) = resolve(channels, listOf(SOURCE_1 to candidates))
        val (_, reversed) = resolve(channels, listOf(SOURCE_1 to candidates.reversed()))

        // "HD" on the channel matches the HD entry; no quality word picks the smallest plain id.
        assertThat(forward.map { it.xmltvChannelId }).containsExactly("z.Fox.HD.us2", "a.Fox.us2").inOrder()
        assertThat(reversed.map { it.xmltvChannelId }).isEqualTo(forward.map { it.xmltvChannelId })
    }

    @Test
    fun `loose_neverMatchesATimeShiftChannelToItsBaseChannel`() = runTest {
        val (summary, mappings) = resolve(
            channels = listOf(channel(1, "beIN Sports 2 +1")),
            guideBySource = listOf(SOURCE_1 to listOf(guide(SOURCE_1, "beIN.Sports.2.qa", "beIN SPORTS 2"))),
        )

        assertThat(mappings.single().sourceType).isEqualTo(EpgSourceType.NONE.name)
        assertThat(summary.unresolvedChannels).isEqualTo(1)
    }

    @Test
    fun `fixtures_everyTaggedNameResolvesToItsExpectedEntry`() = runTest {
        val guide = EpgPresetFixtures.guideChannels("guide_channel_ids.txt", SOURCE_1)
        val expectations = EpgPresetFixtures.expectations("provider_channel_names.txt")
        val channels = expectations.mapIndexed { index, (name, _) -> channel(index + 1L, name) }

        val (_, mappings) = resolve(channels, listOf(SOURCE_1 to guide))

        val byChannel = mappings.associateBy { it.providerChannelId }
        expectations.forEachIndexed { index, (name, expected) ->
            assertWithMessage(name).that(byChannel.getValue(index + 1L).xmltvChannelId).isEqualTo(expected)
        }
    }

    @Test
    fun `regression_channelsMatchedBeforeKeepTheSameEntries`() = runTest {
        // SC-003: a fixture of channels that already match by id or exact name.
        val guide = EpgPresetFixtures.guideChannels("guide_channel_ids.txt", SOURCE_1) +
            listOf(guide(SOURCE_1, "Fox.Plain", "Fox"), guide(SOURCE_1, "Nile.Sport.HD.alt", "Nile Sport HD"))
        val channels = listOf(
            channel(1, "Anything", epgChannelId = "beIN.Sports.1.qa"),
            channel(2, "Al Ahly", epgChannelId = "Al.Ahly.TV.HD.ae"),
            channel(3, "beIN SPORTS 2"),
            channel(4, "Fox HD"),
            channel(5, "Fox"),
            channel(6, "Nile Sport HD"),
            channel(7, "USA Network HD"),
        )
        val before = mapOf(
            1L to ("beIN.Sports.1.qa" to EpgMatchType.EXACT_ID),
            2L to ("Al.Ahly.TV.HD.ae" to EpgMatchType.EXACT_ID),
            3L to ("beIN.Sports.2.qa" to EpgMatchType.NORMALIZED_NAME),
            4L to ("Fox.HD.us2" to EpgMatchType.NORMALIZED_NAME),
            5L to ("Fox.Plain" to EpgMatchType.NORMALIZED_NAME),
            6L to ("Nile.Sport.HD.alt" to EpgMatchType.NORMALIZED_NAME),
            7L to ("USA.Network.HD.us2" to EpgMatchType.NORMALIZED_NAME),
        )

        val (summary, mappings) = resolve(channels, listOf(SOURCE_1 to guide))

        val after = mappings.associate { it.providerChannelId to (it.xmltvChannelId to EpgMatchType.valueOf(it.matchType!!)) }
        assertThat(after).isEqualTo(before)
        assertThat(summary.looseNameMatches).isEqualTo(0)
    }
}
