package com.streamvault.data.sync.favorites

import com.streamvault.data.local.dao.FavoriteSyncDao
import com.streamvault.data.local.entity.VirtualGroupEntity
import com.streamvault.domain.model.ContentType
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Records favorites edits as user intent. Only explicit user actions and backup restores call in;
 * catalog maintenance, M3U reclassification and remote applies write the tables directly and are
 * never recorded, which is what keeps cleanup deletions on one TV from spreading to the others.
 *
 * Implementations run inside the caller's database transaction.
 */
interface FavoriteSyncChangeRecorder {
    suspend fun favoriteAdded(providerId: Long, contentId: Long, contentType: ContentType, groupId: Long?) = Unit

    suspend fun favoriteRemoved(providerId: Long, contentId: Long, contentType: ContentType, groupId: Long?) = Unit

    /** [ordered] is the complete new order of one provider/group partition. */
    suspend fun favoritesReordered(providerId: Long, groupId: Long?, ordered: List<Pair<Long, ContentType>>) = Unit

    /** A group was created or edited; assigns its sync id when missing. */
    suspend fun groupSaved(groupId: Long) = Unit

    /** Called before the row is deleted. */
    suspend fun groupDeleted(group: VirtualGroupEntity) = Unit

    /** A backup restore wrote these scopes; restores spread to other TVs as a fresh change. */
    suspend fun restoreApplied(scopes: Collection<FavoriteSyncRestoreScope>) = Unit

    /** Called after the surrounding transaction committed; schedules the upload. */
    fun changesCommitted() = Unit

    companion object {
        val NONE: FavoriteSyncChangeRecorder = object : FavoriteSyncChangeRecorder {}
    }
}

/** A restored provider/type; replace flags mean the restore cleared that scope first. */
data class FavoriteSyncRestoreScope(
    val providerId: Long,
    val contentType: ContentType,
    val replaceGlobal: Boolean = false,
    val replaceGroups: Boolean = false
)

/** Lets the coordinator upload a few seconds after recorded edits without a dependency cycle. */
@Singleton
class FavoriteSyncChangeSignal @Inject constructor() {
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val events: SharedFlow<Unit> = changes.asSharedFlow()

    fun notifyChanged() {
        changes.tryEmit(Unit)
    }
}

/** How a whole-scope capture of local favorites meets the replica. */
internal data class FavoriteSyncCapturePolicy(
    val removeMissingGlobal: Boolean,
    val removeMissingGroups: Boolean,
    val overwriteOrders: Boolean,
    val overwriteGroupFields: Boolean
) {
    companion object {
        /** First join with Merge: union, the server keeps its order and group details. */
        val MERGE = FavoriteSyncCapturePolicy(false, false, overwriteOrders = false, overwriteGroupFields = false)

        /** First join with "Upload this TV": this TV's lists replace the server's. */
        val UPLOAD = FavoriteSyncCapturePolicy(true, true, overwriteOrders = true, overwriteGroupFields = true)
    }
}

