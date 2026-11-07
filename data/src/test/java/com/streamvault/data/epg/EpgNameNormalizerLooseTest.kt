package com.streamvault.data.epg

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class EpgNameNormalizerLooseTest {

    private fun loose(name: String) = EpgNameNormalizer.normalizeLoose(name)

    @Test
    fun `normalizeLoose_stripsBracketAndParenthesisTags`() {
        assertThat(loose("Fox [US]")).isEqualTo("fox")
        assertThat(loose("USA Network HD [US]")).isEqualTo("usanetwork")
        assertThat(loose("MBC Masr (Backup)")).isEqualTo("mbcmasr")
        assertThat(loose("Al Ahly [HD]")).isEqualTo(loose("Al Ahly [SD]"))
    }

    @Test
    fun `normalizeLoose_stripsCountryPrefixes`() {
        assertThat(loose("Ex-Yu | Hr: Arena Sport 1")).isEqualTo("arenasport1")
        assertThat(loose("AR: beIN Sports 2")).isEqualTo("beinsport2")
        assertThat(loose("AR | beIN Sports 2")).isEqualTo("beinsport2")
        assertThat(loose("US: ESPN 2")).isEqualTo("espn2")
    }

    @Test
    fun `normalizeLoose_dropsQualityWords`() {
        assertThat(loose("beIN Sport 1 H265 SD")).isEqualTo("beinsport1")
        listOf("HD", "FHD", "UHD", "4K", "8K", "HEVC", "H265", "SD", "Q", "Backup", "VIP", "RAW").forEach { word ->
            assertWithMessage(word).that(loose("Nile Sport $word")).isEqualTo("nilesport")
        }
    }

    @Test
    fun `normalizeLoose_treatsSportAndSportsAlike_soPluralSpellingsMatch`() {
        assertThat(loose("beIN Sport 1 H265 SD")).isEqualTo(loose("beIN SPORTS 1"))
    }

    @Test
    fun `normalizeLoose_dropsATrailingTvWord_onlyWhenSomethingRemains`() {
        assertThat(loose("Al Ahly TV HD")).isEqualTo(loose("Al Ahly [HD]"))
        assertThat(loose("TV")).isEqualTo("tv")
    }

    @Test
    fun `normalizeLoose_stripsAccents`() {
        assertThat(loose("Télé Loisirs (FR)")).isEqualTo("teleloisirs")
    }

    @Test
    fun `normalizeLoose_keepsTimeShiftLabels`() {
        assertThat(loose("Fox +1")).isEqualTo("foxplus1")
        assertThat(loose("Fox +1")).isNotEqualTo(loose("Fox"))
        assertThat(loose("Fox +1")).isNotEqualTo(loose("Fox 1"))
        assertThat(loose("beIN Sports 1 +2 HD")).isEqualTo("beinsport1plus2")
    }

    @Test
    fun `normalizeLoose_emptyOrOnlyTags_returnsEmpty`() {
        assertThat(loose("")).isEmpty()
        assertThat(loose("   ")).isEmpty()
        assertThat(loose("[HD]")).isEmpty()
        assertThat(loose("HD")).isEmpty()
    }

    @Test
    fun `normalizeLoose_doesNotChangeTheStrictKey`() {
        assertThat(EpgNameNormalizer.normalize("Fox [US]")).isEqualTo("foxus")
    }

    @Test
    fun `looseKeys_usesDisplayNameAndIdWithoutCountrySuffix`() {
        assertThat(EpgNameNormalizer.looseKeys("Freeform HD", "Freeform.HD.us2")).containsExactly("freeform")
        assertThat(EpgNameNormalizer.looseKeys("", "NFL.Network.HD.us2")).containsExactly("nflnetwork")
        assertThat(EpgNameNormalizer.looseKeys("Al Ahly TV HD", "Al.Ahly.TV.HD.ae")).containsExactly("alahly")
        assertThat(EpgNameNormalizer.looseKeys("beIN SPORTS 1", "beIN.Sports.1.qa")).containsExactly("beinsport1")
    }

    @Test
    fun `looseKeys_idWithoutCountrySuffix_isUsedWhole`() {
        assertThat(EpgNameNormalizer.looseKeys("", "ESPN2")).containsExactly("espn2")
        assertThat(EpgNameNormalizer.looseKeys("", "")).isEmpty()
    }

    @Test
    fun `looseKeys_differentNameAndId_returnsBoth`() {
        assertThat(EpgNameNormalizer.looseKeys("Fox Sports One", "FS1.us2")).containsExactly("foxsportone", "fs1")
    }
}
