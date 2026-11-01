package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.AudioOutputPreference
import com.streamvault.domain.settings.PlayerPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class PlayerSurroundUpmixToggleTest {

    @Test
    fun `switch on maps to surround upmix and off maps to auto`() {
        assertThat(surroundUpmixAudioOutputPreference(enabled = true))
            .isEqualTo(AudioOutputPreference.SURROUND_UPMIX)
        assertThat(surroundUpmixAudioOutputPreference(enabled = false))
            .isEqualTo(AudioOutputPreference.AUTO)
    }

    @Test
    fun `only surround upmix preference reads as enabled`() {
        AudioOutputPreference.entries.forEach { preference ->
            assertThat(isSurroundUpmixEnabled(preference))
                .isEqualTo(preference == AudioOutputPreference.SURROUND_UPMIX)
        }
    }

    @Test
    fun `enabled flow reflects persisted audio output preference`() = runTest {
        val preferences = preferencesWith(AudioOutputPreference.SURROUND_UPMIX)
        assertThat(PlayerPreferencesCoordinator(preferences).playerSurroundUpmixEnabled.first()).isTrue()

        val autoPreferences = preferencesWith(AudioOutputPreference.AUTO)
        assertThat(PlayerPreferencesCoordinator(autoPreferences).playerSurroundUpmixEnabled.first()).isFalse()
    }

    @Test
    fun `toggle from surround upmix persists auto`() = runTest {
        val preferences = preferencesWith(AudioOutputPreference.SURROUND_UPMIX)

        PlayerPreferencesCoordinator(preferences).toggleSurroundUpmix()

        verify(preferences).setPlayerAudioOutputPreference(AudioOutputPreference.AUTO)
    }

    @Test
    fun `toggle from auto persists surround upmix`() = runTest {
        val preferences = preferencesWith(AudioOutputPreference.AUTO)

        PlayerPreferencesCoordinator(preferences).toggleSurroundUpmix()

        verify(preferences).setPlayerAudioOutputPreference(AudioOutputPreference.SURROUND_UPMIX)
    }

    @Test
    fun `toggle from a passthrough mode chosen in settings turns surround upmix on`() = runTest {
        val preferences = preferencesWith(AudioOutputPreference.PREFER_PASSTHROUGH)

        PlayerPreferencesCoordinator(preferences).toggleSurroundUpmix()

        verify(preferences).setPlayerAudioOutputPreference(AudioOutputPreference.SURROUND_UPMIX)
    }

    private fun preferencesWith(preference: AudioOutputPreference): PlayerPreferences =
        mock<PlayerPreferences>().apply {
            whenever(playerAudioOutputPreference).thenReturn(flowOf(preference))
        }
}
