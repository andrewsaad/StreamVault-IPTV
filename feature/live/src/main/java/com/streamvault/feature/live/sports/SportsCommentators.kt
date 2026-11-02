package com.streamvault.feature.live.sports

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** One beIN Sports N broadcast with its Arabic commentator, as listed by btolat.com. */
internal data class SportsCommentatorEntry(
    val beinChannel: Int,
    val kickoff: Instant,
    val commentator: String,
)

/**
 * Reads btolat.com's daily match page. Each `.match-card` carries the kick-off time, and its
 * footer holds one chip per channel: the channel name, then the commentator when one is known.
 * Only "BEIN SPORTS N" chips that name a commentator are kept; league headers are ignored.
 */
internal object BtolatCommentatorParser {
    /** btolat lists times in Cairo time; the page states the offset ("التوقيت GMT+2"). */
    val SOURCE_ZONE: ZoneId = ZoneId.of("Africa/Cairo")

    private val cardStart = Regex("""class="match-card\b""")
    private val matchTime = Regex(
        """class="match-time"[^>]*>\s*([0-9٠-٩]{1,2}):([0-9٠-٩]{2})\s*([صم])?"""
    )
    private val interpolated = Regex("""<span class="sc-interp">([^<]*)</span>""")
    private val pageOffset = Regex("""التوقيت\s*GMT\s*([+-])\s*(\d{1,2})(?::(\d{2}))?""")
    private val beinChip = Regex("""^bein\s*sports?\s*(\d{1,2})(?:\s*hd)?$""", RegexOption.IGNORE_CASE)
    private val numericEntity = Regex("""&#(x?)([0-9a-fA-F]{1,6});""")
    private const val CHIP_MARKER = "data-dc-tpl=\"42\""
    private const val CHIP_FALLBACK_MARKER = "<rect"
    private const val MAX_COMMENTATOR_LENGTH = 60

    fun parse(html: String, date: LocalDate): List<SportsCommentatorEntry> {
        val zone = pageZone(html)
        val starts = cardStart.findAll(html).map { it.range.first }.toList()
        return starts.flatMapIndexed { index, start ->
            val next = starts.getOrNull(index + 1) ?: html.length
            val close = html.indexOf("</li>", start).takeIf { it in start until next } ?: next
            parseCard(html.substring(start, close), date, zone)
        }.distinct()
    }

    private fun parseCard(card: String, date: LocalDate, zone: ZoneId): List<SportsCommentatorEntry> {
        val kickoff = kickoffTime(card)?.let { date.atTime(it).atZone(zone).toInstant() } ?: return emptyList()
        val marker = if (CHIP_MARKER in card) CHIP_MARKER else CHIP_FALLBACK_MARKER
        return card.split(marker).drop(1).mapNotNull { chip ->
            val texts = interpolated.findAll(chip).map { decode(it.groupValues[1]).trim() }.toList()
            val channel = texts.firstOrNull()?.let { beinChip.matchEntire(it) }
                ?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            val commentator = texts.getOrNull(1)
                ?.takeIf { it.isNotBlank() && it.length <= MAX_COMMENTATOR_LENGTH && beinChip.matchEntire(it) == null }
                ?: return@mapNotNull null
            SportsCommentatorEntry(channel, kickoff, commentator)
        }
    }

    private fun kickoffTime(card: String): LocalTime? {
        val match = matchTime.find(card) ?: return null
        var hour = westernDigits(match.groupValues[1]).toIntOrNull() ?: return null
        val minute = westernDigits(match.groupValues[2]).toIntOrNull() ?: return null
        when (match.groupValues[3]) {
            "م" -> if (hour < 12) hour += 12 // م: afternoon/evening
            "ص" -> if (hour == 12) hour = 0 // ص: morning
        }
        if (hour !in 0..23 || minute !in 0..59) return null
        return LocalTime.of(hour, minute)
    }

    private fun pageZone(html: String): ZoneId {
        val match = pageOffset.find(html) ?: return SOURCE_ZONE
        val hours = match.groupValues[2].toInt()
        val minutes = match.groupValues[3].toIntOrNull() ?: 0
        if (hours > 14 || minutes > 59) return SOURCE_ZONE
        val sign = if (match.groupValues[1] == "-") -1 else 1
        return ZoneOffset.ofHoursMinutes(sign * hours, sign * minutes)
    }

    private fun westernDigits(value: String): String = buildString {
        value.forEach { char -> append(if (char in '٠'..'٩') '0' + (char - '٠') else char) }
    }

    private fun decode(value: String): String = numericEntity.replace(value) { match ->
        val code = match.groupValues[2].toIntOrNull(if (match.groupValues[1].isEmpty()) 10 else 16)
        code?.takeIf(Character::isValidCodePoint)?.let { String(Character.toChars(it)) } ?: match.value
    }.replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ").replace("&amp;", "&")
}

/**
 * Pairs btolat commentators with Sports events by beIN channel number and kick-off time.
 * Team names are deliberately not compared: btolat writes them in Arabic and guides rarely do.
 */
internal object SportsCommentatorMatcher {
    val KICKOFF_TOLERANCE: Duration = Duration.ofMinutes(20)

    // Separate beIN feeds (Xtra, MAX, Premium) and non-Arabic regional feeds carry other commentary.
    private val excludedTokens = setOf(
        "xtra", "extra", "max", "premium", "plus",
        "english", "eng", "en", "french", "france", "fr", "usa", "us", "turkey", "turkiye", "tr",
        "espanol", "es", "asia", "au", "australia", "ca", "canada",
    )
    private const val MAX_CHANNEL_NUMBER = 20

    /** The N of an Arabic "beIN Sports N" channel name, or null for any other channel. */
    fun beinChannelNumber(channelName: String): Int? {
        val tokens = channelName.lowercase()
            .replace(Regex("""(?<=\p{L})(?=\d)|(?<=\d)(?=\p{L})"""), " ")
            .split(Regex("""[^\p{L}\p{N}]+"""))
            .filter(String::isNotEmpty)
        if (tokens.any { it in excludedTokens }) return null
        val beinIndex = tokens.indexOfFirst { it == "bein" || it == "beinsport" || it == "beinsports" }
        if (beinIndex < 0) return null
        var numberIndex = beinIndex + 1
        if (tokens[beinIndex] == "bein" && tokens.getOrNull(numberIndex) in setOf("sport", "sports")) numberIndex++
        val number = tokens.getOrNull(numberIndex)?.takeIf { it.length <= 2 }?.toIntOrNull() ?: return null
        return number.takeIf { it in 1..MAX_CHANNEL_NUMBER }
    }

    fun commentatorFor(event: SportsEvent, entries: List<SportsCommentatorEntry>): String? {
        if (entries.isEmpty()) return null
        val kickoff = event.provenance.timing.eventStart ?: Instant.ofEpochMilli(event.startTime)
        return event.broadcasts.asSequence()
            .mapNotNull { broadcast -> beinChannelNumber(broadcast.channel.name) }
            .distinct()
            .mapNotNull { channel ->
                entries.asSequence()
                    .filter { it.beinChannel == channel }
                    .map { it to Duration.between(it.kickoff, kickoff).abs() }
                    .filter { (_, gap) -> gap <= KICKOFF_TOLERANCE }
                    .minByOrNull { (_, gap) -> gap }
                    ?.first?.commentator
            }
            .firstOrNull()
    }

    fun apply(events: List<SportsEvent>, entries: List<SportsCommentatorEntry>): List<SportsEvent> =
        events.map { event ->
            val commentator = commentatorFor(event, entries)
            if (commentator == event.commentator) event else event.copy(commentator = commentator)
        }
}
