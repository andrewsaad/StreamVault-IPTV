package com.streamvault.domain.repository

import com.streamvault.domain.model.Result
import kotlinx.coroutines.flow.Flow

/**
 * One guide file of a preset on this device. [sourceId] is the guide source row it uses, null
 * while the preset is off; [url] is that row's address, else the edited or built-in one.
 */
data class EpgPresetSourceStatus(
    val key: String,
    val sourceId: Long?,
    val url: String,
)

/** What the UI shows for one preset on this device. [sources] follow the catalog order. */
data class EpgPresetStatus(
    val presetId: String,
    val enabled: Boolean,
    val lastSuccessAt: Long?,
    val lastError: String?,
    val refreshing: Boolean,
    val sources: List<EpgPresetSourceStatus> = emptyList(),
)

/** Stable [Result.Error] messages from [EpgPresetManager]; the UI maps them to localized text. */
object EpgPresetErrors {
    const val UNKNOWN_PRESET = "epg_preset_unknown"
    const val NOT_ENABLED = "epg_preset_not_enabled"
    const val REFRESH_TOO_SOON = "epg_preset_refresh_too_soon"
    const val SOURCE_UNAVAILABLE = "epg_preset_source_unavailable"
    const val INVALID_ADDRESS = "epg_preset_invalid_address"
}

/** Device-local control of the built-in guide presets. Nothing here is shared between devices. */
interface EpgPresetManager {
    /** One entry per preset in the catalog, in catalog order. */
    fun observe(): Flow<List<EpgPresetStatus>>

    /** Turning a preset on imports it once; turning it off removes sources no other preset still uses. */
    suspend fun setEnabled(presetId: String, enabled: Boolean): Result<Unit>

    /** Refused inside the minimum gap between attempts (at most two attempts in 24 hours). */
    suspend fun refreshNow(presetId: String): Result<Unit>

    /**
     * Changes the address of one file of an enabled preset on the same source row, and lifts the
     * gap between attempts so the new address can be tried now. The edit survives turning the
     * preset off and on. FR-006, FR-023.
     */
    suspend fun updateAddress(presetId: String, sourceKey: String, url: String): Result<Unit>

    /** Repairs drift: recreates deleted sources and assigns enabled presets to providers added later. */
    suspend fun reconcile()
}
