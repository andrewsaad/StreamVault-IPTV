package com.streamvault.data.epg

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class EpgBeinOnSportAliasTest {

    private fun key(name: String) = EpgNameNormalizer.normalizeLoose(name)

    @Test
    fun `beinSport1_variants_reachTheBaseKey`() {
        val base = key("beIN SPORTS 1")
        listOf(
            "beIN Sport 1 Vega", "beIN Sport 1 Low", "beIN Sport 1 SD", "beIN Sport 1 H.265",
            "beIN Sport 1 H265 SD", "beIN Sport 1 HD Q", "beIN Sport 1 FHD Q", "beIN Sport 1 4K",
        ).forEach { assertWithMessage(it).that(key(it)).isEqualTo(base) }
    }

    @Test
    fun `beinXtra_variants_shareADistinctKey`() {
        val xtra = key("beIN_SPORTS_1Xtra_1080p")
        assertThat(key("beIN_SPORTS_1Xtra_HD720")).isEqualTo(xtra)
        assertThat(key("beIN_SPORTS_1Xtra_Low")).isEqualTo(xtra)
        assertThat(xtra).isEqualTo("beinsport1xtra")
        assertThat(xtra).isNotEqualTo(key("beIN SPORTS 1"))
    }

    @Test
    fun `beinSportNumbers_stayDistinct`() {
        assertThat(key("beIN Sport 1 Low")).isNotEqualTo(key("beIN Sport 2 Low"))
    }

    @Test
    fun `alkass_numberWordsAndBrandReachOneKey`() {
        val k = key("Alkass Four")
        assertThat(key("BeIN Alkass 4 HD")).isEqualTo(k)
        assertThat(key("Alkass_4_EN")).isEqualTo(k)
        assertThat(key("Alkass Five")).isNotEqualTo(k)
    }

    @Test
    fun `numberWords_areNotMappedOutsideAlkass`() {
        assertThat(key("BBC One")).isNotEqualTo(key("BBC 1"))
    }

    @Test
    fun `onSport_variantsReachOnTimeSports`() {
        val k = key("On Time Sports HD")
        listOf("ON Sport HD 1080", "ON  Sport SD", "ON Sport LOW", "ON Sport 4K")
            .forEach { assertWithMessage(it).that(key(it)).isEqualTo(k) }
        assertThat(key("ON Sport 2")).isEqualTo(key("On Time Sport 2"))
    }

    @Test
    fun `onSportMax_staysDistinct`() {
        assertThat(key("ON Sport Max")).isNotEqualTo(key("ON Sport"))
        assertThat(key("ON Sport Max")).isNotEqualTo(key("On Time Sports HD"))
    }
}
