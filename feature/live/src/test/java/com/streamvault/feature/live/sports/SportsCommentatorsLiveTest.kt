package com.streamvault.feature.live.sports

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Channel
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Runs against btolat's real page as read on 2026-11-02 at 18:52 Cairo (16:52Z), when three
 * matches were in play. btolat blanks the kick-off time of a match in play.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SportsCommentatorsLiveTest {
    private val date = LocalDate.of(2026, 11, 2)
    private val readAt = Instant.parse("2026-11-02T16:52:00Z")
    private val livePage by lazy { resource("/sports/commentators/btolat_2026-11-02_live.html") }
    private val morningPage by lazy { resource("/sports/commentators/btolat_2026-11-02.html") }

    @Test fun `live page yields every beIN chip with a commentator, in play ones included`() {
        val entries = BtolatCommentatorParser.parse(livePage, date, readAt)

        // 23 cards; StarzPlay, AlKass, On Sport, Apple TV and the commentator-less beIN 3 chip are dropped.
        assertThat(entries).containsExactly(
            SportsCommentatorEntry(2, Instant.parse("2026-11-02T20:00:00Z"), "حفيظ دراجي"),
            SportsCommentatorEntry(4, Instant.parse("2026-11-02T20:00:00Z"), "أحمد عبده"),
            SportsCommentatorEntry(2, Instant.parse("2026-11-02T13:45:00Z"), "عبدالله الغامدي"),
            SportsCommentatorEntry(1, readAt, "عصام الشوالي", live = true),
            SportsCommentatorEntry(2, readAt, "عامر الخوذيري", live = true),
            SportsCommentatorEntry(1, Instant.parse("2026-11-02T18:15:00Z"), "حسن العيدروس"),
            SportsCommentatorEntry(3, Instant.parse("2026-11-02T18:15:00Z"), "أحمد البلوشي"),
            SportsCommentatorEntry(5, Instant.parse("2026-11-02T19:45:00Z"), "منتصر الأزهري"),
        )
        // Without a read time the in-play cards cannot be placed and are skipped, not guessed.
        assertThat(BtolatCommentatorParser.parse(livePage, date).map { it.commentator })
            .containsNoneOf("عصام الشوالي", "عامر الخوذيري")
        assertThat(BtolatCommentatorParser.parse(livePage, date)).hasSize(6)
    }

    @Test fun `a match in play keeps its commentator on the Sports hub and home row`() {
        val entries = BtolatCommentatorParser.parse(livePage, date, readAt)
        val kickoff = Instant.parse("2026-11-02T16:00:00Z")

        assertThat(SportsCommentatorMatcher.commentatorFor(event("beIN Sport 1 FHD Q", kickoff), entries))
            .isEqualTo("عصام الشوالي")
        assertThat(SportsCommentatorMatcher.commentatorFor("AR: beIN SPORTS 2 HD", kickoff, entries))
            .isEqualTo("عامر الخوذيري")
        // A known kick-off on the same channel still wins over the in-play entry.
        assertThat(SportsCommentatorMatcher.commentatorFor("beIN SPORTS 2", Instant.parse("2026-11-02T13:45:00Z"), entries))
            .isEqualTo("عبدالله الغامدي")
        assertThat(SportsCommentatorMatcher.commentatorFor("beIN SPORTS 1", Instant.parse("2026-11-02T18:15:00Z"), entries))
            .isEqualTo("حسن العيدروس")
    }

    @Test fun `in play window reaches three hours back and twenty minutes ahead of the read`() {
        val entries = listOf(SportsCommentatorEntry(1, readAt, "عصام الشوالي", live = true))
        fun at(kickoff: Instant) = SportsCommentatorMatcher.commentatorFor("beIN SPORTS 1", kickoff, entries)

        assertThat(at(readAt.minus(Duration.ofMinutes(180)))).isEqualTo("عصام الشوالي")
        assertThat(at(readAt.plus(Duration.ofMinutes(20)))).isEqualTo("عصام الشوالي")
        assertThat(at(readAt.minus(Duration.ofMinutes(181)))).isNull()
        assertThat(at(readAt.plus(Duration.ofMinutes(21)))).isNull()
        assertThat(SportsCommentatorMatcher.commentatorFor("beIN SPORTS 2", readAt, entries)).isNull()
    }

    @Test fun `hourly refresh during play restores the kick-off read before the match`() = runTest {
        val clock = MutableTestClock(Instant.parse("2026-11-02T08:00:00Z"))
        var page = morningPage
        val repository = SportsCommentatorRepository({ page }, InMemorySportsCommentatorStore(), clock)

        repository.refreshIfStale()
        page = livePage
        clock.now = readAt
        repository.refreshIfStale()

        val entries = repository.entries.value
        assertThat(entries).contains(SportsCommentatorEntry(1, Instant.parse("2026-11-02T16:00:00Z"), "عصام الشوالي"))
        // Not seen before the match: kept as an in-play entry.
        assertThat(entries).contains(SportsCommentatorEntry(2, readAt, "عامر الخوذيري", live = true))
        assertThat(entries).hasSize(8)
    }

    @Test fun `known kick-offs are only carried over from today and from matches already started`() {
        val live = SportsCommentatorEntry(1, readAt, "عصام الشوالي", live = true)
        val yesterday = SportsCommentatorEntry(1, Instant.parse("2026-11-01T16:00:00Z"), "عصام الشوالي")
        val later = SportsCommentatorEntry(1, Instant.parse("2026-11-02T19:00:00Z"), "عصام الشوالي")
        val otherVoice = SportsCommentatorEntry(1, Instant.parse("2026-11-02T16:00:00Z"), "حسن العيدروس")

        assertThat(SportsCommentatorRepository.withKnownKickoffs(listOf(live), listOf(yesterday, later, otherVoice), date, readAt))
            .containsExactly(live)
    }

    @Test fun `markup drift fails soft with no names and no crash`() {
        val drifted = listOf(
            livePage.replace("match-card", "fixture-card"),
            livePage.replace("sc-interp", "chip-text"),
            livePage.replace("match-time", "kickoff"),
            livePage.replace("Playing", "InPlay").replace(Regex("""\d{2}:\d{2} [صم]"""), ""),
            livePage.substring(0, livePage.length / 2),
            "<html><body>Just a moment...</body></html>",
        )

        val parsed = drifted.map { html -> BtolatCommentatorParser.parse(html, date, readAt) }

        assertThat(parsed[0]).isEmpty()
        assertThat(parsed[1]).isEmpty()
        // Without kick-off times only the cards still marked in play are placed, by the read time.
        assertThat(parsed[2].map { it.commentator to it.live })
            .containsExactly("عصام الشوالي" to true, "عامر الخوذيري" to true)
        assertThat(parsed[3]).isEmpty()
        assertThat(parsed[4].size).isAtMost(8)
        assertThat(parsed[5]).isEmpty()
    }

    @Test fun `stored entries keep the in-play flag and older three-field rows still load`() {
        val context = RuntimeEnvironment.getApplication()
        val store = SharedPreferencesSportsCommentatorStore(context)
        val entries = listOf(
            SportsCommentatorEntry(1, readAt, "عصام الشوالي", live = true),
            SportsCommentatorEntry(5, Instant.parse("2026-11-02T19:45:00Z"), "منتصر الأزهري"),
        )

        store.save(StoredSportsCommentators(date, readAt, entries))
        assertThat(store.load()).isEqualTo(StoredSportsCommentators(date, readAt, entries))

        context.getSharedPreferences("sports_commentators", 0).edit()
            .putString("entries", "3\t${readAt.toEpochMilli()}\tأحمد البلوشي").commit()
        assertThat(store.load()?.entries).containsExactly(SportsCommentatorEntry(3, readAt, "أحمد البلوشي"))
    }

    private fun resource(path: String) = requireNotNull(javaClass.getResourceAsStream(path)) { "missing $path" }
        .bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun event(channelName: String, start: Instant) = SportsEvent(
        id = "event-$channelName-$start", title = "Home vs Away", league = null, categoryLabel = null,
        description = "", sport = SportType.FOOTBALL, startTime = start.toEpochMilli(),
        endTime = start.plus(Duration.ofHours(2)).toEpochMilli(),
        broadcasts = listOf(SportsBroadcast(Channel(id = 1L, name = channelName), combinedProfileId = null)),
        confidence = 1f, competitors = emptyList(), session = null,
        eventType = SportsEventType.LIVE_EVENT, rawProgrammeRefs = emptyList(),
    )
}

private class MutableTestClock(var now: Instant) : Clock() {
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
}
