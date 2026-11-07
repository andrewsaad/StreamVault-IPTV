package com.streamvault.data.epg

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.epg.EpgResolutionHarness.channel
import com.streamvault.data.epg.EpgResolutionHarness.guide
import com.streamvault.data.epg.EpgResolutionHarness.resolve
import com.streamvault.domain.model.EpgMatchType
import com.streamvault.domain.model.EpgSourceType
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** FR-011, FR-022, SC-003: turning a preset on never changes a match that already works. */
class EpgResolutionEnginePresetTierTest {

    private companion object {
        const val HAND_ADDED = 10L
        const val PRESET = 20L
    }

    @Test
    fun `providerNative_beatsPresetExactId`() = runTest {
        val (_, mappings) = resolve(
            channels = listOf(channel(1, "Fox", epgChannelId = "fox.native")),
            guideBySource = listOf(PRESET to listOf(guide(PRESET, "fox.native", "Other Name"))),
            presetSourceIds = setOf(PRESET),
            providerNativeChannelIds = listOf("fox.native"),
        )

        assertThat(mappings.single().sourceType).isEqualTo(EpgSourceType.PROVIDER.name)
    }

    @Test
    fun `providerNative_beatsPresetExactName`() = runTest {
        val (_, mappings) = resolve(
            channels = listOf(channel(1, "Fox", epgChannelId = "fox.native")),
            guideBySource = listOf(PRESET to listOf(guide(PRESET, "Fox.us2", "Fox"))),
            presetSourceIds = setOf(PRESET),
            providerNativeChannelIds = listOf("fox.native"),
        )

        assertThat(mappings.single().sourceType).isEqualTo(EpgSourceType.PROVIDER.name)
    }

    @Test
    fun `providerNative_beatsHandAddedLoose`() = runTest {
        val (summary, mappings) = resolve(
            channels = listOf(channel(1, "Fox [US]", epgChannelId = "fox.native")),
            guideBySource = listOf(HAND_ADDED to listOf(guide(HAND_ADDED, "Fox.us2", "Fox"))),
            providerNativeChannelIds = listOf("fox.native"),
        )

        assertThat(mappings.single().sourceType).isEqualTo(EpgSourceType.PROVIDER.name)
        assertThat(summary.looseNameMatches).isEqualTo(0)
    }

    @Test
    fun `handAddedExactId_stillBeatsProviderNative`() = runTest {
        val (_, mappings) = resolve(
            channels = listOf(channel(1, "Fox", epgChannelId = "fox.native")),
            guideBySource = listOf(HAND_ADDED to listOf(guide(HAND_ADDED, "fox.native", "Other Name"))),
            providerNativeChannelIds = listOf("fox.native"),
        )

        assertThat(mappings.single().epgSourceId).isEqualTo(HAND_ADDED)
        assertThat(mappings.single().matchType).isEqualTo(EpgMatchType.EXACT_ID.name)
    }

    @Test
    fun `handAddedExactName_beatsPresetExactId`() = runTest {
        val (_, mappings) = resolve(
            channels = listOf(channel(1, "Fox", epgChannelId = "fox.preset")),
            guideBySource = listOf(
                PRESET to listOf(guide(PRESET, "fox.preset", "Other Name")),
                HAND_ADDED to listOf(guide(HAND_ADDED, "Fox.hand", "Fox")),
            ),
            presetSourceIds = setOf(PRESET),
        )

        assertThat(mappings.single().epgSourceId).isEqualTo(HAND_ADDED)
        assertThat(mappings.single().xmltvChannelId).isEqualTo("Fox.hand")
    }

    @Test
    fun `handAddedLoose_beatsPresetExactName`() = runTest {
        val (_, mappings) = resolve(
            channels = listOf(channel(1, "Fox HD")),
            guideBySource = listOf(
                PRESET to listOf(guide(PRESET, "Fox.HD.preset", "Fox HD")),
                HAND_ADDED to listOf(guide(HAND_ADDED, "Fox.hand", "Fox")),
            ),
            presetSourceIds = setOf(PRESET),
        )

        assertThat(mappings.single().epgSourceId).isEqualTo(HAND_ADDED)
        assertThat(mappings.single().matchType).isEqualTo(EpgMatchType.NORMALIZED_NAME_LOOSE.name)
    }

    @Test
    fun `preset_fillsOtherwiseUnresolvedChannels_byIdNameAndLoose`() = runTest {
        val (summary, mappings) = resolve(
            channels = listOf(
                channel(1, "Anything", epgChannelId = "fox.preset"),
                channel(2, "Nile Sport"),
                channel(3, "beIN Sport 1 H265 SD"),
            ),
            guideBySource = listOf(
                PRESET to listOf(
                    guide(PRESET, "fox.preset", "Fox"),
                    guide(PRESET, "Nile.ae", "Nile Sport"),
                    guide(PRESET, "beIN.1.qa", "beIN SPORTS 1"),
                )
            ),
            presetSourceIds = setOf(PRESET),
        )

        assertThat(mappings.map { it.matchType }).containsExactly(
            EpgMatchType.EXACT_ID.name,
            EpgMatchType.NORMALIZED_NAME.name,
            EpgMatchType.NORMALIZED_NAME_LOOSE.name,
        ).inOrder()
        assertThat(summary.unresolvedChannels).isEqualTo(0)
    }
}
