package com.streamvault.data.epg

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/** Provider names put the number before the tag ("Sport 1 EN", "1Xtra"); the guide puts it after ("EN 1", "XTRA 1"). */
class EpgBeinWordOrderTest {

    private fun key(name: String) = EpgNameNormalizer.normalizeLoose(name)

    @Test
    fun `xtraChannels_matchTheGuideSpelling`() {
        listOf(
            "beIN_SPORTS_1Xtra_1080p" to "beIN SPORTS XTRA 1",
            "beIN_SPORTS_2Xtra_HD720" to "beIN SPORTS XTRA 2",
            "beIN_SPORTS_3Xtra_Low" to "beIN SPORTS XTRA 3",
            "beIN_SPORTS_4Xtra_VEGA" to "beIN SPORTS XTRA 4",
        ).forEach { (provider, guide) -> assertWithMessage(provider).that(key(provider)).isEqualTo(key(guide)) }
    }

    @Test
    fun `languageChannels_matchTheGuideSpelling`() {
        listOf(
            "beIN Sport 1 EN HD" to "beIN SPORTS EN 1",
            "beIN Sport 2 EN FHD" to "beIN SPORTS EN 2",
            "beIN Sport 1 EN Vega" to "beIN SPORTS EN 1",
            "beIN Sport 1 FR HD" to "beIN SPORTS FR 1",
            "beIN Sport 2 FR FHD" to "beIN SPORTS FR 2",
        ).forEach { (provider, guide) -> assertWithMessage(provider).that(key(provider)).isEqualTo(key(guide)) }
    }

    @Test
    fun `reorderedKeys_stayDistinctFromPlainChannels`() {
        assertThat(key("beIN_SPORTS_1Xtra_Low")).isNotEqualTo(key("beIN SPORTS 1"))
        assertThat(key("beIN Sport 1 EN HD")).isNotEqualTo(key("beIN Sport 1 FR HD"))
        assertThat(key("beIN Sport 1 EN HD")).isNotEqualTo(key("beIN SPORTS 1"))
        assertThat(key("beIN_SPORTS_1Xtra_Low")).isNotEqualTo(key("beIN_SPORTS_2Xtra_Low"))
    }

    @Test
    fun `guideIdKey_matchesProviderForXtra`() {
        val keys = EpgNameNormalizer.looseKeys("beIN SPORTS XTRA 1.qa", "beIN SPORTS XTRA 1.qa")
        assertThat(keys).contains(key("beIN_SPORTS_1Xtra_1080p"))
    }

    @Test
    fun `nonBeinNames_areNotReordered`() {
        assertThat(key("Channel 1 EN")).isEqualTo("channel1en")
        assertThat(key("Sport 2 FR")).isEqualTo("sport2fr")
    }
}
