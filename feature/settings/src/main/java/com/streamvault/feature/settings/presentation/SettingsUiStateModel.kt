package com.streamvault.feature.settings.presentation

import com.streamvault.domain.model.LiveTvChannelMode
import com.streamvault.domain.model.LiveTvQuickFilterVisibilityMode
import com.streamvault.domain.model.VodViewMode
import com.streamvault.domain.manager.BackupImportPlan
import com.streamvault.domain.manager.BackupPreview
import com.streamvault.domain.manager.BackupProviderReference
import com.streamvault.domain.manager.BackupRestoreJobStatus
import com.streamvault.domain.manager.DriveAuthState
import com.streamvault.domain.manager.DriveBackupSnapshot
import com.streamvault.domain.manager.DriveSignInRequest
import com.streamvault.domain.manager.DriveSyncStatus
import com.streamvault.domain.manager.OpenSubtitlesAccount
import com.streamvault.domain.manager.ProviderCredentials
import com.streamvault.domain.model.ActiveLiveSource
import com.streamvault.domain.model.VodCategoryLoadMode
import com.streamvault.domain.model.AppHomeDashboardShelf
import com.streamvault.domain.model.AppLandingDestination
import com.streamvault.domain.model.AppTopLevelDestination
import com.streamvault.domain.model.AppTimeFormat
import com.streamvault.domain.model.AppTheme
import com.streamvault.domain.model.AudioOutputPreference
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.CategorySortMode
import com.streamvault.domain.model.ChannelNumberingMode
import com.streamvault.domain.model.CombinedM3uProfile
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.DecoderMode
import com.streamvault.domain.model.EpgPresetCatalog
import com.streamvault.domain.model.EpgResolutionSummary
import com.streamvault.domain.repository.EpgPresetErrors
import com.streamvault.domain.repository.EpgSourceErrors
import com.streamvault.feature.settings.R
import com.streamvault.domain.repository.EpgPresetStatus
import com.streamvault.domain.model.GroupedChannelLabelMode
import com.streamvault.domain.model.LiveChannelGroupingMode
import com.streamvault.domain.model.LiveVariantPreferenceMode
import com.streamvault.domain.model.LiveClockFont
import com.streamvault.domain.model.LiveClockPosition
import com.streamvault.domain.model.LiveClockSize
import com.streamvault.domain.model.PlaybackBufferMode
import com.streamvault.domain.model.VodDuplicateHandlingMode
import com.streamvault.domain.model.VodHttpProtocolMode
import com.streamvault.domain.model.ExternalPlaybackMode
import com.streamvault.domain.model.PlayerSurfaceMode
import com.streamvault.domain.model.PlayerBackButtonVisibility
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.ProviderAvailabilityStatus
import com.streamvault.domain.model.RecordingItem
import com.streamvault.domain.model.RecordingStorageState
import com.streamvault.domain.model.RemoteShortcutPreferences
import com.streamvault.domain.model.TimeshiftBackendPreference
import com.streamvault.domain.model.VodVariantPreferenceMode
import com.streamvault.domain.settings.PlaybackQualityPreferences

data class CrashReportUiModel(
    val timestamp: String = "",
    val exception: String = "",
    val fileName: String = "",
    val content: String = ""
) {
    val hasReport: Boolean
        get() = content.isNotBlank()
}

data class ProviderAvailabilityUiState(
    val status: ProviderAvailabilityStatus = ProviderAvailabilityStatus.UNKNOWN,
    val checkedAt: Long = 0L,
    val isChecking: Boolean = false
)

