package com.streamvault.data.manager

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.manager.BackupData
import com.streamvault.domain.manager.BackupImportPlan
import com.streamvault.domain.manager.ProviderBackupSnapshot
import org.junit.Test
import org.mockito.kotlin.mock

class ProvidersForcedByBackupReferencesTest {

    private val snapshots: List<ProviderBackupSnapshot> = listOf(mock())

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
}
