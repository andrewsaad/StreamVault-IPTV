package com.streamvault.data.sync.favorites

import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.FavoriteDao
import com.streamvault.data.local.dao.FavoriteSyncDao
import com.streamvault.data.local.entity.FavoriteEntity
import com.streamvault.data.local.entity.VirtualGroupEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes the merged document into the local favorites tables. Each provider is applied in its own
 * transaction; on the sync path the engine wraps all of them in its integrate transaction, so
 * they commit or roll back together with the replica. The catalog-commit path applies per provider.
 *
 * Items whose content is not in this TV's catalog yet stay in the document and are counted as
 * unmatched; the catalog-commit hook re-runs the apply so they appear once the catalog has them.
 * Local favorites the document has never heard of are left alone unless [authoritative] is set.
 */
@Singleton
class FavoriteSyncApplier @Inject constructor(
    private val dao: FavoriteSyncDao,
    private val favoriteDao: FavoriteDao,
    private val identity: FavoriteSyncIdentity,
    private val transactionRunner: DatabaseTransactionRunner
) {
    /**
     * Applies [document] to the providers in [providerKeys] (local id to wire key) and returns the
     * number of present items that could not be matched. Providers whose wire key is in
     * [authoritativeProviders] drop local favorites and groups the document does not have.
     * Providers the document does not mention are never touched.
     */
    suspend fun apply(
        document: FavoriteSyncDocument,
        providerKeys: Map<Long, String>,
        authoritativeProviders: Set<String> = emptySet()
    ): Int = providerKeys.entries.sumOf { (providerId, wireKey) ->
        val providerDoc = document.providers[wireKey] ?: return@sumOf 0
        transactionRunner.inTransaction { applyProvider(providerId, providerDoc, wireKey in authoritativeProviders) }
    }

    private suspend fun applyProvider(
        providerId: Long,
        doc: FavoriteSyncProviderDoc,
        authoritative: Boolean
    ): Int {
        val groupsByUuid = applyGroups(providerId, doc, authoritative)
        val uuidByGroupId = groupsByUuid.entries.associate { (uuid, group) -> group.id to uuid }

        val rowsByList = HashMap<String, MutableList<Pair<Long, String?>>>()
        val positionById = HashMap<Long, Int>()
        dao.localFavorites(providerId).forEach { row ->
            val listType = FavoriteSyncItemKeys.listType(row.contentType) ?: return@forEach
            val listKey = when (val groupId = row.groupId) {
                null -> FavoriteSyncListKey.global(listType)
                else -> FavoriteSyncListKey.group(listType, uuidByGroupId[groupId] ?: return@forEach)
            }
            rowsByList.getOrPut(listKey) { mutableListOf() } += row.id to FavoriteSyncItemKeys.of(row)
            positionById[row.id] = row.position
        }

        val wanted = doc.lists.values.flatMap { list -> list.members.filterValues { it.present }.keys }
        val resolved = identity.resolve(providerId, wanted)
        var unmatched = 0

        doc.lists.forEach { (listKey, list) ->
            val listType = FavoriteSyncListKey.type(listKey)
            val groupId = FavoriteSyncListKey.groupUuid(listKey)?.let { uuid ->
                groupsByUuid[uuid]?.id ?: return@forEach
            }
            val current = rowsByList.remove(listKey).orEmpty()
            val keptByKey = HashMap<String, Long>()
            val unknownRows = mutableListOf<Long>()
            current.forEach { (rowId, itemKey) ->
                val member = itemKey?.let(list.members::get)
                when {
                    itemKey == null -> unknownRows += rowId
                    member == null -> if (authoritative) dao.deleteFavorite(rowId) else unknownRows += rowId
                    !member.present -> dao.deleteFavorite(rowId)
                    // A second row for the same item (legacy VOD and MOVIE rows) keeps its place.
                    itemKey in keptByKey -> unknownRows += rowId
                    else -> keptByKey[itemKey] = rowId
                }
            }
            var index = 0
            FavoriteSyncMerger.orderedPresentKeys(list).forEach { itemKey ->
                val position = index * POSITION_STEP
                val existing = keptByKey[itemKey]
                val contentId = resolved[itemKey]
                when {
                    existing != null -> {
                        if (positionById[existing] != position) dao.updateFavoritePosition(existing, position)
                        index++
                    }
                    contentId != null -> {
                        favoriteDao.insert(
                            FavoriteEntity(
                                providerId = providerId,
                                contentId = contentId,
                                contentType = FavoriteSyncItemKeys.contentType(listType),
                                position = position,
                                groupId = groupId,
                                addedAt = list.members[itemKey]?.ts?.wallMs ?: System.currentTimeMillis()
                            )
                        )
                        index++
                    }
                    else -> unmatched++
                }
            }
            // Favorites the document does not know keep their relative order after synced ones.
            unknownRows.forEach { rowId ->
                val position = index++ * POSITION_STEP
                if (positionById[rowId] != position) dao.updateFavoritePosition(rowId, position)
            }
        }
        if (authoritative) {
            // Lists the server copy does not have at all are emptied when this TV adopts it.
            // Rows this TV cannot identify yet are kept, as they are in lists the copy does have.
            rowsByList.values.flatten().forEach { (rowId, itemKey) -> if (itemKey != null) dao.deleteFavorite(rowId) }
        }
        return unmatched
    }

    /** Creates, updates and deletes local groups; returns the live groups by sync id. */
    private suspend fun applyGroups(
        providerId: Long,
        doc: FavoriteSyncProviderDoc,
        authoritative: Boolean
    ): Map<String, VirtualGroupEntity> {
        val local = dao.groups(providerId)
        val byUuid = local.filter { it.syncUuid != null }.associateByTo(HashMap()) { it.syncUuid!! }
        doc.groups.forEach { (uuid, group) ->
            val existing = byUuid[uuid]
            if (group.isDeleted) {
                if (existing != null) {
                    // Members leave with the group; the deleting TV re-added them to the global list.
                    dao.deleteFavoritesInGroup(existing.id)
                    dao.deleteGroup(existing.id)
                    byUuid.remove(uuid)
                }
                return@forEach
            }
            val name = group.name?.v ?: existing?.name ?: return@forEach
            val icon = if (group.icon != null) group.icon.v else existing?.iconEmoji
            val position = group.pos?.v ?: existing?.position ?: 0
            if (existing == null) {
                val entity = VirtualGroupEntity(
                    providerId = providerId,
                    name = name,
                    iconEmoji = icon,
                    position = position,
                    contentType = FavoriteSyncItemKeys.contentType(group.type),
                    syncUuid = uuid
                )
                byUuid[uuid] = entity.copy(id = dao.insertGroup(entity))
            } else if (existing.name != name || existing.iconEmoji != icon || existing.position != position) {
                dao.updateGroup(existing.id, name, icon, position)
                byUuid[uuid] = existing.copy(name = name, iconEmoji = icon, position = position)
            }
        }
        if (authoritative) {
            local.filter { it.syncUuid == null || it.syncUuid !in doc.groups }.forEach { group ->
                dao.deleteFavoritesInGroup(group.id)
                dao.deleteGroup(group.id)
                group.syncUuid?.let(byUuid::remove)
            }
        }
        return byUuid
    }

    private companion object {
        const val POSITION_STEP = 1_024
    }
}
