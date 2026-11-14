package com.streamvault.data.manager

import com.streamvault.domain.manager.BackupData
import com.streamvault.domain.manager.BackupImportPlan

/**
 * A v14+ backup with provider snapshots restores its providers even when Providers is unticked, but only
 * when a selected section holds provider references that resolve against them (saved library, history,
 * preferences, multi-view presets, recording schedules). The result tells the user. When none of those
 * sections is selected the untick is honoured.
 */
internal fun providersForcedByBackupReferences(backupData: BackupData, plan: BackupImportPlan): Boolean =
    !plan.importProviders &&
        backupData.version >= 14 &&
        backupData.providerSnapshots != null &&
        plan.importsProviderReferences()

private fun BackupImportPlan.importsProviderReferences(): Boolean =
    importSavedLibrary || importPlaybackHistory || importPreferences || importMultiViewPresets || importRecordingSchedules