data class SettingsUiState(
    val providers: List<Provider> = emptyList(),
    val providerAvailabilityById: Map<Long, ProviderAvailabilityUiState> = emptyMap(),
    val combinedProfiles: List<CombinedM3uProfile> = emptyList(),
    val availableM3uProviders: List<Provider> = emptyList(),
    val activeProviderId: Long? = null,
    val activeLiveSource: ActiveLiveSource? = null,
    val isSyncing: Boolean = false,
    val syncProgress: String? = null,
    val syncingProviderName: String? = null,
    val syncStartedAt: Long = 0L,
    val syncSectionLabel: String? = null,
    val syncCanCancel: Boolean = false,
    val userMessage: String? = null,
    val syncWarningsByProvider: Map<Long, List<String>> = emptyMap(),
    val xtreamLiveOnboardingPhaseByProvider: Map<Long, String> = emptyMap(),
    val xtreamLiveOnboardingByProvider: Map<Long, XtreamLiveOnboardingUiModel> = emptyMap(),
    val xtreamIndexSectionStatusByProvider: Map<Long, Map<String, ProviderCatalogCountStatus>> = emptyMap(),
    val diagnosticsByProvider: Map<Long, ProviderDiagnosticsUiModel> = emptyMap(),
    val databaseMaintenance: DatabaseMaintenanceUiModel? = null,
    val parentalControlLevel: Int = 0,
    val hasParentalPin: Boolean = false,
    val appLanguage: String = "system",
    val appLandingDestination: AppLandingDestination = AppLandingDestination.HOME,
    val appTopLevelDestinations: List<AppTopLevelDestination> = AppTopLevelDestination.defaultOrder,
    val appHomeDashboardShelves: List<AppHomeDashboardShelf> = AppHomeDashboardShelf.defaultOrder,
    val appTimeFormat: AppTimeFormat = AppTimeFormat.SYSTEM,
    val appTheme: AppTheme = AppTheme.DEFAULT,
    val preferredAudioLanguage: String = "auto",
    val playerMediaSessionEnabled: Boolean = true,
    val playerBackButtonVisibility: PlayerBackButtonVisibility = PlayerBackButtonVisibility.DEFAULT,
    val playerConfirmClosePlayback: Boolean = false,
    val playerFastRetryOnTransientFailures: Boolean = false,
    val playerAudioDecoderMode: DecoderMode = DecoderMode.AUTO,
    val playerVideoDecoderMode: DecoderMode = DecoderMode.AUTO,
    val playerPlaybackBufferMode: PlaybackBufferMode = PlaybackBufferMode.AUTO,
    val playerAudioOutputPreference: AudioOutputPreference = AudioOutputPreference.DEFAULT,
    val playerCompatibilityMemoryEnabled: Boolean = true,
    val playerSurfaceMode: PlayerSurfaceMode = PlayerSurfaceMode.AUTO,
    val playerVodHttpProtocolMode: VodHttpProtocolMode = VodHttpProtocolMode.COMPATIBILITY_HTTP1,
    val playerPlaybackSpeed: Float = 1f,
    val playerExternalPlaybackMode: ExternalPlaybackMode = ExternalPlaybackMode.INTERNAL_PLAYER,
    val playerAudioVideoSyncEnabled: Boolean = false,
    val playerAudioVideoOffsetMs: Int = 0,
    val centerTwoSlotMultiviewLayout: Boolean = false,
    val multiViewRespectProviderConnectionLimit: Boolean = true,
    val playerControlsTimeoutSeconds: Int = 5,
    val playerLiveOverlayTimeoutSeconds: Int = 4,
    val playerLiveClockEnabled: Boolean = false,
    val playerLiveClockPosition: LiveClockPosition = LiveClockPosition.TOP_END,
    val playerLiveClockSize: LiveClockSize = LiveClockSize.MEDIUM,
    val playerLiveClockFont: LiveClockFont = LiveClockFont.DIGITAL_MONO,
    val playerNoticeTimeoutSeconds: Int = 6,
    val playerDiagnosticsTimeoutSeconds: Int = 15,
    val subtitleTextScale: Float = 1f,
    val subtitleTextColor: Int = 0xFFFFFFFF.toInt(),
    val subtitleBackgroundColor: Int = 0x80000000.toInt(),
    val pinnedSubtitleLanguages: List<String> = emptyList(),
    val playerLiveTranslationEnabled: Boolean = false,
    val playerLiveTranslationEndpoint: String = "http://10.0.2.2:8765",
    val openSubtitlesAccount: OpenSubtitlesAccount? = null,
    val isOpenSubtitlesBusy: Boolean = false,
    val openSubtitlesError: String? = null,
    val wifiMaxVideoHeight: Int? = null,
    val ethernetMaxVideoHeight: Int? = null,
    val playbackQualityPreferences: PlaybackQualityPreferences = PlaybackQualityPreferences(),
    val playerTimeshiftEnabled: Boolean = false,
    val playerTimeshiftDepthMinutes: Int = 30,
    val playerTimeshiftBackend: TimeshiftBackendPreference = TimeshiftBackendPreference.AUTOMATIC,
    val defaultStopPlaybackTimerMinutes: Int = 0,
    val defaultIdleStandbyTimerMinutes: Int = 0,
    val lastSpeedTest: InternetSpeedTestUiModel? = null,
    val isRunningInternetSpeedTest: Boolean = false,
    val isDeletingProvider: Boolean = false,
    val deleteProviderProgressMessage: String? = null,
    val deleteProviderProgressFraction: Float? = null,
    val isImportingBackup: Boolean = false,
    val backupPreview: BackupPreview? = null,
    val pendingBackupUri: String? = null,
    val backupImportPlan: BackupImportPlan = BackupImportPlan(),
    val pendingRestoreJobId: String? = null,
    val pendingRestoreProviders: List<BackupProviderReference> = emptyList(),
    val selectedRestoreProviderIndices: Set<Int> = emptySet(),
    val backupRestoreJobs: List<BackupRestoreJobStatus> = emptyList(),
    // --- Drive sync (M2) ---
    val driveAuthState: DriveAuthState = DriveAuthState.SignedOut,
    val driveSyncStatus: DriveSyncStatus = DriveSyncStatus(),
    val driveLastPushAt: Long? = null,
    val driveLastPullAt: Long? = null,
    val drivePendingSignIn: DriveSignInRequest? = null,
    val driveIsBusy: Boolean = false,
    /** Drive snapshots waiting for the user to choose one before preview/import. */
    val driveBackupOptions: List<DriveBackupSnapshot> = emptyList(),
    /** Drive snapshots shown in the explicit manage/delete screen. */
    val driveBackupManagementOptions: List<DriveBackupSnapshot> = emptyList(),
    // M3 — credentials downloaded by pullBackup, waiting to be applied
    // to providers once the import confirm completes.
    val pendingDriveCredentials: List<ProviderCredentials>? = null,
    val recordingItems: List<RecordingItem> = emptyList(),
    val recordingStorageState: RecordingStorageState = RecordingStorageState(),
    val wifiOnlyRecording: Boolean = false,
    val recordingPaddingBeforeMinutes: Int = 0,
    val recordingPaddingAfterMinutes: Int = 0,
    val hideStremioAddons: Boolean = false,
    val isIncognitoMode: Boolean = false,
    val useXtreamTextClassification: Boolean = true,
    val xtreamBase64TextCompatibility: Boolean = false,
    val liveTvChannelMode: LiveTvChannelMode = LiveTvChannelMode.PRO,
    val liveTvAutoHideCategories: Boolean = false,
    val showLiveSourceSwitcher: Boolean = false,
    val showFavoritesCategory: Boolean = true,
    val showAllChannelsCategory: Boolean = true,
    val showRecentChannelsCategory: Boolean = true,
    val remoteShortcutPreferences: RemoteShortcutPreferences = RemoteShortcutPreferences(),
    val liveTvCategoryFilters: List<String> = emptyList(),
    val liveTvQuickFilterVisibilityMode: LiveTvQuickFilterVisibilityMode = LiveTvQuickFilterVisibilityMode.ALWAYS_VISIBLE,
    val hideDecorativeLiveRows: Boolean = true,
    val liveChannelNumberingMode: ChannelNumberingMode = ChannelNumberingMode.GROUP,
    val liveChannelGroupingMode: LiveChannelGroupingMode = LiveChannelGroupingMode.RAW_VARIANTS,
    val groupedChannelLabelMode: GroupedChannelLabelMode = GroupedChannelLabelMode.HYBRID,
    val liveVariantPreferenceMode: LiveVariantPreferenceMode = LiveVariantPreferenceMode.BALANCED,
    val vodViewMode: VodViewMode = VodViewMode.MODERN,
    val showTraktTrendingMovies: Boolean = true,
    val showTraktPopularMovies: Boolean = true,
    val showTraktTrendingSeries: Boolean = true,
    val showTraktPopularSeries: Boolean = true,
    val vodTypeBadgeAsIcon: Boolean = false,
    val vodCategoryLoadMode: VodCategoryLoadMode = VodCategoryLoadMode.PAGED,
    val vodInfiniteScroll: Boolean = true,
    val vodPortalSearch: Boolean = true,
    val vodDuplicateHandlingMode: VodDuplicateHandlingMode = VodDuplicateHandlingMode.SHOW_ALL,
    val vodVariantPreferenceMode: VodVariantPreferenceMode = VodVariantPreferenceMode.BALANCED,
    val guideDefaultCategoryId: Long = com.streamvault.domain.model.VirtualCategoryIds.FAVORITES,
    val guideDefaultCategoryOptions: List<Category> = emptyList(),
    val playerRefreshRateMatchingEnabled: Boolean = true,
    val preventStandbyDuringPlayback: Boolean = true,
    val zapAutoRevert: Boolean = true,
    val autoPlayNextEpisode: Boolean = true,
    val categorySortModes: Map<ContentType, CategorySortMode> = emptyMap(),
    val hiddenCategories: List<Category> = emptyList(),
    val epgSources: List<com.streamvault.domain.model.EpgSource> = emptyList(),
    val epgSourceAssignments: Map<Long, List<com.streamvault.domain.model.ProviderEpgSourceAssignment>> = emptyMap(),
    val epgResolutionSummaries: Map<Long, EpgResolutionSummary> = emptyMap(),
    val refreshingEpgSourceIds: Set<Long> = emptySet(),
    val epgPendingDeleteSourceId: Long? = null,
    val epgTimeShiftMinutesByProvider: Map<Long, Int> = emptyMap(),
    val epgPresets: List<EpgPresetUiModel> = mapEpgPresetUiModels(emptyList()),
    val autoCheckAppUpdates: Boolean = false,
    val autoDownloadAppUpdates: Boolean = false,
    val isCheckingForUpdates: Boolean = false,
    val appUpdate: AppUpdateUiModel = AppUpdateUiModel(),
    val crashReport: CrashReportUiModel = CrashReportUiModel(),
    val viewedCrashReport: CrashReportUiModel? = null
)

