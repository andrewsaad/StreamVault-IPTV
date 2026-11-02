package com.streamvault.data.sync.favorites

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The shared favorites document. It carries no server URLs, usernames or passwords: providers are
 * keyed by an HMAC of their identity and items by provider-side ids such as `L:<streamId>`.
 *
 * [horizon] is the wall time before which tombstones may have been compacted away. An entry
 * older than the other side's horizon that the other side does not have was deleted there, so
 * the merge drops it instead of resurrecting it.
 */
@Serializable
data class FavoriteSyncDocument(
    val schema: Int = SCHEMA,
    val horizon: Long = 0L,
    val providers: Map<String, FavoriteSyncProviderDoc> = emptyMap()
) {
    companion object {
        const val SCHEMA = 1
        val EMPTY = FavoriteSyncDocument()
    }
}

@Serializable
data class FavoriteSyncProviderDoc(
    /** List key ("LIVE|global" or "LIVE|<groupUuid>") to list state. */
    val lists: Map<String, FavoriteSyncListDoc> = emptyMap(),
    /** Group uuid to group state. */
    val groups: Map<String, FavoriteSyncGroupDoc> = emptyMap()
)

@Serializable
data class FavoriteSyncListDoc(
    val members: Map<String, FavoriteSyncMember> = emptyMap(),
    val order: FavoriteSyncOrder? = null
)

/** Per-item add/remove register; the latest stamp wins. */
@Serializable
data class FavoriteSyncMember(val present: Boolean, val ts: FavoriteSyncHlc)

/** The latest reorder of a list wins as a whole. */
@Serializable
data class FavoriteSyncOrder(val keys: List<String>, val ts: FavoriteSyncHlc)

@Serializable
data class FavoriteSyncLww<T>(val v: T, val ts: FavoriteSyncHlc)

/** Custom favorite group; each field is its own last-writer-wins register. */
@Serializable
data class FavoriteSyncGroupDoc(
    val type: String,
    val name: FavoriteSyncLww<String>? = null,
    val icon: FavoriteSyncLww<String?>? = null,
    val pos: FavoriteSyncLww<Int>? = null,
    val deleted: FavoriteSyncLww<Boolean>? = null
) {
    val isDeleted: Boolean get() = deleted?.v == true

    fun latestTs(): FavoriteSyncHlc? = listOfNotNull(name?.ts, icon?.ts, pos?.ts, deleted?.ts).maxOrNull()
}

/** List keys pair a content type with either the global list or a group uuid. */
object FavoriteSyncListKey {
    const val GLOBAL = "global"

    fun global(type: String): String = "$type|$GLOBAL"

    fun group(type: String, groupUuid: String): String = "$type|$groupUuid"

    fun type(listKey: String): String = listKey.substringBefore('|')

    /** Null for the global list. */
    fun groupUuid(listKey: String): String? = listKey.substringAfter('|').takeUnless { it == GLOBAL }
}

internal object FavoriteSyncJson {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    fun encode(document: FavoriteSyncDocument): String =
        json.encodeToString(FavoriteSyncDocument.serializer(), document)

    fun decode(text: String): FavoriteSyncDocument =
        json.decodeFromString(FavoriteSyncDocument.serializer(), text)
}
