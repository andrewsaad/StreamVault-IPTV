package com.streamvault.feature.live.sports

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Channel
import com.streamvault.domain.sports.provider.SportsEventTiming
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SportsCommentatorsTest {
    private val fixtureDate = LocalDate.of(2026, 11, 2)
    private val fixture by lazy {
        requireNotNull(javaClass.getResourceAsStream("/sports/commentators/btolat_2026-11-02.html")) {
            "missing btolat fixture"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    @Test fun `fixture yields only beIN chips that name a commentator`() {
        val entries = BtolatCommentatorParser.parse(fixture, fixtureDate)

        // Page times are GMT+2: 22:00, 18:00 and 21:45 local. AlKass, StarzPlay and the
        // commentator-less beIN 3 chip are dropped.
        assertThat(entries).containsExactly(
            SportsCommentatorEntry(2, Instant.parse("2026-11-02T20:00:00Z"), "حفيظ دراجي"),
            SportsCommentatorEntry(1, Instant.parse("2026-11-02T16:00:00Z"), "عصام الشوالي"),
            SportsCommentatorEntry(5, Instant.parse("2026-11-02T19:45:00Z"), "منتصر الأزهري"),
        )
    }

    @Test fun `twelve hour markers and Arabic digits convert to the right clock time`() {
        val date = LocalDate.of(2026, 11, 2)
        fun kickoff(time: String) = BtolatCommentatorParser.parse(page(card(time, "BEIN SPORTS 1", "معلق")), date)
            .single().kickoff

        assertThat(kickoff("12:30 ص")).isEqualTo(Instant.parse("2026-11-01T22:30:00Z"))
        assertThat(kickoff("12:15 م")).isEqualTo(Instant.parse("2026-11-02T10:15:00Z"))
        assertThat(kickoff("03:30 ص")).isEqualTo(Instant.parse("2026-11-02T01:30:00Z"))
        assertThat(kickoff("٠٨:١٥ م")).isEqualTo(Instant.parse("2026-11-02T18:15:00Z"))
    }

    @Test fun `page without an offset label falls back to Cairo time including summer time`() {
        val summer = LocalDate.of(2026, 7, 1)
        val html = "<html><body><ul>" + card("09:00 م", "BEIN SPORTS 3", "معلق") + "</ul></body></html>"

        val entry = BtolatCommentatorParser.parse(html, summer).single()

        assertThat(entry.kickoff).isEqualTo(Instant.parse("2026-07-01T18:00:00Z"))
        assertThat(BtolatCommentatorParser.SOURCE_ZONE).isEqualTo(ZoneId.of("Africa/Cairo"))
    }

    @Test fun `stated page offset wins over the Cairo default`() {
        val html = page(card("09:00 م", "BEIN SPORTS 3", "معلق"), offset = "GMT+3")

        val entry = BtolatCommentatorParser.parse(html, LocalDate.of(2026, 11, 2)).single()

        assertThat(entry.kickoff).isEqualTo(Instant.parse("2026-11-02T18:00:00Z"))
    }

    @Test fun `beIN MAX, other networks and malformed cards are ignored`() {
        val html = page(
            card("06:00 م", "BEIN SPORTS MAX 1", "معلق") +
                card("06:00 م", "ALKASS 5 HD", "معلق") +
                card("bad", "BEIN SPORTS 1", "معلق") +
                card("07:00 م", "beIN Sports 4 HD", "أحمد &amp; علي"),
        )

        assertThat(BtolatCommentatorParser.parse(html, fixtureDate)).containsExactly(
            SportsCommentatorEntry(4, Instant.parse("2026-11-02T17:00:00Z"), "أحمد & علي"),
        )
    }

    @Test fun `parser tolerates pages without cards`() {
        assertThat(BtolatCommentatorParser.parse("<html>Access denied</html>", fixtureDate)).isEmpty()
        assertThat(BtolatCommentatorParser.parse("", fixtureDate)).isEmpty()
    }

    @Test fun `beIN channel numbers come from Arabic beIN Sports N names only`() {
        assertThat(SportsCommentatorMatcher.beinChannelNumber("beIN Sport 1 FHD Q")).isEqualTo(1)
        assertThat(SportsCommentatorMatcher.beinChannelNumber("AR: beIN SPORTS 2 HD")).isEqualTo(2)
        assertThat(SportsCommentatorMatcher.beinChannelNumber("BeINSports3")).isEqualTo(3)
        assertThat(SportsCommentatorMatcher.beinChannelNumber("beIN_SPORTS_6_HEVC")).isEqualTo(6)
        assertThat(SportsCommentatorMatcher.beinChannelNumber("beIN_SPORTS_1Xtra_1080p")).isNull()
        assertThat(SportsCommentatorMatcher.beinChannelNumber("beIN SPORTS XTRA 1")).isNull()
        assertThat(SportsCommentatorMatcher.beinChannelNumber("beIN SPORTS MAX 1 HD")).isNull()
        assertThat(SportsCommentatorMatcher.beinChannelNumber("beIN Sports English 1")).isNull()
        assertThat(SportsCommentatorMatcher.beinChannelNumber("FR: beIN Sports 1")).isNull()
        assertThat(SportsCommentatorMatcher.beinChannelNumber("beIN Sports News")).isNull()
        assertThat(SportsCommentatorMatcher.beinChannelNumber("Sky Sports 1")).isNull()
    }

    @Test fun `matches by channel number and kick-off time from the real fixture`() {
        val entries = BtolatCommentatorParser.parse(fixture, fixtureDate)
        val event = event("beIN Sport 1 FHD Q", Instant.parse("2026-11-02T16:00:00Z"))

        assertThat(SportsCommentatorMatcher.commentatorFor(event, entries)).isEqualTo("عصام الشوالي")
        // Reading the page as UTC would put kick-off two hours late; that must not match.
        assertThat(SportsCommentatorMatcher.commentatorFor(
            event("beIN Sport 1 FHD Q", Instant.parse("2026-11-02T18:00:00Z")), entries,
        )).isNull()
    }

    @Test fun `kick-off window is twenty minutes either side inclusive`() {
        val kickoff = Instant.parse("2026-11-02T16:00:00Z")
        val entries = listOf(SportsCommentatorEntry(1, kickoff, "عصام الشوالي"))
        fun at(offsetMinutes: Long) = SportsCommentatorMatcher.commentatorFor(
            event("beIN SPORTS 1", kickoff.plus(Duration.ofMinutes(offsetMinutes))), entries,
        )

        assertThat(at(-20)).isEqualTo("عصام الشوالي")
        assertThat(at(20)).isEqualTo("عصام الشوالي")
        assertThat(at(-21)).isNull()
        assertThat(at(21)).isNull()
    }

    @Test fun `wrong channel, Xtra feed, missing data and other networks never match`() {
        val kickoff = Instant.parse("2026-11-02T16:00:00Z")
        val entries = listOf(SportsCommentatorEntry(1, kickoff, "عصام الشوالي"))

        assertThat(SportsCommentatorMatcher.commentatorFor(event("beIN SPORTS 2", kickoff), entries)).isNull()
        assertThat(SportsCommentatorMatcher.commentatorFor(event("beIN_SPORTS_1Xtra_1080p", kickoff), entries)).isNull()
        assertThat(SportsCommentatorMatcher.commentatorFor(event("SSC 1", kickoff), entries)).isNull()
        assertThat(SportsCommentatorMatcher.commentatorFor(event("beIN SPORTS 1", kickoff), emptyList())).isNull()
    }

    @Test fun `provider kick-off is preferred, closest entry wins and any broadcast can match`() {
        val kickoff = Instant.parse("2026-11-02T16:00:00Z")
        val entries = listOf(
            SportsCommentatorEntry(1, kickoff, "عصام الشوالي"),
            SportsCommentatorEntry(1, kickoff.plus(Duration.ofMinutes(15)), "حسن العيدروس"),
            SportsCommentatorEntry(3, kickoff, "أحمد البلوشي"),
        )
        // The guide slot opens 30 minutes early; the provider's kick-off still matches.
        val early = event("beIN SPORTS 1", kickoff.minus(Duration.ofMinutes(30)))
        val withKickoff = early.copy(provenance = early.provenance.copy(timing = SportsEventTiming(eventStart = kickoff)))

        assertThat(SportsCommentatorMatcher.commentatorFor(early, entries)).isNull()
        assertThat(SportsCommentatorMatcher.commentatorFor(withKickoff, entries)).isEqualTo("عصام الشوالي")
        assertThat(SportsCommentatorMatcher.commentatorFor(
            event("beIN SPORTS 1", kickoff.plus(Duration.ofMinutes(12))), entries,
        )).isEqualTo("حسن العيدروس")
        assertThat(SportsCommentatorMatcher.commentatorFor(
            event(listOf("SSC 1", "beIN SPORTS 3"), kickoff), entries,
        )).isEqualTo("أحمد البلوشي")
    }

    @Test fun `apply sets and clears commentators and keeps unchanged events`() {
        val kickoff = Instant.parse("2026-11-02T16:00:00Z")
        val entries = listOf(SportsCommentatorEntry(1, kickoff, "عصام الشوالي"))
        val plain = event("SSC 1", kickoff)
        val stale = event("beIN SPORTS 2", kickoff).copy(commentator = "قديم")

        val applied = SportsCommentatorMatcher.apply(listOf(event("beIN SPORTS 1", kickoff), plain, stale), entries)

        assertThat(applied.map(SportsEvent::commentator)).containsExactly("عصام الشوالي", null, null).inOrder()
        assertThat(applied[1]).isSameInstanceAs(plain)
    }

    @Test fun `fetches at most once an hour and only for today in Cairo`() = runTest {
        val clock = MutableClock(Instant.parse("2026-11-02T05:45:00Z"))
        val requested = mutableListOf<LocalDate>()
        val store = InMemorySportsCommentatorStore()
        val repository = SportsCommentatorRepository({ date -> requested += date; fixture }, store, clock)

        repository.refreshIfStale()
        clock.now = clock.now.plus(Duration.ofMinutes(59))
        repository.refreshIfStale()

        assertThat(requested).containsExactly(fixtureDate)
        assertThat(repository.entries.value).hasSize(3)
        assertThat(store.value?.date).isEqualTo(fixtureDate)

        clock.now = clock.now.plus(Duration.ofMinutes(2))
        repository.refreshIfStale()
        assertThat(requested).containsExactly(fixtureDate, fixtureDate)

        // 22:00Z is already the next day in Cairo, so the hour throttle does not apply.
        clock.now = Instant.parse("2026-11-02T22:00:00Z")
        repository.refreshIfStale()
        assertThat(requested.last()).isEqualTo(LocalDate.of(2026, 11, 3))
    }

    @Test fun `failed fetch is silent, keeps shown entries and still waits an hour`() = runTest {
        val clock = MutableClock(Instant.parse("2026-11-02T05:45:00Z"))
        var calls = 0
        var page: String? = fixture
        val repository = SportsCommentatorRepository({ calls++; page }, InMemorySportsCommentatorStore(), clock)

        repository.refreshIfStale()
        page = null
        clock.now = clock.now.plus(Duration.ofMinutes(61))
        repository.refreshIfStale()
        clock.now = clock.now.plus(Duration.ofMinutes(30))
        repository.refreshIfStale()

        assertThat(calls).isEqualTo(2)
        assertThat(repository.entries.value).hasSize(3)

        val throwing = SportsCommentatorRepository({ error("network") }, InMemorySportsCommentatorStore(), clock)
        throwing.refreshIfStale()
        assertThat(throwing.entries.value).isEmpty()
    }

    @Test fun `stored entries for today are reused across restarts without fetching`() = runTest {
        val clock = MutableClock(Instant.parse("2026-11-02T06:00:00Z"))
        val stored = listOf(SportsCommentatorEntry(1, Instant.parse("2026-11-02T16:00:00Z"), "عصام الشوالي"))
        val store = InMemorySportsCommentatorStore(
            StoredSportsCommentators(fixtureDate, Instant.parse("2026-11-02T05:30:00Z"), stored),
        )
        var calls = 0
        val repository = SportsCommentatorRepository({ calls++; fixture }, store, clock)

        repository.refreshIfStale()
        assertThat(calls).isEqualTo(0)
        assertThat(repository.entries.value).isEqualTo(stored)

        val yesterday = InMemorySportsCommentatorStore(
            StoredSportsCommentators(fixtureDate.minusDays(1), Instant.parse("2026-11-02T05:30:00Z"), stored),
        )
        val fresh = SportsCommentatorRepository({ calls++; fixture }, yesterday, clock)
        fresh.refreshIfStale()
        assertThat(calls).isEqualTo(1)
        assertThat(fresh.entries.value).hasSize(3)
    }

    private fun event(channelName: String, start: Instant) = event(listOf(channelName), start)

    private fun event(channelNames: List<String>, start: Instant) = SportsEvent(
        id = "event-${channelNames.joinToString()}-$start", title = "Home vs Away", league = null, categoryLabel = null,
        description = "", sport = SportType.FOOTBALL, startTime = start.toEpochMilli(),
        endTime = start.plus(Duration.ofHours(2)).toEpochMilli(),
        broadcasts = channelNames.mapIndexed { index, name ->
            SportsBroadcast(Channel(id = index + 1L, name = name), combinedProfileId = null)
        },
        confidence = 1f, competitors = emptyList(), session = null,
        eventType = SportsEventType.LIVE_EVENT, rawProgrammeRefs = emptyList(),
    )

    private fun page(cards: String, offset: String = "GMT+2") =
        "<html><body><span>التوقيت $offset</span><ul>$cards</ul></body></html>"

    private fun card(time: String, channel: String, commentator: String) = """
        <li class="item NotStarted"><div class="match-card ">
            <a class="match-state"><span class="match-time">$time</span></a>
        </div><div class="match-footer"><div>
            <span data-dc-tpl="42"><span class="sc-interp">$channel</span>
            <span class="sc-interp">$commentator</span></span>
        </div></div></li>
    """.trimIndent()
}

internal class InMemorySportsCommentatorStore(
    var value: StoredSportsCommentators? = null,
) : SportsCommentatorStore {
    override fun load(): StoredSportsCommentators? = value
    override fun save(value: StoredSportsCommentators) { this.value = value }
}

private class MutableClock(var now: Instant) : Clock() {
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
}
