package com.streamvault.feature.live.sports

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Program
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Test

class HomeLiveMatchesTest {
    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    @Test
    fun `window keeps live matches and matches starting within 30 minutes inclusive`() {
        val bein = channel(1L, "beIN Sport 1 FHD Q")
        val candidates = listOf(
            candidate(bein, "Live A vs B", start = now - 50 * minute, end = now + 40 * minute),
            candidate(bein, "Starts Now vs C", start = now, end = now + 110 * minute),
            candidate(bein, "Edge D vs E", start = now + 30 * minute, end = now + 140 * minute),
            candidate(bein, "Late F vs G", start = now + 30 * minute + 1, end = now + 150 * minute),
            candidate(bein, "Ended H vs I", start = now - 120 * minute, end = now),
            candidate(bein, "Stale J vs K", start = now - 4 * 60 * minute, end = now + 60 * minute),
        )

        val titles = HomeLiveMatchSelector.select(candidates, now, limit = 20).map { it.title }

        assertThat(titles).containsExactly("Live A vs B", "Starts Now vs C", "Edge D vs E").inOrder()
    }

    @Test
    fun `beIN channels come first, then other sports channels, live before upcoming`() {
        val sky = channel(1L, "Sky Sports Football", number = 1)
        val bein2 = channel(2L, "beIN_SPORTS_2_1080p", number = 50)
        val bein1 = channel(3L, "beIN Sport 1 FHD Q", number = 40)
        val candidates = listOf(
            candidate(sky, "Sky Live vs X", start = now - 10 * minute, end = now + 80 * minute),
            candidate(bein2, "Bein Soon vs Y", start = now + 10 * minute, end = now + 100 * minute),
            candidate(bein1, "Bein Live vs Z", start = now - 20 * minute, end = now + 70 * minute),
            candidate(sky, "Sky Soon vs W", start = now + 5 * minute, end = now + 95 * minute),
        )

        val titles = HomeLiveMatchSelector.select(candidates, now, limit = 20).map { it.title }

        assertThat(titles).containsExactly(
            "Bein Live vs Z", "Bein Soon vs Y", "Sky Live vs X", "Sky Soon vs W",
        ).inOrder()
    }

    @Test
    fun `the same match on several feeds is shown once on the Arabic beIN channel`() {
        val xtra = channel(1L, "beIN_SPORTS_1Xtra_1080p", number = 1)
        val main = channel(2L, "beIN Sport 1 FHD Q", number = 9)
        val candidates = listOf(
            candidate(xtra, "Liverpool vs Arsenal", start = now - 5 * minute, end = now + 90 * minute),
            candidate(main, "Liverpool vs Arsenal", start = now - 5 * minute, end = now + 90 * minute),
        )

        val selected = HomeLiveMatchSelector.select(candidates, now, limit = 20)

        assertThat(selected.map { it.channel.id }).containsExactly(2L)
    }

    @Test
    fun `a live Arabic beIN feed comes before a regional one that started earlier`() {
        val french = channel(1L, "beIN Sport 1 FR FHD", number = 1)
        val arabic = channel(2L, "beIN Sport 1 FHD", number = 56)
        val candidates = listOf(
            candidate(french, "Valence vs Real Madrid", start = now - 8 * minute, end = now + 90 * minute),
            candidate(arabic, "Valencia vs Real Madrid", start = now - 5 * minute, end = now + 90 * minute),
        )

        val selected = HomeLiveMatchSelector.select(candidates, now, limit = 20)

        assertThat(selected.map { it.channel.id }).containsExactly(2L, 1L).inOrder()
    }

    @Test
    fun `a live regional beIN feed still comes before an upcoming Arabic one`() {
        val french = channel(1L, "beIN Sport 1 FR FHD", number = 1)
        val arabic = channel(2L, "beIN Sport 2 FHD", number = 59)
        val candidates = listOf(
            candidate(arabic, "Soon A vs B", start = now + 5 * minute, end = now + 95 * minute),
            candidate(french, "Live C vs D", start = now - 5 * minute, end = now + 85 * minute),
        )

        val selected = HomeLiveMatchSelector.select(candidates, now, limit = 20)

        assertThat(selected.map { it.title }).containsExactly("Live C vs D", "Soon A vs B").inOrder()
    }

