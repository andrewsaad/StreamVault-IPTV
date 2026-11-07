package com.streamvault.data.epg

import java.text.Normalizer
import java.util.Locale

/**
 * Normalizes channel display names for EPG matching.
 * Designed for comparing channel names across different EPG sources.
 *
 * [normalize] is intentionally simpler than [ChannelNormalizer.getLogicalGroupId]
 * — it does not strip quality tags or country prefixes because those may be
 * meaningful when matching EPG channels to provider channels. [normalizeLoose] is the
 * fallback key used only after exact id and exact normalized name both fail.
 */
object EpgNameNormalizer {

    // Compiled once: normalize() runs for every channel on each EPG resolution pass.
    private val nonAlphanumericRegex = Regex("[^a-z0-9]")
    private val combiningMarksRegex = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val bracketTagRegex = Regex("\\[[^\\]]*]|\\([^)]*\\)")
    // "XX:", "XX |", "Ex-Yu |" style country or region prefixes, possibly chained.
    private val regionPrefixRegex = Regex("^\\s*\\|?\\s*[a-z]{2,3}(?:-[a-z]{2,3})?\\s*[:|]\\s*")
    private val timeShiftRegex = Regex("\\+\\s*(\\d{1,2})\\b")
    private val tokenSplitRegex = Regex("[^a-z0-9]+")
    // epgshare01 ids end in a country code, optionally numbered: ".us2", ".ae", ".qa".
    private val countrySuffixRegex = Regex("^[a-z]{2}\\d*$")

    // "H.265" / "H.264" -> "h265" / "h264" so the codec is one quality token.
    private val codecDotRegex = Regex("\\bh\\.(26[45])\\b")
    // Splits glued digits: "1HD" -> "1 HD", "1Xtra" -> "1 Xtra".
    private val glueSplitRegex = Regex("(\\d)(?=(?:fhd|uhd|hd|sd|xtra)(?![a-z0-9]))")
    // "1080p", "720", "hd720": resolution labels are quality words.
    private val resolutionRegex = Regex("(?:f?hd)?(?:480|576|720|1080|1440|2160)p?")

    private val qualityWords = setOf(
        "hd", "fhd", "uhd", "4k", "8k", "hevc", "h265", "h264", "sd", "q", "backup", "vip", "raw", "low", "vega",
    )

    private val orderedTags = setOf("en", "fr", "xtra")

    private val numberWords =listOf("one", "two", "three", "four", "five", "six", "seven", "eight")
        .withIndex().associate { (i, w) -> w to (i + 1).toString() }

    /**
     * Canonical loose keys for well-known US sports networks whose provider and guide names
     * differ. Applied to the whole joined loose key on both sides, only inside the loose
     * fallback, so exact id / exact name matches are never affected. Distinct channels
     * (espn2, espnu, espndeportes, foxsoccerplus, onsportmax) are deliberately absent.
     * No Bally Sports entries: no public guide exists to verify them.
     */
    private val canonicalAliases = mapOf(
        "espn1" to "espn",
        "espnnews" to "espnews",
        "foxsport1" to "fs1",
        "fs1foxsport1" to "fs1",
        "foxsport2" to "fs2",
        "fs2foxsport2" to "fs2",
        "nbcgolfchannel" to "golfchannel",
        "nbcgolf" to "golfchannel",
        "onsport" to "ontimesport",
        "onsport2" to "ontimesport2",
    )

    /**
     * Produces a normalized key from a channel display name.
     * - lowercase
     * - strip accents
     * - remove all non-alphanumeric characters
     *
     * Example: "BBC One HD" → "bbconehd"
     */
    fun normalize(name: String): String {
        if (name.isBlank()) return ""
        return stripAccents(name.lowercase(Locale.ROOT)).replace(nonAlphanumericRegex, "")
    }

    /**
     * Loose key: ignores [..] and (..) tags, "XX:" / "XX |" prefixes, quality words, accents,
     * a trailing "TV" word and the sport/sports spelling. Time-shift labels such as "+1" are kept
     * (as "plus1"), so a shifted channel never takes its base channel's times.
     *
     * Example: "AR: beIN Sport 1 H265 [SD]" → "beinsport1"; "Fox +1" → "foxplus1"
     */
    fun normalizeLoose(name: String): String = looseTokens(name).joinToString("")

    /** Loose keys for one guide channel: from its display name and from its id without the country suffix. */
    fun looseKeys(displayName: String, xmltvId: String): Set<String> {
        val keys = LinkedHashSet<String>(2)
        normalizeLoose(displayName).takeIf(String::isNotEmpty)?.let(keys::add)
        idAsName(xmltvId)?.let(::normalizeLoose)?.takeIf(String::isNotEmpty)?.let(keys::add)
        return keys
    }

    /** Quality words present in [name], used to break ties between entries sharing a loose key. */
    fun qualityWords(name: String): Set<String> {
        if (name.isBlank()) return emptySet()
        return stripAccents(name.lowercase(Locale.ROOT))
            .split(tokenSplitRegex)
            .filterTo(HashSet()) { it in qualityWords }
    }

    private fun looseTokens(name: String): List<String> {
        if (name.isBlank()) return emptyList()
        var text = stripAccents(name.lowercase(Locale.ROOT)).replace(bracketTagRegex, " ")
        while (true) {
            val stripped = regionPrefixRegex.replaceFirst(text, "")
            if (stripped == text) break
            text = stripped
        }
        text = timeShiftRegex.replace(text) { " plus${it.groupValues[1]} " }
        text = codecDotRegex.replace(text, " h$1 ")
        text = glueSplitRegex.replace(text, "$1 ")
        var tokens = text.split(tokenSplitRegex)
            .filter { it.isNotEmpty() && !isQuality(it) }
            .map { if (it == "sports") "sport" else it }
        if ("alkass" in tokens) {
            // Alkass: number words equal digits, the beIN brand and the language tag are noise.
            tokens = tokens.filter { it != "bein" && it != "en" }.map { numberWords[it] ?: it }
        }
        if ("bein" in tokens && "alkass" !in tokens) tokens = tagBeforeNumber(tokens)
        if (tokens.size > 1 && tokens.last() == "tv") tokens = tokens.dropLast(1)
        return canonicalAliases[tokens.joinToString("")]?.let { listOf(it) } ?: tokens
    }

    /**
     * beIN only: providers write "Sport 1 EN" / "1Xtra", the guide writes "EN 1" / "XTRA 1".
     * A trailing "<number> <tag>" pair is rewritten to "<tag> <number>" so both reach one key.
     */
    private fun tagBeforeNumber(tokens: List<String>): List<String> {
        if (tokens.size < 2) return tokens
        val tag = tokens.last()
        val number = tokens[tokens.size - 2]
        if (tag !in orderedTags || number.isEmpty() || !number.all(Char::isDigit)) return tokens
        return tokens.dropLast(2) + tag + number
    }

    private fun isQuality(token: String) = token in qualityWords || resolutionRegex.matches(token)

    private fun idAsName(xmltvId: String): String? {
        val trimmed = xmltvId.trim()
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split('.')
        val withoutCountry = if (parts.size > 1 && countrySuffixRegex.matches(parts.last().lowercase(Locale.ROOT))) {
            parts.dropLast(1)
        } else {
            parts
        }
        return withoutCountry.joinToString(" ").replace('_', ' ')
    }

    private fun stripAccents(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(combiningMarksRegex, "")
}
