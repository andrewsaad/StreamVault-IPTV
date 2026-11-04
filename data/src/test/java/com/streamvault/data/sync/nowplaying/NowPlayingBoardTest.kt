package com.streamvault.data.sync.nowplaying

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.sync.OtherViewer
import org.junit.Test

class NowPlayingBoardTest {
    @Test
    fun `refreshing the same channel keeps the start time and a new channel resets it`() {
        val started = NowPlayingBoard.upsert(NowPlayingDocument.EMPTY, "a", "Kitchen", "beIN 1", 1_000L)
        val refreshed = NowPlayingBoard.upsert(started, "a", "Kitchen", "beIN 1", 61_000L)
        val switched = NowPlayingBoard.upsert(refreshed, "a", "Kitchen", "beIN 2", 62_000L)

        assertThat(refreshed.devices.getValue("a")).isEqualTo(NowPlayingEntry("Kitchen", "beIN 1", 1_000L, 61_000L))
        assertThat(switched.devices.getValue("a").sinceMs).isEqualTo(62_000L)
    }

    @Test
    fun `coming back after the entry went stale counts as a new start`() {
        val started = NowPlayingBoard.upsert(NowPlayingDocument.EMPTY, "a", "Kitchen", "beIN 1", 0L)
        val later = NowPlayingBoard.upsert(started, "a", "Kitchen", "beIN 1", NowPlayingBoard.FRESH_MS)

        assertThat(later.devices.getValue("a").sinceMs).isEqualTo(NowPlayingBoard.FRESH_MS)
    }

    @Test
    fun `other viewer skips this TV and stale TVs and picks the latest start`() {
        val now = 10 * 60_000L
        val board = NowPlayingDocument(
            devices = mapOf(
                "me" to NowPlayingEntry("Living Room", "beIN 1", now - 1_000L, now),
                "old" to NowPlayingEntry("Game Room", "beIN 3", now - 9 * 60_000L, now - 4 * 60_000L),
                "early" to NowPlayingEntry("Bedroom", "MBC", now - 5 * 60_000L, now - 10_000L),
                "late" to NowPlayingEntry("Kitchen", "beIN 2", now - 60_000L, now - 20_000L)
            )
        )

        assertThat(NowPlayingBoard.otherViewer(board, "me", now))
            .isEqualTo(OtherViewer("Kitchen", "beIN 2", now - 60_000L))
        assertThat(NowPlayingBoard.otherViewer(board.copy(devices = board.devices.filterKeys { it == "me" }), "me", now))
            .isNull()
    }

    @Test
    fun `writes drop entries older than the prune age and remove takes this TV off`() {
        val now = 2 * NowPlayingBoard.PRUNE_MS
        val board = NowPlayingDocument(
            devices = mapOf(
                "gone" to NowPlayingEntry("Game Room", "beIN 3", 0L, now - NowPlayingBoard.PRUNE_MS),
                "me" to NowPlayingEntry("Kitchen", "beIN 1", now - 1_000L, now - 1_000L)
            )
        )

        assertThat(NowPlayingBoard.remove(board, "me", now).devices).isEmpty()
    }

    @Test
    fun `the board round trips through json and ignores unknown fields`() {
        val board = NowPlayingBoard.upsert(NowPlayingDocument.EMPTY, "a", "Kitchen", "beIN 1", 5L)

        assertThat(NowPlayingBoard.decode(NowPlayingBoard.encode(board))).isEqualTo(board)
        assertThat(NowPlayingBoard.decode("""{"schema":1,"devices":{},"future":true}""")).isEqualTo(NowPlayingDocument.EMPTY)
    }
}
