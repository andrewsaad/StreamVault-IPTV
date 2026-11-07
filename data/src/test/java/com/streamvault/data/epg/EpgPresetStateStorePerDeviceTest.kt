package com.streamvault.data.epg

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** FR-026 / user story 4: preset state belongs to one device and is never shared. */
class EpgPresetStateStorePerDeviceTest {

    private fun device(name: String) =
        testPresetStore { 1L }

    @Test
    fun `enablingOnOneDevice_leavesTheOtherDeviceOff`() = runTest {
        val googleTv = device("google_tv")
        val fireTv = device("fire_tv")

        googleTv.update { it.copy(enabledPresetIds = setOf("bein")) }

        assertThat(googleTv.current().enabledPresetIds).containsExactly("bein")
        assertThat(fireTv.current().enabledPresetIds).isEmpty()
    }

    @Test
    fun `eachDeviceKeepsItsOwnSourceRowsAndAttempts`() = runTest {
        val googleTv = device("google_tv")
        val fireTv = device("fire_tv")
        googleTv.update {
            it.copy(sources = mapOf("AE1" to EpgPresetSourceRecord(7L, owned = true, assignedProviderIds = setOf(1L))))
        }
        fireTv.update {
            it.copy(sources = mapOf("AE1" to EpgPresetSourceRecord(3L, owned = true, assignedProviderIds = setOf(2L))))
        }

        assertThat(googleTv.tryBeginRefreshAttempt(7L)).isTrue()

        assertThat(googleTv.current().sources.getValue("AE1").sourceId).isEqualTo(7L)
        assertThat(fireTv.current().sources.getValue("AE1").sourceId).isEqualTo(3L)
        assertThat(fireTv.current().lastAttemptAtBySourceId).isEmpty()
        assertThat(fireTv.tryBeginRefreshAttempt(3L)).isTrue()
    }

    @Test
    fun `storeUsesItsOwnFile_notTheSharedPreferencesFile`() {
        assertThat(EpgPresetStateStore.FILE_NAME).isEqualTo("epg_preset_state")
        // The main preferences file; preset state must never be written there.
        assertThat(EpgPresetStateStore.FILE_NAME).isNotEqualTo("user_preferences")
    }
}