    @Test
    fun `only competitive football counts - studio shows, replays and other sports are dropped`() {
        val bein = channel(1L, "beIN Sport 1 FHD Q")
        fun candidateFor(title: String, genre: String?) = SportsProgrammeClassifier.classify(
            Program(channelId = "1", title = title, startTime = now, endTime = now + 90 * minute, genre = genre),
            bein,
        ).let { classification ->
            HomeLiveMatchSelector.candidateOf(title, now, now + 90 * minute, bein, classification)
        }

        val match = candidateFor("Liverpool vs Arsenal", "Football")
        assertThat(match).isNotNull()
        assertThat(match!!.title).isEqualTo("Liverpool vs Arsenal")
        assertThat(match.isBein).isTrue()
        assertThat(match.beinNumber).isEqualTo(1)
        assertThat(candidateFor("Premier League: Liverpool vs Arsenal", null)?.league).isEqualTo("Premier League")

        assertThat(candidateFor("Barcelona vs Real Madrid Replay", "Football")).isNull()
        assertThat(candidateFor("Football Highlights", "Sports")).isNull()
        assertThat(candidateFor("Pregame Analysis", "Football")).isNull()
        assertThat(candidateFor("Post Match Show", "Football")).isNull()
        assertThat(candidateFor("beIN SPORTS", null)).isNull()
        assertThat(candidateFor("Lakers vs Celtics", "Basketball")).isNull()
    }

    @Test
    fun `source reads the guide once per reload interval and reuses the list when nothing changed`() = runTest {
        val bein = channel(1L, "beIN Sport 1 FHD Q")
        val loads = mutableListOf<Pair<Long, Long>>()
        var refreshes = 0
        val entries = listOf(
            SportsCommentatorEntry(beinChannel = 1, kickoff = Instant.ofEpochMilli(now + 10 * minute), commentator = "عصام الشوالي"),
        )
        val source = HomeLiveMatchesSource(
            loader = { _, start, end ->
                loads += start to end
                listOf(candidate(bein, "Liverpool vs Arsenal", start = now + 10 * minute, end = now + 120 * minute))
            },
            commentators = { entries },
            refreshCommentators = { refreshes++ },
        )

        val first = source.load(listOf(7L), now)
        val second = source.load(listOf(7L), now + minute)
        val third = source.load(listOf(7L), now + HomeLiveMatchesSource.RELOAD_INTERVAL_MS)

        assertThat(first).hasSize(1)
        assertThat(first.single().commentator).isEqualTo("عصام الشوالي")
        assertThat(second).isSameInstanceAs(first)
        assertThat(loads).hasSize(2)
        assertThat(loads.first().first).isEqualTo(now)
        assertThat(loads.first().second)
            .isEqualTo(now + HomeLiveMatchSelector.SOON_WINDOW_MS + HomeLiveMatchesSource.RELOAD_INTERVAL_MS)
        assertThat(third).isEqualTo(first)
        assertThat(refreshes).isEqualTo(2)
        assertThat(source.load(emptyList(), now)).isEmpty()
    }

    @Test
    fun `a failing guide read yields an empty row instead of an error`() = runTest {
        val source = HomeLiveMatchesSource(
            loader = { _, _, _ -> error("database closed") },
            commentators = { emptyList() },
            refreshCommentators = {},
        )

        assertThat(source.load(listOf(1L), now)).isEmpty()
    }

    @Test
    fun `a failing reload keeps the last row and the next load tries again`() = runTest {
        // F22: one failed read used to blank the row for a whole reload interval.
        val bein = channel(1L, "beIN Sport 1 FHD Q")
        var fail = false
        var loads = 0
        val source = HomeLiveMatchesSource(
            loader = { _, _, _ ->
                loads++
                if (fail) error("database busy")
                listOf(candidate(bein, "Liverpool vs Arsenal", start = now + 10 * minute, end = now + 120 * minute))
            },
            commentators = { emptyList() },
            refreshCommentators = {},
        )
        val shown = source.load(listOf(7L), now)

        fail = true
        val duringFailure = source.load(listOf(7L), now + HomeLiveMatchesSource.RELOAD_INTERVAL_MS)
        fail = false
        val recovered = source.load(listOf(7L), now + HomeLiveMatchesSource.RELOAD_INTERVAL_MS + minute)

        assertThat(shown).hasSize(1)
        assertThat(duringFailure).isEqualTo(shown)
        assertThat(recovered).isEqualTo(shown)
        assertThat(loads).isEqualTo(3)
    }

    private fun candidate(channel: Channel, title: String, start: Long, end: Long) = HomeMatchCandidate(
        channel = channel,
        title = title,
        league = null,
        startTimeMs = start,
        endTimeMs = end,
        isBein = HomeLiveMatchSelector.isBeinChannel(channel.name),
        beinNumber = SportsCommentatorMatcher.beinChannelNumber(channel.name),
        eventKey = title.lowercase() + "@" + start / minute,
    )

    private fun channel(id: Long, name: String, number: Int = 0) = Channel(
        id = id,
        name = name,
        streamUrl = "",
        number = number,
        providerId = 7L,
    )
}
