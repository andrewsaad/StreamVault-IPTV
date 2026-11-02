package com.streamvault.feature.catalog.presentation.dashboard

import java.text.Normalizer

/** Language groups for the home "New … Movies/Series" rows, in display order. */
enum class VodLanguage {
    ARABIC,
    EGYPTIAN,
    ENGLISH,
    TURKISH,
}

/**
 * Maps a provider VOD category NAME to a language group. Pure and allocation-light: it runs once
 * per category whenever the category list changes, never per movie.
 *
 * Egyptian wins over Arabic (an Egyptian category is also Arabic); anything without a clear
 * language marker (actor collections, BoxOffice, Indian, dubbed, documentaries...) maps to null.
 */
object VodLanguageClassifier {
    private val combiningMarks = Regex("\\p{Mn}+")
    private val separators = Regex("[^\\p{L}\\p{N}]+")

    private val egyptianWords = setOf("egypt", "egyptian")
    private val egyptianPhrases = listOf(
        "مسرحيات",
        "el kabeer awy",
        // Egyptian actor collections.
        "سعاد حسني", "اسماعيل ياسين", "عادل امام", "احمد زكي", "نور الشريف", "محمود عبد العزيز",
        "رشدي اباظه", "احمد السقا", "كريم عبدالعزيز", "كريم عبد العزيز", "احمد عز", "محمد رمضان",
        "محمد سعد", "احمد حلمي", "محمد هنيدي", "فريد شوقي", "عمر الشريف", "نبيله عبيد",
    ).map(::normalize)

    private val arabicPhrases = listOf("دريد لحام", "كلاسيك").map(::normalize)

    fun classify(categoryName: String): VodLanguage? {
        val text = normalize(categoryName)
        if (text.isEmpty()) return null
        val words = text.split(' ')
        return when {
            isEgyptian(text, words) -> VodLanguage.EGYPTIAN
            isArabic(text, words) -> VodLanguage.ARABIC
            isEnglish(text, words) -> VodLanguage.ENGLISH
            isTurkish(words) -> VodLanguage.TURKISH
            else -> null
        }
    }

    /** Category ids per language, keeping the provider's category order inside each group. */
    fun <T> groupIds(
        categories: List<T>,
        nameOf: (T) -> String,
        idOf: (T) -> Long,
    ): Map<VodLanguage, List<Long>> {
        val grouped = LinkedHashMap<VodLanguage, MutableList<Long>>()
        categories.forEach { category ->
            val language = classify(nameOf(category)) ?: return@forEach
            grouped.getOrPut(language) { ArrayList() }.add(idOf(category))
        }
        return grouped
    }

    // "مصر" covers مصري/مصريه/بالمصري/مسرح مصر/فوازيرمصريه.
    private fun isEgyptian(text: String, words: List<String>): Boolean =
        text.contains("مصر") ||
            words.any { word -> word in egyptianWords } ||
            egyptianPhrases.any(text::contains)

    private fun isArabic(text: String, words: List<String>): Boolean =
        text.contains("عربي") ||
            words.any { word -> word == "arabic" || word == "arab" } ||
            arabicPhrases.any(text::contains)

    private fun isEnglish(text: String, words: List<String>): Boolean =
        words.any { word -> word == "english" } || text.contains("اجنبي")

    private fun isTurkish(words: List<String>): Boolean =
        words.any { word -> word.startsWith("turk") || word.startsWith("ترك") }

    /**
     * Lower-case, strip Latin accents and Arabic diacritics/tatweel, unify أ/إ/آ→ا, ة→ه, ى→ي,
     * and collapse every run of punctuation/whitespace into one space.
     */
    internal fun normalize(value: String): String {
        val decomposed = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
        val unified = buildString(decomposed.length) {
            decomposed.forEach { char ->
                when (char) {
                    'أ', 'إ', 'آ', 'ٱ' -> append('ا')
                    'ة' -> append('ه')
                    'ى' -> append('ي')
                    'ـ' -> Unit
                    else -> append(char)
                }
            }
        }
        return combiningMarks.replace(unified, "")
            .replace(separators, " ")
            .trim()
    }
}
