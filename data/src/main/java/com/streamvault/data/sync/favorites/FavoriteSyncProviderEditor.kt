package com.streamvault.data.sync.favorites

/**
 * Applies local user intent to one provider's part of the replica, stamping each change with the
 * hybrid clock. Writes that would not change the visible state are skipped so idle edits do not
 * dirty the document.
 */
class FavoriteSyncProviderEditor(
    initial: FavoriteSyncProviderDoc,
    private val clock: FavoriteSyncClock
) {
    var provider: FavoriteSyncProviderDoc = initial
        private set

    var changed: Boolean = false
        private set

    fun isPresent(listKey: String, itemKey: String): Boolean =
        provider.lists[listKey]?.members?.get(itemKey)?.present == true

    fun setMember(listKey: String, itemKey: String, present: Boolean) {
        val list = provider.lists[listKey] ?: FavoriteSyncListDoc()
        if (list.members[itemKey]?.present == present) return
        val updated = list.copy(members = list.members + (itemKey to FavoriteSyncMember(present, clock.now())))
        provider = provider.copy(lists = provider.lists + (listKey to updated))
        changed = true
    }

    fun setOrder(listKey: String, keys: List<String>) {
        val list = provider.lists[listKey] ?: FavoriteSyncListDoc()
        val distinct = keys.distinct()
        if (list.order?.keys == distinct) return
        provider = provider.copy(lists = provider.lists + (listKey to list.copy(order = FavoriteSyncOrder(distinct, clock.now()))))
        changed = true
    }

    /** Sets group fields that differ from the replica; a live group is never left marked deleted. */
    fun setGroup(uuid: String, type: String, name: String, icon: String?, position: Int) {
        val current = provider.groups[uuid] ?: FavoriteSyncGroupDoc(type = type)
        var group = current.copy(type = type)
        if (current.name?.v != name) group = group.copy(name = FavoriteSyncLww(name, clock.now()))
        if (current.icon == null || current.icon.v != icon) group = group.copy(icon = FavoriteSyncLww(icon, clock.now()))
        if (current.pos?.v != position) group = group.copy(pos = FavoriteSyncLww(position, clock.now()))
        if (current.isDeleted) group = group.copy(deleted = FavoriteSyncLww(false, clock.now()))
        if (group != current) {
            provider = provider.copy(groups = provider.groups + (uuid to group))
            changed = true
        }
    }

    fun deleteGroup(uuid: String, type: String) {
        val current = provider.groups[uuid] ?: FavoriteSyncGroupDoc(type = type)
        if (current.isDeleted) return
        provider = provider.copy(groups = provider.groups + (uuid to current.copy(deleted = FavoriteSyncLww(true, clock.now()))))
        changed = true
    }
}