/** What the status line under a guide preset says. The UI turns each case into localized text. */
sealed interface EpgPresetStatusLine {
    data object Off : EpgPresetStatusLine
    /** On, but neither a success nor an error has been recorded yet. */
    data object Pending : EpgPresetStatusLine
    data object Refreshing : EpgPresetStatusLine
    data class Updated(val at: Long) : EpgPresetStatusLine
    data class Failed(val message: String) : EpgPresetStatusLine
}

/**
 * The localized message for an [EpgPresetErrors] or [EpgSourceErrors] key, or null for any other
 * error text (a download or address error), which is shown as it is.
 */
internal fun epgPresetErrorRes(message: String): Int? = when (message) {
    EpgPresetErrors.UNKNOWN_PRESET -> R.string.epg_preset_error_unknown
    EpgPresetErrors.NOT_ENABLED -> R.string.epg_preset_error_not_enabled
    EpgPresetErrors.REFRESH_TOO_SOON -> R.string.epg_preset_error_refresh_too_soon
    EpgPresetErrors.SOURCE_UNAVAILABLE -> R.string.epg_preset_error_source_unavailable
    EpgPresetErrors.INVALID_ADDRESS -> R.string.epg_preset_error_invalid_address
    EpgSourceErrors.EMPTY_PAYLOAD -> R.string.epg_source_error_empty_payload
    else -> null
}

