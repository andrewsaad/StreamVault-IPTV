package com.streamvault.data.epg

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.streamvault.data.epg.EpgResolutionHarness.channel
import com.streamvault.data.epg.EpgResolutionHarness.resolve
import com.streamvault.domain.model.EpgMatchType
import com.streamvault.domain.model.EpgPresetCatalog
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** User story 2: the Egypt preset (AE1) links the Al Ahly and ON Time Sports channels. */
class EpgPresetEgyptMatchTest {

    companion object {
        private const val AE1_SOURCE = 30L
    }

    @Test
    fun `egyptPreset_usesAE1`() {
        val egypt = EpgPresetCatalog.byId(EpgPresetCatalog.EGYPT)!!
        assertThat(egypt.sources.map { it.key }).containsExactly("AE1")
    }

    @Test
    fun `egyptNames_resolveToTheirAE1Entries`() = runTest {
        val guide = EpgPresetFixtures.guideChannels("guide_channel_ids.txt", AE1_SOURCE)
            .filter { it.xmltvChannelId.endsWith(".ae") }
        val expectations = EpgPresetFixtures.expectations("egypt_names.txt")
        val channels = expectations.mapIndexed { index, (name, _) -> channel(index + 1L, name) }

        val (_, mappings) = resolve(channels, listOf(AE1_SOURCE to guide))

        val byChannel = mappings.associateBy { it.providerChannelId }
        expectations.forEachIndexed { index, (name, expected) ->
            assertWithMessage(name).that(byChannel.getValue(index + 1L).xmltvChannelId).isEqualTo(expected)
        }
    }

    @Test
    fun `alAhlyHdAndSd_bothUseTheLooseFallback`() = runTest {
        val guide = EpgPresetFixtures.guideChannels("guide_channel_ids.txt", AE1_SOURCE)
            .filter { it.xmltvChannelId.endsWith(".ae") }

        val (summary, mappings) = resolve(
            listOf(channel(1, "Al Ahly [HD]"), channel(2, "Al Ahly [SD]")),
            listOf(AE1_SOURCE to guide),
        )

        assertThat(mappings.map { it.matchType }.toSet()).containsExactly(EpgMatchType.NORMALIZED_NAME_LOOSE.name)
        assertThat(summary.looseNameMatches).isEqualTo(2)
    }
}
