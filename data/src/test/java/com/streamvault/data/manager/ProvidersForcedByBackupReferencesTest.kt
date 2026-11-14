package com.streamvault.data.manager

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.manager.BackupData
import com.streamvault.domain.manager.BackupImportPlan
import com.streamvault.domain.manager.ProviderBackupSnapshot
import org.junit.Test
import org.mockito.kotlin.mock

class ProvidersForcedByBackupReferencesTest {

    private val snapshots: List<ProviderBackupSnapshot> = listOf(mock())

    private val noReferenceSections = BackupImportPlan(
        importPreferences = false,
        importProviders = false,
        importSavedLibrary = false,
        importPlaybackHistory = false,
        importMultiViewPresets = false,
        importRecordingSchedules = false
    )

    @Test
    fun `v14 backup with snapshots forces providers when the plan excludes them`() {
        val data = BackupData(version = 14, providerSnapshots = snapshots)

        assertThat(providersForcedByBackupReferences(data, BackupImportPlan(importProviders = false))).isTrue()
    }

    @Test
    fun `nothing is forced when providers are already selected`() {
        val data = BackupData(version = 14, providerSnapshots = snapshots)

        assertThat(providersForcedByBackupReferences(data, BackupImportPlan(importProviders = true))).isFalse()
    }

    @Test
    fun `nothing is forced without snapshots or before v14`() {
        val plan = BackupImportPlan(importProviders = false)

        assertThat(providersForcedByBackupReferences(BackupData(version = 14), plan)).isFalse()
        assertThat(providersForcedByBackupReferences(BackupData(version = 13, providerSnapshots = snapshots), plan)).isFalse()
    }

    @Test
    fun `the untick is honoured when no selected section references providers`() {
        val data = BackupData(version = 14, providerSnapshots = snapshots)

        assertThat(providersForcedByBackupReferences(data, noReferenceSections)).isFalse()
        assertThat(providersForcedByBackupReferences(data, noReferenceSections.copy(importStremioAddons = true))).isFalse()
    }

    @Test
    fun `each section that references providers forces them on its own`() {
        val data = BackupData(version = 14, providerSnapshots = snapshots)

        listOf(
            noReferenceSections.copy(importSavedLibrary = true),
            noReferenceSections.copy(importPlaybackHistory = true),
            noReferenceSections.copy(importPreferences = true),
            noReferenceSections.copy(importMultiViewPresets = true),
            noReferenceSections.copy(importRecordingSchedules = true)
        ).forEach { plan ->
            assertThat(providersForcedByBackupReferences(data, plan)).isTrue()
        }
    }
}
