package com.streamvault.data.health

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.ChannelHealthRecord
import com.streamvault.domain.model.ChannelHealthRules
import com.streamvault.domain.model.ChannelHealthStatus
import org.junit.Test

class ChannelHealthSnapshotTest {
    // Database ids (1..) deliberately differ from stream ids (100..): a snapshot must be keyed by stream id.
    private val channels = listOf(
        SnapshotChannel(id = 1, streamId = 100, name = "News One"),
        SnapshotChannel(id = 2, streamId = 200, name = "Sport  Two"),
        SnapshotChannel(id = 3, streamId = 300, name = "Movies Three"),
        SnapshotChannel(id = 4, streamId = 400, name = "New Channel"),
    )

    private fun document() = ChannelHealthDocument(
        records = mapOf(
            3L to ChannelHealthRecord(3L, ChannelHealthStatus.DEAD, checkedAt = 5_000L, failedDays = 2),
        ),
        knownIds = setOf(1L, 2L, 3L),
    )

    @Test
    fun `build lists known working channels as ok and failed ones as bad, by stream id`() {
        val snapshot = ChannelHealthSnapshots.build(channels, document(), label = " Vendor ", now = 9_000L)

        assertThat(snapshot.ok.map { it.i }).containsExactly(100L, 200L).inOrder()
        assertThat(snapshot.bad).containsExactly(SnapshotBad(300L, "Movies Three", "dead", 2, 5_000L))
        assertThat(snapshot.label).isEqualTo("Vendor")
        assertThat(snapshot.createdAt).isEqualTo(9_000L)
    }

    @Test
    fun `a channel never answered is in neither list`() {
        val snapshot = ChannelHealthSnapshots.build(channels, document(), "", 0L)

        assertThat(snapshot.ok.map { it.i }).doesNotContain(400L)
        assertThat(snapshot.bad.map { it.i }).doesNotContain(400L)
    }

    @Test
    fun `an unreadable or newer-format file decodes to null`() {
        assertThat(ChannelHealthSnapshots.decode("not json")).isNull()
        assertThat(ChannelHealthSnapshots.decode("""{"format":99,"ok":[],"bad":[]}""")).isNull()
        assertThat(ChannelHealthSnapshots.decode("""{"format":0}""")).isNull()
    }

    @Test
    fun `the file round trips and carries no address, login or url`() {
        val snapshot = ChannelHealthSnapshots.build(channels, document(), "Vendor", 9_000L)

        val text = ChannelHealthSnapshots.encode(snapshot)

        assertThat(ChannelHealthSnapshots.decode(text)).isEqualTo(snapshot)
        assertThat(text).doesNotContain("http")
        assertThat(text).doesNotContain("password")
    }

    @Test
    fun `import on another device maps stream ids to that device's channel ids`() {
        val snapshot = ChannelHealthSnapshots.build(channels, document(), "", 0L)
        val other = listOf(
            SnapshotChannel(id = 70, streamId = 100, name = "news one"),
            SnapshotChannel(id = 71, streamId = 200, name = "Sport Two"),
            SnapshotChannel(id = 72, streamId = 300, name = "Movies Three"),
        )

        val plan = ChannelHealthSnapshots.planImport(snapshot, other, ChannelHealthDocument())

        assertThat(plan.knownIds).containsExactly(70L, 71L, 72L)
        assertThat(plan.summary).isEqualTo(SnapshotImportSummary(okImported = 2, badImported = 1))
    }

    @Test
    fun `imported failures are hints and never hide suggestions`() {
        val snapshot = ChannelHealthSnapshots.build(channels, document(), "", 0L)

        val plan = ChannelHealthSnapshots.planImport(snapshot, channels, ChannelHealthDocument())

        val hint = plan.hints.single()
        assertThat(hint.channelId).isEqualTo(3L)
        assertThat(hint.failedDays).isEqualTo(0)
        assertThat(ChannelHealthRules.isSuggestion(hint)).isFalse()
    }

    @Test
    fun `a hint becomes a suggestion only after local failures on two separate days`() {
        val hint = ChannelHealthRecord(3L, ChannelHealthStatus.DEAD, checkedAt = 0L, failedDays = 0)
        val day = 24L * 60L * 60L * 1000L

        val first = ChannelHealthTracker.apply(hint, 3L, ChannelHealthStatus.DEAD, day, java.time.ZoneOffset.UTC)!!
        val second = ChannelHealthTracker.apply(first, 3L, ChannelHealthStatus.DEAD, 2 * day, java.time.ZoneOffset.UTC)!!

        assertThat(ChannelHealthRules.isSuggestion(first)).isFalse()
        assertThat(ChannelHealthRules.isSuggestion(second)).isTrue()
    }

    @Test
    fun `import skips unknown ids, different names and channels already answered here`() {
        val snapshot = ChannelHealthSnapshot(
            ok = listOf(
                SnapshotOk(100, ChannelHealthSnapshots.nameHash("News One")),
                SnapshotOk(999, 1),
                SnapshotOk(200, ChannelHealthSnapshots.nameHash("Some Other Channel")),
            ),
            bad = listOf(SnapshotBad(300, "Movies Three", "dead", 2, 1L), SnapshotBad(400, "New Channel", "weird", 1, 1L)),
        )
        val local = ChannelHealthDocument(knownIds = setOf(1L))

        val plan = ChannelHealthSnapshots.planImport(snapshot, channels, local)

        assertThat(plan.knownIds).containsExactly(3L)
        assertThat(plan.summary).isEqualTo(
            SnapshotImportSummary(okImported = 0, badImported = 1, alreadyKnown = 1, unknown = 1, nameMismatch = 1)
        )
    }
}
