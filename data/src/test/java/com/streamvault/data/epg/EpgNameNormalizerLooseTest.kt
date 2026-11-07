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
    fun `normalizeLoose_keepsATimeShiftInsideBrackets`() {
        assertThat(loose("Fox (+1)")).isEqualTo("foxplus1")
        assertThat(loose("Fox [+2] HD")).isEqualTo("foxplus2")
    }

    @Test
    fun `normalizeLoose_keepsADifferentFeedNamedInBrackets_andDropsEast`() {
        assertThat(loose("ESPN 2 (Alternate)")).isNotEqualTo(loose("[US]ESPN_2HD"))
        assertThat(loose("HBO (West)")).isEqualTo(loose("HBO West"))
        assertThat(loose("HBO (Pacific)")).isNotEqualTo(loose("HBO"))
        assertThat(loose("Fox (East)")).isEqualTo(loose("Fox"))
    }

    @Test
    fun `normalizeLoose_keepsRegionalBeinFeedsOffTheArabicGuide`() {
        assertThat(loose("[FR]_BeIN_SPORTS_1_HD")).isEqualTo(loose("beIN SPORTS FR 1"))
        assertThat(loose("beIN Sport 1 FR FHD")).isEqualTo(loose("beIN SPORTS FR 1"))
        listOf("BeIN SPORTS 1 HD [TR]", "BeIN_SPORTS_1_HD [TR]", "US: beIN Sports 1", "[CA] beIN Sports 1").forEach { name ->
            assertWithMessage(name).that(loose(name)).isNotEqualTo(loose("beIN SPORTS 1"))
        }
        assertThat(loose("[FR]_BeIN_SPORT_MAX4")).isNotEqualTo(loose("[Q] beIN SPORT MAX4"))
    }

    @Test
    fun `normalizeLoose_keepsArabicTaggedBeinFeedsOnTheArabicGuide`() {
        listOf("AR: beIN Sports 1", "beIN Sports 1 [AR]", "[QA] beIN Sports 1", "beIN Sport 1 FHD Q").forEach { name ->
            assertWithMessage(name).that(loose(name)).isEqualTo("beinsport1")
        }
    }

    @Test
    fun `qualityWords_seesAQualityGluedToANumber`() {
        assertThat(EpgNameNormalizer.qualityWords("[US]ESPN_2HD")).containsExactly("hd")
    }

    @Test
    fun `regionsConflict_rejectsAnotherCountrysGuide_only`() {
        fun conflict(name: String, id: String) = EpgNameNormalizer.regionsConflict(name, id)

        assertThat(conflict("TR: Fox", "Fox.us2")).isTrue()
        assertThat(conflict("Fox [UK]", "Fox.us2")).isTrue()
        assertThat(conflict("US: Fox", "Fox.us2")).isFalse()
        assertThat(conflict("Fox [US]", "Fox.us2")).isFalse()
        assertThat(conflict("Fox", "Fox.us2")).isFalse()
        assertThat(conflict("Fox [SD]", "Fox.us2")).isFalse()
        assertThat(conflict("EN: BBC One", "BBC.One.uk")).isFalse()
        assertThat(conflict("US: Fox", "Fox.tv")).isFalse()
        assertThat(conflict("US: Fox", "fox")).isFalse()
    }

    @Test
    fun `normalizeLoose_aKeyOfOnlyDigitsMatchesNothing`() {
        // A guide name in Arabic script leaves only its digits: "1 أبوظبي الرياضية" must not match "1 TV".
        assertThat(loose("Afg: 1 TV")).isEmpty()
        assertThat(loose("24 HD [TR]")).isEmpty()
        assertThat(EpgNameNormalizer.looseKeys("", "ar:.1.أبوظبي.الرياضية.ae")).isEmpty()
        assertThat(loose("France 24")).isEqualTo("france24")
    }

    @Test
    fun `normalizeLoose_keepsAPlusAfterAWord`() {
        assertThat(loose("MBC Drama+ HD")).isNotEqualTo(loose("MBC Drama"))
        assertThat(loose("MBC Drama+ HD")).isEqualTo(loose("MBC Drama Plus"))
        assertThat(loose("Fox+1")).isEqualTo("foxplus1")
    }

    @Test
    fun `regionsConflict_readsLongerRegionTags`() {
        assertThat(EpgNameNormalizer.regionsConflict("Ex-Yu | Srb: HBO 2 HD", "HBO2.HD.us2")).isTrue()
        assertThat(EpgNameNormalizer.regionsConflict("USA: Fox", "Fox.us2")).isFalse()
        assertThat(EpgNameNormalizer.regionsConflict("CNN [NEWS]", "CNN.HD.us2")).isFalse()
        assertThat(EpgNameNormalizer.regionsConflict("Fox [VIP]", "Fox.us2")).isFalse()
    }

    @Test
    fun `regionsConflict_trustsNoCountryOnMultiRegionGuides`() {
        // AE1 lists channels from many countries under ".ae", so its suffix says nothing about them.
        assertThat(EpgNameNormalizer.regionsConflict("[FR]_M6", "en:.M6.ae")).isFalse()
        assertThat(EpgNameNormalizer.regionsConflict("KU: Amozhgary TV", "Amozhgary.TV.ae")).isFalse()
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