/** The status code of a download error stored as "HTTP 404", or wrapped as "Failed to download EPG: HTTP 404". */
internal fun epgHttpStatus(message: String): Int? =
    EPG_HTTP_STATUS_ERROR.matchEntire(message.trim())?.groupValues?.get(1)?.toIntOrNull()

private val EPG_HTTP_STATUS_ERROR = Regex("""(?:Failed to download EPG: )?HTTP (\d{3})""")

/**
 * [message] with every known error key, and every HTTP status error, in it replaced by its localized
 * text. A preset refresh that failed on several files joins their errors with "; ", so each part is
 * looked up alone.
 */
internal fun localizeEpgError(message: String, text: (Int) -> String, httpStatusText: (Int) -> String): String =
    message.split(EPG_ERROR_SEPARATOR).joinToString(EPG_ERROR_SEPARATOR) { part ->
        epgPresetErrorRes(part)?.let(text) ?: epgHttpStatus(part)?.let(httpStatusText) ?: part
    }

private const val EPG_ERROR_SEPARATOR = "; "

/** One guide file of a preset, linked to the ordinary guide source row when one exists. */
data class EpgPresetSourceUiModel(
    val key: String,
    val url: String,
    val sourceId: Long?,
)

data class EpgPresetUiModel(
    val presetId: String,
    val enabled: Boolean,
    val refreshing: Boolean,
    val lastSuccessAt: Long?,
    val lastError: String?,
    val sources: List<EpgPresetSourceUiModel>,
) {
    val statusLine: EpgPresetStatusLine
        get() = when {
            !enabled -> EpgPresetStatusLine.Off
            refreshing -> EpgPresetStatusLine.Refreshing
            !lastError.isNullOrBlank() -> EpgPresetStatusLine.Failed(lastError)
            lastSuccessAt != null -> EpgPresetStatusLine.Updated(lastSuccessAt)
            else -> EpgPresetStatusLine.Pending
        }
}

/**
 * Builds one entry per catalog preset, in catalog order. A preset the manager has not reported
 * is shown as off (FR-002). Each preset file takes its row and address from the manager, which
 * keeps them across restarts and address edits; a file it did not report shows the built-in one.
 */
internal fun mapEpgPresetUiModels(statuses: List<EpgPresetStatus>): List<EpgPresetUiModel> {
    val statusById = statuses.associateBy { it.presetId }
    return EpgPresetCatalog.all.map { preset ->
        val status = statusById[preset.id]
        EpgPresetUiModel(
            presetId = preset.id,
            enabled = status?.enabled == true,
            refreshing = status?.refreshing == true,
            lastSuccessAt = status?.lastSuccessAt,
            lastError = status?.lastError,
            sources = preset.sources.map { presetSource ->
                val reported = status?.sources?.firstOrNull { it.key == presetSource.key }
                EpgPresetSourceUiModel(
                    key = presetSource.key,
                    url = reported?.url ?: presetSource.url,
                    sourceId = reported?.sourceId,
                )
            },
        )
    }
}
