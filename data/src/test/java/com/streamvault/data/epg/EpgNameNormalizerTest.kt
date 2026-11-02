package com.streamvault.data.epg

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EpgNameNormalizerTest {

    @Test
    fun `normalize_basicName_lowercaseAndStripped`() {
        assertThat(EpgNameNormalizer.normalize("BBC One HD")).isEqualTo("bbconehd")
    }

    @Test
    fun `normalize_accents_removed`() {
        assertThat(EpgNameNormalizer.normalize("Télé Loisirs")).isEqualTo("teleloisirs")
    }

    @Test
    fun `normalize_specialChars_removed`() {
        assertThat(EpgNameNormalizer.normalize("CNN - US (Int'l)")).isEqualTo("cnnusintl")
    }

    @Test
    fun `normalize_empty_returnsEmpty`() {
        assertThat(EpgNameNormalizer.normalize("")).isEmpty()
        assertThat(EpgNameNormalizer.normalize("   ")).isEmpty()
    }

    @Test
    fun `normalize_numbersPreserved`() {
        assertThat(EpgNameNormalizer.normalize("Sport 1")).isEqualTo("sport1")
    }

    @Test
    fun `normalize_mixedCase_lowered`() {
        assertThat(EpgNameNormalizer.normalize("DiScOvErY")).isEqualTo("discovery")
    }

    @Test
    fun `normalize_allSpecialChars_returnsEmpty`() {
        assertThat(EpgNameNormalizer.normalize("---")).isEmpty()
    }

    @Test
    fun `normalize_germanUmlauts_stripped`() {
        assertThat(EpgNameNormalizer.normalize("ZDF Küche")).isEqualTo("zdfkuche")
    }

    @Test
    fun `normalize_frenchCedilla_stripped`() {
        assertThat(EpgNameNormalizer.normalize("Garçon")).isEqualTo("garcon")
    }

    @Test
    fun `normalize_precompiledRegexes_matchThePerCallImplementation`() {
        val names = listOf(
            "", "   ", "BBC One HD", "Télé Loisirs", "CNN - US (Int'l)", "ZDF Küche", "Garçon",
            "beIN SPORTS 1 ᴴᴰ", "MBC Action ★", "ŞHOW TV", "Ελληνικά 24", "قناة الكأس", "Ö3 / Österreich",
            "Canal+ Sport 2 (FR)", "ESPN２", "Ñ TV", "áb̧c",
        )
        names.forEach { name ->
            assertThat(EpgNameNormalizer.normalize(name)).isEqualTo(perCallReference(name))
        }
    }

    /** The implementation before regexes were hoisted to constants. */
    private fun perCallReference(name: String): String {
        if (name.isBlank()) return ""
        val lower = name.lowercase(java.util.Locale.ROOT)
        val stripped = java.text.Normalizer.normalize(lower, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        return stripped.replace(Regex("[^a-z0-9]"), "")
    }
}
