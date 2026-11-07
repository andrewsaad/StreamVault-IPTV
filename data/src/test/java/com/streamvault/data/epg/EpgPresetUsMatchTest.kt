package com.streamvault.data.epg

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.epg.EpgResolutionHarness.channel
import com.streamvault.data.epg.EpgResolutionHarness.resolve
import com.streamvault.domain.model.EpgPresetCatalog
import com.streamvault.domain.model.EpgSourceType
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** User story 3: the US preset links the tagged USA channel names (SC-002 regression floor). */
class EpgPresetUsMatchTest {

    companion object {
        private const val US2_SOURCE = 40L
    }

    private suspend fun resolveSample() = run {
        val names = EpgPresetFixtures.values("us_names.txt")
        val guide = EpgPresetFixtures.guideChannels("us2_ids.txt", US2_SOURCE)
        names to resolve(names.mapIndexed { index, name -> channel(index + 1L, name) }, listOf(US2_SOURCE to guide)).second
    }

    @Test
    fun `usPreset_usesUS2AndUsSports`() {
        val us = EpgPresetCatalog.byId(EpgPresetCatalog.US)!!
        assertThat(us.sources.map { it.key }).containsExactly("US2", "US_SPORTS1")
    }

    @Test
    fun `atLeastHalfOfTheSampledUsaNames_match`() = runTest {
        val (names, mappings) = resolveSample()

        val matched = mappings.count { it.sourceType == EpgSourceType.EXTERNAL.name }
        assertThat(matched * 2).isAtLeast(names.size)
    }

    @Test
    fun `nflNetworkHdUs_matchesTheNflNetworkEntry`() = runTest {
        val (names, mappings) = resolveSample()

        val id = names.indexOf("NFL Network HD [US]") + 1L
        assertThat(mappings.single { it.providerChannelId == id }.xmltvChannelId).isEqualTo("NFL.Network.HD.us2")
    }

    @Test
    fun `localAffiliates_areNotForcedOntoTheNationalFeed`() = runTest {
        val (names, mappings) = resolveSample()

        val id = names.indexOf("ABC 7 New York [US]") + 1L
        assertThat(mappings.single { it.providerChannelId == id }.sourceType).isEqualTo(EpgSourceType.NONE.name)
    }
}
