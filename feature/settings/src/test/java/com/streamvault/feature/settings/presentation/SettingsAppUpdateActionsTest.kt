package com.streamvault.feature.settings.presentation

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.settings.SettingsPreferences
import com.streamvault.feature.settings.R
import com.streamvault.feature.settings.api.SettingsAppUpdatePort
import com.streamvault.feature.settings.api.SettingsReleaseInfo
import com.streamvault.feature.settings.api.SettingsUpdateDownloadState
import com.streamvault.domain.model.Result
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsAppUpdateActionsTest {
    @Test
    fun `failed check records failure without replacing cached release`() = runTest(StandardTestDispatcher()) {
        val application: Context = fakeTextContext()
        val preferences = mock<SettingsPreferences>()
        val updatePort = mock<SettingsAppUpdatePort>()
        val uiState = MutableStateFlow(SettingsUiState())
        whenever(updatePort.fetchLatestRelease()).thenReturn(Result.error("HTTP 500"))

        val actions = SettingsAppUpdateActions(
            appContext = application,
            preferencesRepository = preferences,
            appUpdatePort = updatePort,
            uiState = uiState
        )

        actions.checkForAppUpdates(
            scope = this,
            manual = false,
            isRemoteVersionNewer = { _, _, _ -> false }
        )
        advanceUntilIdle()

        assertThat(uiState.value.appUpdate.errorMessage).isEqualTo(resText(R.string.settings_update_check_failed))
        verify(preferences).setLastAppUpdateAttemptTimestamp(any())
        verify(preferences).setLastAppUpdateFailureTimestamp(any())
        verify(preferences).setLastAppUpdateOutcome("FAILURE: HTTP 500")
        verify(preferences, never()).setCachedAppUpdateRelease(
            versionName = anyOrNull(),
            versionCode = anyOrNull(),
            releaseUrl = anyOrNull(),
            downloadUrl = anyOrNull(),
            downloadSha256 = anyOrNull(),
            releaseNotes = anyOrNull(),
            publishedAt = anyOrNull()
        )
    }

    @Test
    fun `manual failed check shows the localized sentence and never the raw error`() = runTest(StandardTestDispatcher()) {
        val updatePort = mock<SettingsAppUpdatePort>()
        val uiState = MutableStateFlow(SettingsUiState())
        whenever(updatePort.fetchLatestRelease()).thenReturn(Result.error("Update check failed: HTTP 500"))
        val actions = SettingsAppUpdateActions(fakeTextContext(), mock<SettingsPreferences>(), updatePort, uiState)

        actions.checkForAppUpdates(scope = this, manual = true, isRemoteVersionNewer = { _, _, _ -> false })
        advanceUntilIdle()

        assertThat(uiState.value.userMessage).isEqualTo(resText(R.string.settings_update_check_failed))
        assertThat(uiState.value.userMessage).doesNotContain("HTTP 500")
    }

    @Test
    fun `failed download shows the localized sentence and never the raw error`() = runTest(StandardTestDispatcher()) {
        val updatePort = mock<SettingsAppUpdatePort>()
        val uiState = MutableStateFlow(
            SettingsUiState(appUpdate = AppUpdateUiModel(latestVersionName = "9.9.9", releaseUrl = "https://example.test/r"))
        )
        whenever(updatePort.startDownload(any())).thenReturn(Result.error("Update download is unavailable for this release"))
        val actions = SettingsAppUpdateActions(fakeTextContext(), mock<SettingsPreferences>(), updatePort, uiState)

        actions.downloadLatestUpdate(this)
        advanceUntilIdle()

        assertThat(uiState.value.userMessage).isEqualTo(resText(R.string.settings_update_download_failed))
        assertThat(uiState.value.userMessage).doesNotContain("unavailable")
    }

    @Test
    fun `failed install shows the localized sentence and never the raw error`() = runTest(StandardTestDispatcher()) {
        val updatePort = mock<SettingsAppUpdatePort>()
        val uiState = MutableStateFlow(SettingsUiState())
        whenever(updatePort.installDownloadedUpdate(anyOrNull())).thenReturn(Result.error("The package installer could not be launched"))
        val actions = SettingsAppUpdateActions(fakeTextContext(), mock<SettingsPreferences>(), updatePort, uiState)

        actions.installDownloadedUpdate(this)
        advanceUntilIdle()

        assertThat(uiState.value.userMessage).isEqualTo(resText(R.string.settings_update_install_failed))
        assertThat(uiState.value.userMessage).doesNotContain("launched")
    }
}
