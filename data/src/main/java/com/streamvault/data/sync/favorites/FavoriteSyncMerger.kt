package com.streamvault.data.sync.favorites

/**
 * Pure merge of two favorites documents. The merge is commutative, associative and idempotent
 * for documents that share a horizon, which is what lets every TV merge in any order and reach
 * the same state.
 */
object FavoriteSyncMerger {
    const val TOMBSTONE_TTL_MS: Long = 90L * 24 * 60 * 60 * 1000

    fun merge(a: FavoriteSyncDocument, b: FavoriteSyncDocument): FavoriteSyncDocument {
        val providers = (a.providers.keys + b.providers.keys).associateWith { key ->
            mergeProvider(a.providers[key], a.horizon, b.providers[key], b.horizon)
        }.filterValues { it.lists.isNotEmpty() || it.groups.isNotEmpty() }
        return FavoriteSyncDocument(
            schema = maxOf(a.schema, b.schema),
            horizon = maxOf(a.horizon, b.horizon),
            providers = providers
        )
    }

    /**
     * Drops tombstones older than [TOMBSTONE_TTL_MS] and advances the horizon so peers that still
     * hold those tombstones discard them on their next merge. Run before every upload.
     *
     * Time is measured against the newest stamp in the document, never against this TV's wall
     * clock alone: a TV whose clock runs ahead must not push the horizon past what the household
     * has actually written.
     */
    fun compact(document: FavoriteSyncDocument, nowMs: Long): FavoriteSyncDocument {
        val newest = maxTimestamp(document)?.wallMs ?: return document
        val cutoff = minOf(nowMs, newest) - TOMBSTONE_TTL_MS
        val providers = document.providers.mapValues { (_, provider) ->
            val expiredGroups = provider.groups.filterValues { group ->
                group.isDeleted && (group.deleted?.ts?.wallMs ?: Long.MAX_VALUE) < cutoff
            }.keys
            val groups = provider.groups - expiredGroups
            val lists = provider.lists
                // Lists of expired or unknown groups go too, so orphans cannot linger forever.
                .filterKeys { key -> FavoriteSyncListKey.groupUuid(key)?.let { it in groups } ?: true }
                .mapValues { (_, list) ->
                    list.copy(members = list.members.filterValues { it.present || it.ts.wallMs >= cutoff })
                }
                .filterValues { it.members.isNotEmpty() || it.order != null }
            FavoriteSyncProviderDoc(lists = lists, groups = groups)
        }.filterValues { it.lists.isNotEmpty() || it.groups.isNotEmpty() }
        return document.copy(horizon = maxOf(document.horizon, cutoff), providers = providers)
    }

    /** Present members in display order: the winning reorder first, then unordered adds by add time. */
    fun orderedPresentKeys(list: FavoriteSyncListDoc): List<String> {
        val present = list.members.filterValues { it.present }
        val ordered = list.order?.keys.orEmpty().filter { it in present }.distinct()
        val orderedSet = ordered.toSet()
        val rest = present.entries
            .filter { it.key !in orderedSet }
            .sortedWith(compareBy({ it.value.ts }, { it.key }))
            .map { it.key }
        return ordered + rest
    }

    /** Highest stamp anywhere in the document, used to advance the local clock. */
    fun maxTimestamp(document: FavoriteSyncDocument): FavoriteSyncHlc? =
        document.providers.values.flatMap { provider ->
            provider.lists.values.flatMap { list ->
                list.members.values.map { it.ts } + listOfNotNull(list.order?.ts)
            } + provider.groups.values.mapNotNull { it.latestTs() }
        }.maxOrNull()

    private fun mergeProvider(
        a: FavoriteSyncProviderDoc?,
        aHorizon: Long,
        b: FavoriteSyncProviderDoc?,
        bHorizon: Long
    ): FavoriteSyncProviderDoc {
        val left = a ?: FavoriteSyncProviderDoc()
        val right = b ?: FavoriteSyncProviderDoc()
        val groups = mergeKeyed(left.groups, aHorizon, right.groups, bHorizon, { it.latestTs() }, { !it.isDeleted }, ::mergeGroup)
        val lists = (left.lists.keys + right.lists.keys).associateWith { key ->
            mergeList(left.lists[key], aHorizon, right.lists[key], bHorizon)
        }.filterValues { it.members.isNotEmpty() || it.order != null }
        return FavoriteSyncProviderDoc(lists = lists, groups = groups)
    }

    private fun mergeList(
        a: FavoriteSyncListDoc?,
        aHorizon: Long,
        b: FavoriteSyncListDoc?,
        bHorizon: Long
    ): FavoriteSyncListDoc {
        val members = mergeKeyed(a?.members.orEmpty(), aHorizon, b?.members.orEmpty(), bHorizon, { it.ts }, { it.present }) { x, y ->
            if (x.ts >= y.ts) x else y
        }
        val order = when {
            a?.order != null && b?.order != null -> if (a.order.ts >= b.order.ts) a.order else b.order
            a?.order != null -> a.order.takeUnless { it.ts.wallMs < bHorizon }
            else -> b?.order?.takeUnless { it.ts.wallMs < aHorizon }
        }
        return FavoriteSyncListDoc(members = members, order = order)
    }

    private fun mergeGroup(a: FavoriteSyncGroupDoc, b: FavoriteSyncGroupDoc): FavoriteSyncGroupDoc =
        FavoriteSyncGroupDoc(
            // Type never changes after creation; pick deterministically if two TVs disagree.
            type = maxOf(a.type, b.type),
            name = latest(a.name, b.name),
            icon = latest(a.icon, b.icon),
            pos = latest(a.pos, b.pos),
            deleted = latest(a.deleted, b.deleted)
        )

    private fun <T> latest(a: FavoriteSyncLww<T>?, b: FavoriteSyncLww<T>?): FavoriteSyncLww<T>? = when {
        a == null -> b
        b == null -> a
        a.ts >= b.ts -> a
        else -> b
    }

    /**
     * Union of two keyed maps. A tombstone only one side has is dropped when it is older than the
     * other side's horizon, because the other side already compacted it away. A live entry is
     * never dropped this way: losing a real favorite to a skewed clock is worse than a removal
     * coming back on a TV that stayed offline longer than the tombstone lifetime.
     */
    private inline fun <V> mergeKeyed(
        a: Map<String, V>,
        aHorizon: Long,
        b: Map<String, V>,
        bHorizon: Long,
        ts: (V) -> FavoriteSyncHlc?,
        live: (V) -> Boolean,
        both: (V, V) -> V
    ): Map<String, V> {
        val result = LinkedHashMap<String, V>()
        for (key in (a.keys + b.keys).sorted()) {
            val left = a[key]
            val right = b[key]
            val merged = when {
                left != null && right != null -> both(left, right)
                left != null -> left.takeUnless { !live(it) && (ts(it)?.wallMs ?: 0L) < bHorizon }
                else -> right?.takeUnless { !live(it) && (ts(it)?.wallMs ?: 0L) < aHorizon }
            }
            if (merged != null) result[key] = merged
        }
        return result
    }
}
