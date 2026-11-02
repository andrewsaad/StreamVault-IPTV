package com.streamvault.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.domain.manager.FavoriteSyncJoinMode
import com.streamvault.domain.manager.FavoriteSyncManager
import com.streamvault.domain.manager.FavoriteSyncSetupResult
import com.streamvault.domain.manager.FavoriteSyncStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Settings > Backup > Favorites sync. Secrets go straight to the manager and are never kept here. */
@HiltViewModel
class FavoriteSyncSettingsViewModel @Inject constructor(
    private val manager: FavoriteSyncManager
) : ViewModel() {
    val status: StateFlow<FavoriteSyncStatus> = manager.status

    private val mutableSetupResult = MutableStateFlow<FavoriteSyncSetupResult?>(null)
    val setupResult: StateFlow<FavoriteSyncSetupResult?> = mutableSetupResult.asStateFlow()

    private val mutableSaving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = mutableSaving.asStateFlow()

    fun enable(serverUrl: String, token: String, passphrase: String, joinMode: FavoriteSyncJoinMode) {
        viewModelScope.launch {
            mutableSaving.value = true
            try {
                mutableSetupResult.value = manager.enable(serverUrl, token, passphrase, joinMode)
            } finally {
                mutableSaving.value = false
            }
        }
    }

    fun clearSetupResult() {
        mutableSetupResult.value = null
    }

    fun disable() {
        viewModelScope.launch { manager.disable() }
    }

    fun syncNow() = manager.syncNow()
}
