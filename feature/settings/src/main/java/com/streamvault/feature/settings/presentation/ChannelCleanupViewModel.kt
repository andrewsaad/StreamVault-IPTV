package com.streamvault.feature.settings.presentation

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.domain.model.ChannelHealthPauseReason
import com.streamvault.domain.model.ChannelHealthScanState
import com.streamvault.domain.model.ChannelHealthStatus
import com.streamvault.domain.model.ChannelHealthSuggestion
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.ChannelHealthRepository
import com.streamvault.feature.settings.R
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the scan card shows. */
sealed interface ChannelCleanupScanUi {
    data class Idle(val lastFinishedAt: Long?) : ChannelCleanupScanUi
    data class Running(val scanned: Int, val total: Int) : ChannelCleanupScanUi
    data class Paused(
        val reason: ChannelHealthPauseReason,
        val scanned: Int,
        val total: Int,
    ) : ChannelCleanupScanUi
}

data class ChannelCleanupRow(
    val channelId: Long,
    val name: String,
    val status: ChannelHealthStatus,
    val failedDays: Int,
)

/** One category of suggestions; [categoryName] is null for channels without a category. */
data class ChannelCleanupGroup(
    val categoryName: String?,
    val rows: List<ChannelCleanupRow>,
) {
    val channelIds: Set<Long> get() = rows.mapTo(HashSet()) { it.channelId }
}

data class ChannelCleanupUiState(
    val scan: ChannelCleanupScanUi = ChannelCleanupScanUi.Idle(null),
    val groups: List<ChannelCleanupGroup> = emptyList(),
    val selectedIds: Set<Long> = emptySet(),
    val showConfirm: Boolean = false,
    @StringRes val errorRes: Int? = null,
) {
    val allIds: Set<Long> get() = groups.flatMapTo(HashSet()) { it.channelIds }
    val selectedCount: Int get() = selectedIds.size
    val allSelected: Boolean get() = selectedIds.isNotEmpty() && selectedIds.containsAll(allIds)
    fun isGroupSelected(group: ChannelCleanupGroup): Boolean =
        group.rows.isNotEmpty() && selectedIds.containsAll(group.channelIds)
}

internal fun mapChannelCleanupScan(state: ChannelHealthScanState): ChannelCleanupScanUi {
    val paused = state.paused
    return when {
        paused != null -> ChannelCleanupScanUi.Paused(paused, state.scanned, state.total)
        state.running -> ChannelCleanupScanUi.Running(state.scanned, state.total)
        else -> ChannelCleanupScanUi.Idle(state.lastFinishedAt)
    }
}

/**
 * Groups suggestions by category in the order the repository delivered them; channels without a
 * category (null or blank name) share one group that comes last.
 */
internal fun groupChannelCleanupSuggestions(
    suggestions: List<ChannelHealthSuggestion>,
): List<ChannelCleanupGroup> {
    val byCategory = LinkedHashMap<String?, MutableList<ChannelCleanupRow>>()
    suggestions.forEach { suggestion ->
        val key = suggestion.channel.categoryName?.takeIf { it.isNotBlank() }
        byCategory.getOrPut(key) { mutableListOf() }.add(
            ChannelCleanupRow(
                channelId = suggestion.channel.id,
                name = suggestion.channel.name,
                status = suggestion.record.status,
                failedDays = suggestion.record.failedDays,
            )
        )
    }
    val named = byCategory.filterKeys { it != null }.map { (name, rows) -> ChannelCleanupGroup(name, rows) }
    val uncategorized = byCategory[null]?.let { ChannelCleanupGroup(null, it) }
    return if (uncategorized == null) named else named + uncategorized
}

@StringRes
internal fun channelCleanupStatusLabelRes(status: ChannelHealthStatus): Int = when (status) {
    ChannelHealthStatus.EMPTY -> R.string.channel_cleanup_status_empty
    else -> R.string.channel_cleanup_status_dead
}

