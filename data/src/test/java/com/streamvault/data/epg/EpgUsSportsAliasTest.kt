package com.streamvault.data.epg

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.streamvault.data.epg.EpgResolutionHarness.channel
import com.streamvault.data.epg.EpgResolutionHarness.guide
import com.streamvault.data.epg.EpgResolutionHarness.resolve
import com.streamvault.data.local.entity.EpgChannelEntity
import com.streamvault.domain.model.EpgMatchType
import kotlinx.coroutines.test.runTest
import org.junit.Test

class EpgUsSportsAliasTest {

    private val source = 10L

    private val guideEntries = listOf(
        guide(source, "ESPN.HD.us2", "ESPN HD"),
        guide(source, "ESPN2.HD.us2", "ESPN2 HD"),
        guide(source, "ESPNU.HD.us2", "ESPNU HD"),
        guide(source, "ESPNEWS.HD.us2", "ESPNEWS HD"),
        guide(source, "ESPN.Deportes.us2", "ESPN Deportes"),
        guide(source, "FS1.HD.us2", "FS1 HD"),
        guide(source, "FS2.HD.us2", "FS2 HD"),
        guide(source, "Golf.Channel.HD.us2", "Golf Channel HD"),
        guide(source, "Fox.Soccer.Plus.us2", "Fox Soccer Plus"),
    )

    private fun only(id: String) = guideEntries.filter { it.xmltvChannelId == id }

    private suspend fun matchOf(name: String, guideList: List<EpgChannelEntity> = guideEntries): String? {
        val (_, mappings) = resolve(
            channels = listOf(channel(1, name)),
            guideBySource = listOf(source to guideList),
        )
        return mappings.singleOrNull()?.xmltvChannelId
    }

    @Test
    fun `listedProviderNames_matchTheirGuideEntries`() = runTest {
        val expected = mapOf(
            "[US]ESPN_1HD" to "ESPN.HD.us2",
            "[US]ESPN_2HD" to "ESPN2.HD.us2",
            "Espn News [US]" to "ESPNEWS.HD.us2",
            "FOX Sport 1 [US]" to "FS1.HD.us2",
            "FOX SPORT 1 [US]" to "FS1.HD.us2",
            "Fox Sports 2 [US]" to "FS2.HD.us2",
            "US: FOX Sport 2" to "FS2.HD.us2",
            "NBC Golf Channel[US]" to "Golf.Channel.HD.us2",
            "ESPN U [US]" to "ESPNU.HD.us2",
        )
        expected.forEach { (name, id) -> assertWithMessage(name).that(matchOf(name)).isEqualTo(id) }
    }

    @Test
    fun `aliasMatch_isLooseType`() = runTest {
        val (_, mappings) = resolve(
            channels = listOf(channel(1, "FOX Sport 1 [US]")),
            guideBySource = listOf(source to guideEntries),
        )
        assertThat(mappings.single().matchType).isEqualTo(EpgMatchType.NORMALIZED_NAME_LOOSE.name)
    }

    @Test
    fun `fs1_variants_shareOneKey`() {
        val keys = setOf("FS1 Fox Sports 1 HD", "FS1 HD", "FOX Sport 1", "Fox Sports 1 [US]")
            .map { EpgNameNormalizer.normalizeLoose(it) }.toSet()
        assertThat(keys).hasSize(1)
    }

    @Test
    fun `espnFamily_staysDistinct`() = runTest {
        assertThat(matchOf("ESPN [US]")).isEqualTo("ESPN.HD.us2")
        assertThat(matchOf("ESPN2 [US]")).isEqualTo("ESPN2.HD.us2")
        assertThat(matchOf("ESPN U")).isEqualTo("ESPNU.HD.us2")
        assertThat(matchOf("ESPN Deportes")).isEqualTo("ESPN.Deportes.us2")
        assertThat(matchOf("ESPN2 [US]", only("ESPN.HD.us2"))).isNull()
        assertThat(matchOf("ESPN [US]", only("ESPN2.HD.us2"))).isNull()
        assertThat(matchOf("ESPN2 [US]", only("ESPNU.HD.us2"))).isNull()
    }

    @Test
    fun `fs1_and_fs2_neverCross`() = runTest {
        assertThat(matchOf("FOX Sport 1", only("FS2.HD.us2"))).isNull()
        assertThat(matchOf("Fox Sports 2", only("FS1.HD.us2"))).isNull()
    }

    @Test
    fun `timeShiftLabel_isKept`() = runTest {
        assertThat(EpgNameNormalizer.normalizeLoose("Fox Sports 1 +1"))
            .isNotEqualTo(EpgNameNormalizer.normalizeLoose("Fox Sports 1"))
        assertThat(matchOf("Fox Sports 1 +1 [US]")).isNull()
    }

    @Test
    fun `foxSoccerPlus_staysDistinct`() = runTest {
        assertThat(matchOf("Fox Soccer Plus [US]")).isEqualTo("Fox.Soccer.Plus.us2")
        assertThat(matchOf("Fox Soccer [US]")).isNull()
    }

    @Test
    fun `missingAliasTarget_staysUnmatched`() = runTest {
        val onlyEspn = only("ESPN.HD.us2")
        assertThat(matchOf("Fox Sports 2 [US]", onlyEspn)).isNull()
        assertThat(matchOf("NBC Golf Channel[US]", onlyEspn)).isNull()
    }

    @Test
    fun `bothFs1Forms_pickIsDeterministic`() = runTest {
        val both = listOf(
            guide(source, "FS1.HD.us2", "FS1 HD"),
            guide(source, "FS1.Fox.Sports.1.HD.us2", "FS1 Fox Sports 1 HD"),
        )
        val first = matchOf("FOX Sport 1 [US]", both)
        assertThat(first).isNotNull()
        assertThat(matchOf("FOX Sport 1 [US]", both.reversed())).isEqualTo(first)
        assertThat(matchOf("FOX SPORT 1 [US]", both)).isEqualTo(first)
    }

    @Test
    fun `exactName_stillBeatsAlias`() = runTest {
        val list = listOf(
            guide(source, "FS1.HD.us2", "FS1 HD"),
            guide(source, "fox.sports.1", "FOX Sport 1"),
        )
        assertThat(matchOf("FOX Sport 1", list)).isEqualTo("fox.sports.1")
    }
}
