package com.streamvault.data.sync.favorites

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FavoriteSyncMergerTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_000L * day

    private fun ts(wall: Long, device: String = "a", counter: Int = 0) = FavoriteSyncHlc(wall, counter, device)

    private fun doc(
        lists: Map<String, FavoriteSyncListDoc> = emptyMap(),
        groups: Map<String, FavoriteSyncGroupDoc> = emptyMap(),
        horizon: Long = 0L
    ) = FavoriteSyncDocument(horizon = horizon, providers = mapOf("p" to FavoriteSyncProviderDoc(lists, groups)))

    private fun members(vararg entries: Pair<String, FavoriteSyncMember>) =
        FavoriteSyncListDoc(members = entries.toMap())

    private val global = FavoriteSyncListKey.global("LIVE")

    @Test
    fun `concurrent add and remove of the same item keeps the later stamp`() {
        val add = doc(mapOf(global to members("L:1" to FavoriteSyncMember(true, ts(now, "a")))))
        val remove = doc(mapOf(global to members("L:1" to FavoriteSyncMember(false, ts(now + 1, "b")))))

        val merged = FavoriteSyncMerger.merge(add, remove)

        assertThat(merged.providers.getValue("p").lists.getValue(global).members.getValue("L:1").present).isFalse()
    }

    @Test
    fun `equal wall times are ordered by device id so both sides agree`() {
        val a = doc(mapOf(global to members("L:1" to FavoriteSyncMember(true, ts(now, "a")))))
        val b = doc(mapOf(global to members("L:1" to FavoriteSyncMember(false, ts(now, "b")))))

        assertThat(FavoriteSyncMerger.merge(a, b)).isEqualTo(FavoriteSyncMerger.merge(b, a))
        assertThat(FavoriteSyncMerger.merge(a, b).providers.getValue("p").lists.getValue(global).members.getValue("L:1").present)
            .isFalse()
    }

    @Test
    fun `adds on different TVs both survive`() {
        val a = doc(mapOf(global to members("L:1" to FavoriteSyncMember(true, ts(now, "a")))))
        val b = doc(mapOf(global to members("L:2" to FavoriteSyncMember(true, ts(now, "b")))))

        val merged = FavoriteSyncMerger.merge(a, b)

        assertThat(merged.providers.getValue("p").lists.getValue(global).members.keys).containsExactly("L:1", "L:2")
    }

    @Test
    fun `merge is commutative associative and idempotent`() {
        val a = doc(
            mapOf(global to FavoriteSyncListDoc(
                members = mapOf("L:1" to FavoriteSyncMember(true, ts(now, "a")), "L:2" to FavoriteSyncMember(false, ts(now + 5, "a"))),
                order = FavoriteSyncOrder(listOf("L:1"), ts(now + 1, "a"))
            )),
            mapOf("g1" to FavoriteSyncGroupDoc("LIVE", name = FavoriteSyncLww("Sports", ts(now, "a"))))
        )
        val b = doc(
            mapOf(global to FavoriteSyncListDoc(
                members = mapOf("L:2" to FavoriteSyncMember(true, ts(now + 2, "b")), "L:3" to FavoriteSyncMember(true, ts(now, "b"))),
                order = FavoriteSyncOrder(listOf("L:3", "L:2"), ts(now + 3, "b"))
            )),
            mapOf("g1" to FavoriteSyncGroupDoc("LIVE", name = FavoriteSyncLww("Sport", ts(now + 9, "b")), icon = FavoriteSyncLww(null, ts(now, "b"))))
        )
        val c = doc(mapOf(global to members("L:1" to FavoriteSyncMember(false, ts(now + 7, "c")))))

        assertThat(FavoriteSyncMerger.merge(a, b)).isEqualTo(FavoriteSyncMerger.merge(b, a))
        assertThat(FavoriteSyncMerger.merge(FavoriteSyncMerger.merge(a, b), c))
            .isEqualTo(FavoriteSyncMerger.merge(a, FavoriteSyncMerger.merge(b, c)))
        val ab = FavoriteSyncMerger.merge(a, b)
        assertThat(FavoriteSyncMerger.merge(ab, ab)).isEqualTo(ab)
        assertThat(FavoriteSyncMerger.merge(ab, a)).isEqualTo(ab)
        assertThat(ab.providers.getValue("p").groups.getValue("g1").name?.v).isEqualTo("Sport")
    }

    @Test
    fun `latest reorder wins and later adds are appended by add time`() {
        val list = FavoriteSyncListDoc(
            members = mapOf(
                "L:1" to FavoriteSyncMember(true, ts(now, "a")),
                "L:2" to FavoriteSyncMember(true, ts(now, "a", 1)),
                "L:3" to FavoriteSyncMember(true, ts(now + 10, "b")),
                "L:4" to FavoriteSyncMember(false, ts(now + 11, "b")),
                "L:5" to FavoriteSyncMember(true, ts(now + 12, "b"))
            ),
            order = FavoriteSyncOrder(listOf("L:2", "L:4", "L:1"), ts(now + 5, "a"))
        )

        assertThat(FavoriteSyncMerger.orderedPresentKeys(list)).containsExactly("L:2", "L:1", "L:3", "L:5").inOrder()
    }

    @Test
    fun `group fields merge independently`() {
        val a = doc(groups = mapOf("g" to FavoriteSyncGroupDoc("LIVE", name = FavoriteSyncLww("A", ts(now + 5, "a")), pos = FavoriteSyncLww(1, ts(now, "a")))))
        val b = doc(groups = mapOf("g" to FavoriteSyncGroupDoc("LIVE", name = FavoriteSyncLww("B", ts(now, "b")), pos = FavoriteSyncLww(9, ts(now + 5, "b")))))

        val group = FavoriteSyncMerger.merge(a, b).providers.getValue("p").groups.getValue("g")

        assertThat(group.name?.v).isEqualTo("A")
        assertThat(group.pos?.v).isEqualTo(9)
    }

    @Test
    fun `compaction drops old tombstones and a stale peer cannot bring the tombstone back`() {
        val oldRemove = FavoriteSyncMember(false, ts(now - 100 * day, "b"))
        val server = FavoriteSyncMerger.compact(
            doc(mapOf(global to members("L:1" to oldRemove, "L:2" to FavoriteSyncMember(true, ts(now - day, "b"))))),
            now
        )

        assertThat(server.providers.getValue("p").lists.getValue(global).members.keys).containsExactly("L:2")
        assertThat(server.horizon).isEqualTo(now - day - FavoriteSyncMerger.TOMBSTONE_TTL_MS)

        // A TV offline for months still holds an expired tombstone; the horizon drops it on merge.
        val stalePeer = doc(mapOf(global to members(
            "L:5" to FavoriteSyncMember(false, ts(now - 120 * day, "c")),
            "L:9" to FavoriteSyncMember(true, ts(now - 1 * day, "c"))
        )))
        val merged = FavoriteSyncMerger.merge(stalePeer, server)

        assertThat(merged.providers.getValue("p").lists.getValue(global).members.keys).containsExactly("L:2", "L:9")
        assertThat(FavoriteSyncMerger.merge(server, stalePeer)).isEqualTo(merged)
    }

    @Test
    fun `a live add older than the other side's horizon is kept`() {
        // Accepted trade-off: an add the other side never saw survives, even if that means a removal
        // made more than 90 days ago comes back on a TV that was offline the whole time.
        val server = doc(mapOf(global to members("L:2" to FavoriteSyncMember(true, ts(now, "b")))), horizon = now - day)
        val peer = doc(mapOf(global to members("L:1" to FavoriteSyncMember(true, ts(now - 10 * day, "c")))))

        val merged = FavoriteSyncMerger.merge(peer, server)

        assertThat(merged.providers.getValue("p").lists.getValue(global).members.keys).containsExactly("L:1", "L:2")
        assertThat(FavoriteSyncMerger.merge(server, peer)).isEqualTo(merged)
    }

    @Test
    fun `a fast wall clock cannot move the horizon past the newest stamp in the document`() {
        val newest = now - 10 * day
        val document = doc(mapOf(global to members(
            "L:1" to FavoriteSyncMember(true, ts(newest)),
            "L:2" to FavoriteSyncMember(false, ts(newest - 50 * day))
        )))

        val compacted = FavoriteSyncMerger.compact(document, nowMs = now + 300 * day)

        assertThat(compacted.horizon).isEqualTo(newest - FavoriteSyncMerger.TOMBSTONE_TTL_MS)
        assertThat(compacted.providers.getValue("p").lists.getValue(global).members.keys).containsExactly("L:1", "L:2")
    }

    @Test
    fun `compaction of an empty document keeps its horizon`() {
        val empty = FavoriteSyncDocument(horizon = 5L)

        assertThat(FavoriteSyncMerger.compact(empty, now)).isEqualTo(empty)
    }

    @Test
    fun `compaction removes expired deleted groups and their lists`() {
        val groupKey = FavoriteSyncListKey.group("LIVE", "g")
        val compacted = FavoriteSyncMerger.compact(
            doc(
                mapOf(
                    groupKey to members("L:1" to FavoriteSyncMember(true, ts(now - 200 * day))),
                    global to members("L:2" to FavoriteSyncMember(true, ts(now)))
                ),
                mapOf("g" to FavoriteSyncGroupDoc("LIVE", deleted = FavoriteSyncLww(true, ts(now - 95 * day))))
            ),
            now
        )

        val provider = compacted.providers.getValue("p")
        assertThat(provider.groups).isEmpty()
        assertThat(provider.lists.keys).containsExactly(global)
    }

    @Test
    fun `recent tombstones survive compaction`() {
        val compacted = FavoriteSyncMerger.compact(doc(mapOf(global to members("L:1" to FavoriteSyncMember(false, ts(now - day))))), now)

        assertThat(compacted.providers.getValue("p").lists.getValue(global).members.getValue("L:1").present).isFalse()
    }

    @Test
    fun `document json round trips including null icons`() {
        val original = doc(
            mapOf(global to FavoriteSyncListDoc(mapOf("S:abc" to FavoriteSyncMember(true, ts(now))), FavoriteSyncOrder(listOf("S:abc"), ts(now)))),
            mapOf("g" to FavoriteSyncGroupDoc("SERIES", name = FavoriteSyncLww("Kids", ts(now)), icon = FavoriteSyncLww(null, ts(now))))
        )

        assertThat(FavoriteSyncJson.decode(FavoriteSyncJson.encode(original))).isEqualTo(original)
    }
}