@StringRes
internal fun channelCleanupPausedLabelRes(reason: ChannelHealthPauseReason): Int = when (reason) {
    ChannelHealthPauseReason.PLAYBACK_ACTIVE -> R.string.channel_cleanup_paused_playback
    ChannelHealthPauseReason.PROVIDER_BACKOFF -> R.string.channel_cleanup_paused_backoff
}

/**
 * The repository's error text is technical English, so any failure to start a scan maps to the one
 * localized message (the only expected cause is a provider type that cannot be scanned).
 */
@StringRes
internal fun channelCleanupStartErrorRes(result: Result<Unit>): Int? =
    if (result is Result.Error) R.string.channel_cleanup_error_unsupported else null

/** Settings > provider > Channel clean-up. Talks only to [ChannelHealthRepository]. */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelCleanupViewModel @Inject constructor(
    private val repository: ChannelHealthRepository,
) : ViewModel() {
    private val providerId = MutableStateFlow<Long?>(null)
    private val selected = MutableStateFlow<Set<Long>>(emptySet())
    private val showConfirm = MutableStateFlow(false)
    private val errorRes = MutableStateFlow<Int?>(null)

    private val scan = providerId.flatMapLatest { id ->
        if (id == null) flowOf(ChannelHealthScanState()) else repository.observeScanState(id)
    }
    private val groups = providerId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else repository.observeSuggestions(id)
    }.onEach { suggestions ->
        // Drop ids that are no longer suggested from the stored selection too, so a channel that
        // comes back later does not return already ticked.
        val present = suggestions.mapTo(HashSet()) { it.channel.id }
        selected.update { current -> if (present.containsAll(current)) current else current.filterTo(HashSet()) { it in present } }
    }

    val uiState: StateFlow<ChannelCleanupUiState> = combine(
        scan, groups, selected, showConfirm, errorRes,
    ) { scanState, suggestions, selectedIds, confirm, error ->
        val grouped = groupChannelCleanupSuggestions(suggestions)
        val present = grouped.flatMapTo(HashSet()) { it.channelIds }
        ChannelCleanupUiState(
            scan = mapChannelCleanupScan(scanState),
            groups = grouped,
            // Rows that stopped being suggestions (hidden elsewhere, results cleared) leave the selection.
            selectedIds = selectedIds.filterTo(HashSet()) { it in present },
            showConfirm = confirm,
            errorRes = error,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ChannelCleanupUiState())

    fun bind(id: Long) {
        if (providerId.value == id) return
        providerId.value = id
        selected.value = emptySet()
        showConfirm.value = false
        errorRes.value = null
    }

    fun startScan() {
        val id = providerId.value ?: return
        errorRes.value = null
        viewModelScope.launch {
            errorRes.value = channelCleanupStartErrorRes(repository.startScan(id))
        }
    }

    fun stopScan() {
        val id = providerId.value ?: return
        viewModelScope.launch { repository.cancelScan(id) }
    }

    fun toggleChannel(channelId: Long) {
        selected.update { if (channelId in it) it - channelId else it + channelId }
    }

    /** Selects every suggestion, or clears the selection when everything is already selected. */
    fun toggleAll() {
        val state = uiState.value
        selected.value = if (state.allSelected) emptySet() else state.allIds
    }

    /** Selects the whole category, or deselects it when all of it is already selected. */
    fun toggleCategory(group: ChannelCleanupGroup) {
        val ids = group.channelIds
        selected.update { current -> if (current.containsAll(ids)) current - ids else current + ids }
    }

    fun requestHide() {
        if (uiState.value.selectedCount > 0) showConfirm.value = true
    }

    fun dismissConfirm() {
        showConfirm.value = false
    }

    /** Hides exactly the selected channels, then clears the selection. */
    fun confirmHide() {
        val id = providerId.value ?: return
        val ids = uiState.value.selectedIds
        showConfirm.value = false
        if (ids.isEmpty()) return
        selected.value = emptySet()
        viewModelScope.launch { repository.hideChannels(id, ids) }
    }
}
