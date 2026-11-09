package com.streamvault.domain.repository

import com.streamvault.domain.model.ChannelHealthScanState
import com.streamvault.domain.model.ChannelHealthSuggestion
import com.streamvault.domain.model.Result
import kotlinx.coroutines.flow.Flow

/**
 * Finds live channels that do not work and lets the user hide them. Scans use the provider's own login
 * and at most the connections the account allows; nothing is ever deleted (hiding is reversible and
 * survives provider syncs).
 */
interface ChannelHealthRepository {
    fun observeScanState(providerId: Long): Flow<ChannelHealthScanState>

    /** Channels that failed on enough separate days, not already hidden, ordered by category then name. */
    fun observeSuggestions(providerId: Long): Flow<List<ChannelHealthSuggestion>>

    /**
     * Starts a background scan of the provider's live channels, limited to [categoryIds] when not null.
     * Returns an error for a provider type that cannot be scanned (only Xtream Codes can).
     */
    suspend fun startScan(providerId: Long, categoryIds: Set<Long>? = null): Result<Unit>

    suspend fun cancelScan(providerId: Long)

    /** Hides [channelIds] through the hidden channels list the Live TV screen already uses. */
    suspend fun hideChannels(providerId: Long, channelIds: Set<Long>)

    /** Forgets all probe results of the provider (not the hidden channels). */
    suspend fun clearResults(providerId: Long)
}
