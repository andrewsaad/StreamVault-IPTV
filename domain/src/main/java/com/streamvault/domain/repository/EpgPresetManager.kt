package com.streamvault.domain.repository

import com.streamvault.domain.model.Result
import kotlinx.coroutines.flow.Flow

/** What the UI shows for one preset on this device. */
data class EpgPresetStatus(
    val presetId: String,
    val enabled: Boolean,
    val lastSuccessAt: Long?,
    val lastError: String?,
    val refreshing: Boolean,
)

/** Device-local control of the built-in guide presets. Nothing here is shared between devices. */
interface EpgPresetManager {
    /** One entry per preset in the catalog, in catalog order. */
    fun observe(): Flow<List<EpgPresetStatus>>

    /** Turning a preset on imports it once; turning it off removes sources no other preset still uses. */
    suspend fun setEnabled(presetId: String, enabled: Boolean): Result<Unit>

    /** Refused inside the minimum gap between attempts (at most two attempts in 24 hours). */
    suspend fun refreshNow(presetId: String): Result<Unit>

    /** Repairs drift: recreates deleted sources and assigns enabled presets to providers added later. */
    suspend fun reconcile()
}