@Singleton
class FavoriteSyncRecorder @Inject constructor(
    private val secretStore: FavoriteSyncSecretStore,
    private val state: FavoriteSyncStateStore,
    private val identity: FavoriteSyncIdentity,
    private val dao: FavoriteSyncDao,
    private val signal: FavoriteSyncChangeSignal,
    private val keyring: FavoriteSyncKeyring
) : FavoriteSyncChangeRecorder {

    override suspend fun favoriteAdded(providerId: Long, contentId: Long, contentType: ContentType, groupId: Long?) {
        setMembership(providerId, contentId, contentType, groupId, present = true)
    }

    override suspend fun favoriteRemoved(providerId: Long, contentId: Long, contentType: ContentType, groupId: Long?) {
        setMembership(providerId, contentId, contentType, groupId, present = false)
    }

    override suspend fun favoritesReordered(providerId: Long, groupId: Long?, ordered: List<Pair<Long, ContentType>>) {
        edit(providerId) {
            val groupUuid = groupId?.let { groupUuid(it) ?: return@edit }
            ordered.groupBy({ FavoriteSyncItemKeys.listType(it.second) }, { it.first })
                .forEach { (listType, contentIds) ->
                    if (listType == null) return@forEach
                    val keysById = identity.itemKeysFor(providerId, listType, contentIds)
                    val listKey = listKey(listType, groupUuid)
                    val localKeys = contentIds.mapNotNull(keysById::get)
                    setOrder(listKey, localKeys.withUnmatchedTail(provider.lists[listKey]))
                }
        }
    }

    override suspend fun groupSaved(groupId: Long) {
        val group = dao.groupById(groupId) ?: return
        val uuid = group.syncUuid ?: UUID.randomUUID().toString().also { dao.setGroupSyncUuid(groupId, it) }
        val listType = FavoriteSyncItemKeys.listType(group.contentType) ?: return
        edit(group.providerId) {
            setGroup(uuid, listType, group.name, group.iconEmoji, group.position)
        }
    }

    override suspend fun groupDeleted(group: VirtualGroupEntity) {
        val uuid = group.syncUuid ?: return
        val listType = FavoriteSyncItemKeys.listType(group.contentType) ?: return
        edit(group.providerId) { deleteGroup(uuid, listType) }
    }

    override suspend fun restoreApplied(scopes: Collection<FavoriteSyncRestoreScope>) {
        scopes.groupBy { it.providerId }.forEach { (providerId, providerScopes) ->
            val byType = providerScopes.groupBy { FavoriteSyncItemKeys.listType(it.contentType) }
            byType.forEach { (listType, typeScopes) ->
                if (listType == null) return@forEach
                edit(providerId) {
                    captureLocal(
                        providerId = providerId,
                        listTypes = setOf(listType),
                        policy = FavoriteSyncCapturePolicy(
                            removeMissingGlobal = typeScopes.any { it.replaceGlobal },
                            removeMissingGroups = typeScopes.any { it.replaceGroups },
                            overwriteOrders = true,
                            overwriteGroupFields = true
                        )
                    )
                }
            }
        }
    }

    override fun changesCommitted() {
        signal.notifyChanged()
    }

    /**
     * Copies the local favorites of [listTypes] into the editor. Used for first join and restores,
     * both explicit user actions; ordinary edits are recorded one by one instead.
     */
    internal suspend fun FavoriteSyncProviderEditor.captureLocal(
        providerId: Long,
        listTypes: Set<String>,
        policy: FavoriteSyncCapturePolicy
    ) {
        val groupUuids = HashMap<Long, String>()
        val claimed = HashSet<String>()
        dao.groups(providerId).forEach { group ->
            val listType = FavoriteSyncItemKeys.listType(group.contentType) ?: return@forEach
            if (listType !in listTypes) return@forEach
            val existing = group.syncUuid?.takeIf { it in provider.groups && it !in claimed }
            // A group first seen by sync adopts a same-named server group so both TVs share it.
            val adopted = existing ?: provider.groups.entries.firstOrNull { (uuid, doc) ->
                uuid !in claimed && !doc.isDeleted && doc.type == listType &&
                    doc.name?.v?.normalizedName() == group.name.normalizedName()
            }?.key
            val uuid = adopted ?: group.syncUuid?.takeIf { it !in claimed } ?: UUID.randomUUID().toString()
            if (uuid != group.syncUuid) dao.setGroupSyncUuid(group.id, uuid)
            claimed += uuid
            groupUuids[group.id] = uuid
            if (policy.overwriteGroupFields || adopted == null) {
                setGroup(uuid, listType, group.name, group.iconEmoji, group.position)
            }
        }
        if (policy.removeMissingGroups) {
            provider.groups.filter { (uuid, doc) -> doc.type in listTypes && !doc.isDeleted && uuid !in claimed }
                .forEach { (uuid, doc) -> deleteGroup(uuid, doc.type) }
        }

        val localLists = LinkedHashMap<String, MutableList<String>>()
        // Lists holding a favorite this TV cannot identify yet: what is absent there proves nothing.
        val incompleteLists = HashSet<String>()
        val incompleteTypes = HashSet<String>()
        dao.localFavorites(providerId).forEach { row ->
            val listType = FavoriteSyncItemKeys.listType(row.contentType) ?: return@forEach
            if (listType !in listTypes) return@forEach
            val groupUuid = row.groupId?.let { groupId ->
                groupUuids[groupId] ?: run {
                    incompleteTypes += listType
                    return@forEach
                }
            }
            val listKey = listKey(listType, groupUuid)
            val itemKey = FavoriteSyncItemKeys.of(row) ?: run {
                incompleteLists += listKey
                return@forEach
            }
            localLists.getOrPut(listKey) { mutableListOf() } += itemKey
        }
        localLists.forEach { (listKey, keys) ->
            keys.forEach { setMember(listKey, it, present = true) }
            if (policy.overwriteOrders || provider.lists[listKey]?.order == null) {
                setOrder(listKey, keys.withUnmatchedTail(provider.lists[listKey]))
            }
        }
        provider.lists.keys.filter { FavoriteSyncListKey.type(it) in listTypes }.forEach { listKey ->
            val isGlobal = FavoriteSyncListKey.groupUuid(listKey) == null
            val remove = if (isGlobal) policy.removeMissingGlobal else policy.removeMissingGroups
            if (!remove || listKey in incompleteLists || FavoriteSyncListKey.type(listKey) in incompleteTypes) return@forEach
            val local = localLists[listKey].orEmpty().toSet()
            val missing = provider.lists[listKey]?.members.orEmpty()
                .filter { (key, member) -> member.present && key !in local }
                .keys
            if (missing.isEmpty()) return@forEach
            // Only items this TV's catalog has are known removals; the rest may simply not be
            // indexed yet (fresh install, catalog still loading), so they stay for the other TVs.
            val known = identity.resolve(providerId, missing).keys
            missing.filter { it in known }.forEach { setMember(listKey, it, present = false) }
        }
    }

    private suspend fun setMembership(
        providerId: Long,
        contentId: Long,
        contentType: ContentType,
        groupId: Long?,
        present: Boolean
    ) {
        val listType = FavoriteSyncItemKeys.listType(contentType) ?: return
        edit(providerId) {
            val groupUuid = groupId?.let { groupUuid(it) ?: return@edit }
            val itemKey = identity.itemKeysFor(providerId, listType, listOf(contentId))[contentId] ?: return@edit
            setMember(listKey(listType, groupUuid), itemKey, present)
        }
    }

    /** Sync id of a local group, assigning one if the group predates sync. */
    private suspend fun groupUuid(groupId: Long): String? {
        val group = dao.groupById(groupId) ?: return null
        return group.syncUuid ?: UUID.randomUUID().toString().also { dao.setGroupSyncUuid(groupId, it) }
    }

    /**
     * Runs [block] against this provider's replica when sync is paired and past its first join.
     * Before the join there is nothing to record: the join captures the whole local state.
     */
    private suspend fun edit(providerId: Long, block: suspend FavoriteSyncProviderEditor.() -> Unit) {
        val config = secretStore.load()
        if (!config.enabled || !config.isComplete) return
        if (state.joinMode() != null) return
        // The salt is known from the first join on; keys are cached by then, so this does not derive.
        val salt = state.salt() ?: return
        val keys = keyring.keys(config, salt) ?: return
        val wireKey = identity.providerWireKey(providerId, keys) ?: return
        val document = state.replica()
        val editor = FavoriteSyncProviderEditor(document.providers[wireKey] ?: FavoriteSyncProviderDoc(), state.clock())
        editor.block()
        if (!editor.changed) return
        state.saveReplica(document.copy(providers = document.providers + (wireKey to editor.provider)))
        state.markLocalChange()
        state.saveClock()
    }

    private fun listKey(listType: String, groupUuid: String?): String =
        groupUuid?.let { FavoriteSyncListKey.group(listType, it) } ?: FavoriteSyncListKey.global(listType)

    /** Keeps items this TV cannot resolve in the order, after the ones it can. */
    private fun List<String>.withUnmatchedTail(previous: FavoriteSyncListDoc?): List<String> {
        if (previous == null) return this
        val local = toSet()
        val tail = FavoriteSyncMerger.orderedPresentKeys(previous).filter { it !in local }
        return this + tail
    }

    private fun String.normalizedName(): String = trim().lowercase(Locale.ROOT)
}
