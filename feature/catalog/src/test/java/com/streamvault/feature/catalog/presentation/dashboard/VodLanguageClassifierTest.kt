package com.streamvault.feature.catalog.presentation.dashboard

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class VodLanguageClassifierTest {

    @Test
    fun `every real provider category maps to its expected language group`() {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream("vod_category_languages.txt")) {
            "missing vod_category_languages.txt fixture"
        }
        val rows = stream.bufferedReader(Charsets.UTF_8).readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val parts = line.split('|')
                check(parts.size == 3) { "bad fixture line: $line" }
                parts
            }

        assertThat(rows.size).isAtLeast(140)
        val mismatches = rows.mapNotNull { (type, name, expected) ->
            val actual = VodLanguageClassifier.classify(name)?.name ?: "NONE"
            "$type|$name expected $expected but was $actual".takeIf { actual != expected }
        }
        assertWithMessage(mismatches.joinToString("\n")).that(mismatches).isEmpty()
        VodLanguage.entries.forEach { language ->
            assertWithMessage("fixture covers $language").that(rows.any { it[2] == language.name }).isTrue()
        }
    }

    @Test
    fun `Egyptian wins over Arabic`() {
        assertThat(VodLanguageClassifier.classify("افلام عربي مصري")).isEqualTo(VodLanguage.EGYPTIAN)
        assertThat(VodLanguageClassifier.classify("Arabic Egyptian Classics")).isEqualTo(VodLanguage.EGYPTIAN)
    }

    @Test
    fun `Arabic letter variants and diacritics are normalized`() {
        assertThat(VodLanguageClassifier.classify("مسلسلات مصرية")).isEqualTo(VodLanguage.EGYPTIAN)
        assertThat(VodLanguageClassifier.classify("مسلسلات مصرى")).isEqualTo(VodLanguage.EGYPTIAN)
        assertThat(VodLanguageClassifier.classify("أفلام عربية")).isEqualTo(VodLanguage.ARABIC)
        assertThat(VodLanguageClassifier.classify("أفلامٌ عَرَبِيّة")).isEqualTo(VodLanguage.ARABIC)
        assertThat(VodLanguageClassifier.classify("مسلسلات تركية")).isEqualTo(VodLanguage.TURKISH)
        assertThat(VodLanguageClassifier.classify("أحمد زكي")).isEqualTo(VodLanguage.EGYPTIAN)
        assertThat(VodLanguageClassifier.classify("نبيلة عبيد")).isEqualTo(VodLanguage.EGYPTIAN)
        assertThat(VodLanguageClassifier.classify("مسلسلات أجنبية")).isEqualTo(VodLanguage.ENGLISH)
    }

    @Test
    fun `Latin names match whole words case-insensitively`() {
        assertThat(VodLanguageClassifier.classify("ENGLISH_SERIES")).isEqualTo(VodLanguage.ENGLISH)
        assertThat(VodLanguageClassifier.classify("Turkish Drama")).isEqualTo(VodLanguage.TURKISH)
        assertThat(VodLanguageClassifier.classify("Türk Dizileri")).isEqualTo(VodLanguage.TURKISH)
        assertThat(VodLanguageClassifier.classify("Arabica Coffee Show")).isNull()
        assertThat(VodLanguageClassifier.classify("")).isNull()
        assertThat(VodLanguageClassifier.classify("   ")).isNull()
    }

    @Test
    fun `groupIds keeps provider order inside each language and drops unclassified`() {
        val categories = listOf(
            1L to "English Action",
            2L to "افلام مصري",
            3L to "Marvel",
            4L to "English Drama",
            5L to "مسلسلات تركيه",
        )

        val grouped = VodLanguageClassifier.groupIds(categories, nameOf = { it.second }, idOf = { it.first })

        assertThat(grouped[VodLanguage.ENGLISH]).containsExactly(1L, 4L).inOrder()
        assertThat(grouped[VodLanguage.EGYPTIAN]).containsExactly(2L)
        assertThat(grouped[VodLanguage.TURKISH]).containsExactly(5L)
        assertThat(grouped).doesNotContainKey(VodLanguage.ARABIC)
    }
}
