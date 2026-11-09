package com.streamvault.feature.settings.presentation

import android.util.Log
import androidx.annotation.StringRes
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.XmltvTimezonePolicy
import com.streamvault.domain.repository.EpgPresetManager
import com.streamvault.domain.repository.EpgRefreshOutcome
import com.streamvault.domain.repository.EpgSourceRepository
import com.streamvault.feature.settings.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class SettingsEpgActions(
    private val epgSourceRepository: EpgSourceRepository,
    private val epgPresetManager: EpgPresetManager,
    private val uiState: MutableStateFlow<SettingsUiState>,
    /** Turns a preset refusal or guide refresh error into the text shown; the view model maps known keys to localized strings. */
    private val epgErrorText: (String) -> String = { it },
    /** Localized text for a string resource and its format arguments; the view model reads it from the app resources. */
    private val epgNotice: (Int, List<Any>) -> String = { resId, _ -> resId.toString() },
) {
    // Tracks the active assignment-collector job per provider so recompositions
    // cancel the old coroutine rather than leaking it in viewModelScope.
    private val assignmentJobs = ConcurrentHashMap<Long, Job>()

    private var presetObserverJob: Job? = null

    // ── Guide presets ────────────────────────────────────────────────

    /** Publishes preset status into [uiState]; reconciles preset sources once when started. */
    fun observeEpgPresets(scope: CoroutineScope): Job {
        presetObserverJob?.cancel()
        return scope.launch {
            launch {
                try {
                    epgPresetManager.reconcile()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    showUnexpectedPresetError(error)
                }
            }
            try {
                epgPresetManager.observe().map(::mapEpgPresetUiModels).collect { presets ->
                    uiState.update { it.copy(epgPresets = presets) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showUnexpectedPresetError(error)
            }
        }.also { presetObserverJob = it }
    }

    fun toggleEpgPreset(scope: CoroutineScope, presetId: String, enabled: Boolean) {
        scope.launch {
            try {
                val result = epgPresetManager.setEnabled(presetId, enabled)
                if (result is Result.Error) {
                    uiState.update { it.copy(userMessage = epgErrorText(result.message)) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showUnexpectedPresetError(error)
            }
        }
    }

    fun refreshEpgPresetNow(scope: CoroutineScope, presetId: String) {
        scope.launch {
            try {
                // Progress and the outcome show in the preset's own status line.
                val result = epgPresetManager.refreshNow(presetId)
                if (result is Result.Error) {
                    uiState.update { it.copy(userMessage = epgErrorText(result.message)) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showUnexpectedPresetError(error)
            }
        }
    }

    /**
     * Changes the address of a preset file on the same guide source row. The manager lifts the
     * gap and imports only that file in the background, so the editor can close at once. FR-006, FR-023.
     */
    fun updateEpgPresetAddress(
        scope: CoroutineScope,
        presetId: String,
        sourceKey: String,
        url: String,
        onSuccess: () -> Unit = {},
        onError: () -> Unit = {}
    ) {
        if (url.isBlank()) {
            onError()
            return
        }
        scope.launch {
            try {
                val result = epgPresetManager.updateAddress(presetId, sourceKey, url)
                if (result is Result.Error) {
                    uiState.update { it.copy(userMessage = epgErrorText(result.message)) }
                    onError()
                } else {
                    // The manager imports the edited file itself when its address changed; progress
                    // and the outcome show in the preset's own status line.
                    onSuccess()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showUnexpectedPresetError(error)
                onError()
            }
        }
    }

    private fun showUnexpectedPresetError(error: Exception) {
        // The exception text is raw English from lower layers; the user only ever sees the localized sentence.
        Log.w("SettingsEpgActions", "Unexpected guide preset failure: ${error.message}", error)
        uiState.update { it.copy(userMessage = epgNotice(R.string.settings_epg_preset_failed, emptyList())) }
    }

    /** A repository refusal carries raw English text; log it and show the localized sentence for the action. */
    private fun showSourceFailure(result: Result.Error, @StringRes fallbackRes: Int) {
        Log.w("SettingsEpgActions", "Guide source action failed: ${result.message}", result.exception)
        uiState.update { it.copy(userMessage = epgNotice(fallbackRes, emptyList())) }
    }

    fun loadEpgAssignments(scope: CoroutineScope, providerId: Long) {
        assignmentJobs[providerId]?.cancel()
        assignmentJobs[providerId] = scope.launch {
            try {
                epgSourceRepository.getAssignmentsForProvider(providerId).collect { assignments ->
                    val summary = epgSourceRepository.getResolutionSummary(providerId)
                    uiState.update {
                        it.copy(
                            epgSourceAssignments = it.epgSourceAssignments + (providerId to assignments),
                            epgResolutionSummaries = it.epgResolutionSummaries + (providerId to summary)
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showUnexpectedError(error, R.string.epg_error_load_assignments_failed)
            }
        }
    }

    fun addEpgSource(
        scope: CoroutineScope,
        name: String,
        url: String,
        timezoneId: String?,
        onSuccess: () -> Unit = {},
        onError: () -> Unit = {}
    ) {
        scope.launch {
            try {
                val normalizedTimezoneId = timezoneId?.trim()?.takeIf(String::isNotEmpty)
                val timezonePolicy = when {
                    normalizedTimezoneId == null -> XmltvTimezonePolicy.REQUIRE_OFFSET
                    normalizedTimezoneId.equals("UTC", ignoreCase = true) -> XmltvTimezonePolicy.UTC
                    else -> XmltvTimezonePolicy.EXPLICIT_ZONE
                }
                val result = epgSourceRepository.addSource(
                    name = name,
                    url = url,
                    timezonePolicy = timezonePolicy,
                    timezoneId = normalizedTimezoneId
                )
                if (result is Result.Error) {
                    showSourceFailure(result, R.string.epg_error_add_source_failed)
                    onError()
                } else {
                    onSuccess()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showUnexpectedError(error, R.string.epg_error_add_source_failed)
                onError()
            }
        }
    }

    fun updateEpgSourceTimezone(
        scope: CoroutineScope,
        source: com.streamvault.domain.model.EpgSource,
        timezoneId: String?,
        onSuccess: () -> Unit = {},
        onError: () -> Unit = {}
    ) {
        scope.launch {
            try {
                val normalizedTimezoneId = timezoneId?.trim()?.takeIf(String::isNotEmpty)
                val timezonePolicy = when {
                    normalizedTimezoneId == null -> XmltvTimezonePolicy.REQUIRE_OFFSET
                    normalizedTimezoneId.equals("UTC", ignoreCase = true) -> XmltvTimezonePolicy.UTC
                    else -> XmltvTimezonePolicy.EXPLICIT_ZONE
                }
                val updateResult = epgSourceRepository.updateSource(
                    source.copy(
                        timezonePolicy = timezonePolicy,
                        timezoneId = normalizedTimezoneId
                    )
                )
                if (updateResult is Result.Error) {
                    showSourceFailure(updateResult, R.string.epg_error_update_timezone_failed)
                    onError()
                    return@launch
                }

                val affectedProviders = epgSourceRepository.getProviderIdsForSource(source.id)
                val refreshResult = epgSourceRepository.refreshSource(source.id)
                if (refreshResult is Result.Error) {
                    uiState.update {
                        it.copy(
                            userMessage = epgNotice(
                                R.string.settings_epg_timezone_refresh_failed,
                                listOf(epgErrorText(refreshResult.message)),
                            )
                        )
                    }
                    onError()
                } else {
                    refreshLoadedResolutionSummaries(affectedProviders)
                    uiState.update { it.copy(userMessage = epgNotice(R.string.epg_timezone_updated, emptyList())) }
                    onSuccess()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showUnexpectedError(error, R.string.epg_error_update_timezone_failed)
                onError()
            }
        }
    }

    fun deleteEpgSource(scope: CoroutineScope, sourceId: Long) {
        scope.launch {
            try {
                uiState.update { it.copy(epgPendingDeleteSourceId = null) }
                // Query from the repository BEFORE deletion so we capture all affected
                // providers, not just those already loaded into the UI state.
                val affectedProviders = epgSourceRepository.getProviderIdsForSource(sourceId)
                epgSourceRepository.deleteSource(sourceId)
                refreshLoadedResolutionSummaries(affectedProviders)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showUnexpectedError(error, R.string.epg_error_delete_source_failed)
            }
        }
    }

    fun toggleEpgSourceEnabled(scope: CoroutineScope, sourceId: Long, enabled: Boolean) {
        scope.launch {
            try {
                // Query from the repository so all assigned providers are refreshed regardless
                // of which ones have been opened in the UI.
                val affectedProviders = epgSourceRepository.getProviderIdsForSource(sourceId)
                epgSourceRepository.setSourceEnabled(sourceId, enabled)
                refreshLoadedResolutionSummaries(affectedProviders)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showUnexpectedError(error, R.string.epg_error_update_source_failed)
            }
        }
    }

    fun refreshEpgSource(scope: CoroutineScope, sourceId: Long) {
        scope.launch {
            uiState.update { it.copy(refreshingEpgSourceIds = it.refreshingEpgSourceIds + sourceId) }
            try {
                // Query from the repository BEFORE refresh so all assigned providers are
                // updated, not just those already loaded into the UI state.
                val affectedProviders = epgSourceRepository.getProviderIdsForSource(sourceId)
                val result = epgSourceRepository.refreshSource(sourceId)
                if (result !is Result.Error) {
                    refreshLoadedResolutionSummaries(affectedProviders)
                }
                uiState.update {
                    it.copy(
                        refreshingEpgSourceIds = it.refreshingEpgSourceIds - sourceId,
                        userMessage = when {
                            result is Result.Error -> epgErrorText(result.message)
                            // Nothing was downloaded (rate limit or preset gap), so do not claim a refresh.
                            result.getOrNull() == EpgRefreshOutcome.SKIPPED ->
                                epgNotice(R.string.epg_source_refresh_skipped, emptyList())
                            else -> epgNotice(R.string.epg_source_refreshed, emptyList())
                        }
                    )
                }
            } catch (cancelled: CancellationException) {
                uiState.update { it.copy(refreshingEpgSourceIds = it.refreshingEpgSourceIds - sourceId) }
                throw cancelled
            } catch (error: Exception) {
                uiState.update { it.copy(refreshingEpgSourceIds = it.refreshingEpgSourceIds - sourceId) }
                showUnexpectedError(error, R.string.epg_error_refresh_source_failed)
            }
        }
    }

    fun assignEpgSourceToProvider(scope: CoroutineScope, providerId: Long, epgSourceId: Long) {
        scope.launch {
            try {
                val existingAssignments = uiState.value.epgSourceAssignments[providerId].orEmpty()
                val nextPriority = (existingAssignments.maxOfOrNull { it.priority } ?: 0) + 1
                val result = epgSourceRepository.assignSourceToProvider(providerId, epgSourceId, nextPriority)
                if (result is Result.Error) {
                    showSourceFailure(result, R.string.epg_error_assign_source_failed)
                    return@launch
                }
                // A freshly-added source has no programme data yet, so assigning it would
                // otherwise resolve to "Matched 0/N". Download it once now (rate-limited)
                // so the assignment produces real matches, then surface any refresh error.
                uiState.update { it.copy(refreshingEpgSourceIds = it.refreshingEpgSourceIds + epgSourceId) }
                val refreshResult = epgSourceRepository.refreshSource(epgSourceId)
                refreshProviderEpgSummary(providerId)
                uiState.update {
                    it.copy(
                        refreshingEpgSourceIds = it.refreshingEpgSourceIds - epgSourceId,
                        userMessage = if (refreshResult is Result.Error) epgErrorText(refreshResult.message) else null
                    )
                }
            } catch (cancelled: CancellationException) {
                uiState.update { it.copy(refreshingEpgSourceIds = it.refreshingEpgSourceIds - epgSourceId) }
                throw cancelled
            } catch (error: Exception) {
                uiState.update { it.copy(refreshingEpgSourceIds = it.refreshingEpgSourceIds - epgSourceId) }
                showUnexpectedError(error, R.string.epg_error_assign_source_failed)
            }
        }
    }

    fun unassignEpgSourceFromProvider(scope: CoroutineScope, providerId: Long, epgSourceId: Long) {
        scope.launch {
            try {
                epgSourceRepository.unassignSourceFromProvider(providerId, epgSourceId)
                refreshProviderEpgSummary(providerId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showUnexpectedError(error, R.string.epg_error_remove_source_failed)
            }
        }
    }

    fun moveEpgSourceAssignmentUp(scope: CoroutineScope, providerId: Long, epgSourceId: Long) {
        scope.launch {
            try {
                val assignments = uiState.value.epgSourceAssignments[providerId].orEmpty().sortedBy { it.priority }
                val index = assignments.indexOfFirst { it.epgSourceId == epgSourceId }
                if (index <= 0) return@launch
                val current = assignments[index]
                val previous = assignments[index - 1]
                epgSourceRepository.swapAssignmentPriorities(
                    providerId,
                    current.epgSourceId, previous.priority,
                    previous.epgSourceId, current.priority
                )
                refreshProviderEpgSummary(providerId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showUnexpectedError(error, R.string.epg_error_reorder_source_failed)
            }
        }
    }

    fun moveEpgSourceAssignmentDown(scope: CoroutineScope, providerId: Long, epgSourceId: Long) {
        scope.launch {
            try {
                val assignments = uiState.value.epgSourceAssignments[providerId].orEmpty().sortedBy { it.priority }
                val index = assignments.indexOfFirst { it.epgSourceId == epgSourceId }
                if (index == -1 || index >= assignments.lastIndex) return@launch
                val current = assignments[index]
                val next = assignments[index + 1]
                epgSourceRepository.swapAssignmentPriorities(
                    providerId,
                    current.epgSourceId, next.priority,
                    next.epgSourceId, current.priority
                )
                refreshProviderEpgSummary(providerId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showUnexpectedError(error, R.string.epg_error_reorder_source_failed)
            }
        }
    }

    private suspend fun refreshProviderEpgSummary(providerId: Long) {
        val summary = epgSourceRepository.getResolutionSummary(providerId)
        uiState.update {
            it.copy(epgResolutionSummaries = it.epgResolutionSummaries + (providerId to summary))
        }
    }

    /**
     * Cancels the assignment collector job for each provider in [removedIds] and purges their
     * entries from [SettingsUiState]. Called when the provider list shrinks so that the
     * [assignmentJobs] map doesn't grow unboundedly for providers that no longer exist.
     */
    fun cleanupEpgAssignmentsFor(removedIds: Set<Long>) {
        for (id in removedIds) {
            assignmentJobs.remove(id)?.cancel()
        }
        uiState.update { state ->
            state.copy(
                epgSourceAssignments = state.epgSourceAssignments - removedIds,
                epgResolutionSummaries = state.epgResolutionSummaries - removedIds
            )
        }
    }

    private fun loadedProvidersForSource(sourceId: Long): List<Long> =
        uiState.value.epgSourceAssignments
            .filterValues { assignments -> assignments.any { it.epgSourceId == sourceId } }
            .keys
            .toList()

    private suspend fun refreshLoadedResolutionSummaries(providerIds: Iterable<Long>) {
        providerIds.asSequence().distinct().forEach { providerId ->
            refreshProviderEpgSummary(providerId)
        }
    }

    private fun showUnexpectedError(error: Exception, @StringRes fallbackRes: Int) {
        // The exception text is raw English from lower layers; the user only ever sees the localized sentence.
        Log.w("SettingsEpgActions", "Unexpected guide failure: ${error.message}", error)
        uiState.update { it.copy(userMessage = epgNotice(fallbackRes, emptyList())) }
    }
}
