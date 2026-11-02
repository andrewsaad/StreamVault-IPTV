package com.streamvault.domain.repository

import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StreamInfo
import kotlinx.coroutines.flow.Flow

interface ChannelRepository {
    fun getChannels(providerId: Long): Flow<List<Channel>>

    /**
     * The subset of [getChannels] whose logical channel has a raw row with guide programmes
     * overlapping [startTime, endTime). Every returned channel is identical to its entry in
     * [getChannels] (including numbering); unrelated channels are never presented. The default
     * returns the full list, which is a valid superset.
     */
    fun getChannelsWithGuide(providerId: Long, startTime: Long, endTime: Long): Flow<List<Channel>> =
        getChannels(providerId)

    fun getChannelCount(providerId: Long): Flow<Int>
    fun getChannelsByCategory(providerId: Long, categoryId: Long): Flow<List<Channel>>
    fun getChannelsByCategoryPage(providerId: Long, categoryId: Long, limit: Int): Flow<List<Channel>>
    fun getChannelsByNumber(providerId: Long, categoryId: Long = ALL_CHANNELS_ID): Flow<List<Channel>>
    fun getChannelsWithoutErrors(providerId: Long, categoryId: Long = ALL_CHANNELS_ID): Flow<List<Channel>>
    fun getChannelsWithoutErrorsPage(providerId: Long, categoryId: Long = ALL_CHANNELS_ID, limit: Int): Flow<List<Channel>>
    suspend fun getChannelsByCategoryPageOffset(providerId: Long, categoryId: Long, limit: Int, offset: Int): List<Channel>
    suspend fun getChannelsWithoutErrorsPageOffset(providerId: Long, categoryId: Long, limit: Int, offset: Int): List<Channel>
    fun searchChannelsByCategory(providerId: Long, categoryId: Long, query: String): Flow<List<Channel>>
    fun searchChannelsByCategoryPaged(providerId: Long, categoryId: Long, query: String, limit: Int): Flow<List<Channel>>
    fun getCategories(providerId: Long): Flow<List<Category>>
    suspend fun getCategoriesSnapshot(providerId: Long): List<Category>
    fun searchChannels(providerId: Long, query: String): Flow<List<Channel>>
    suspend fun getChannel(channelId: Long): Channel?
    suspend fun getStreamInfo(channel: Channel, preferStableUrl: Boolean = false): Result<StreamInfo>
    suspend fun refreshChannels(providerId: Long): Result<Unit>
    fun getChannelsByIds(ids: List<Long>): Flow<List<Channel>>
    /**
     * Visible channels whose name mentions beIN or sport(s), beIN first, as raw (ungrouped) rows.
     * A one-shot read for lightweight surfaces such as the home "live & starting soon" row.
     */
    suspend fun getSportsChannelCandidates(providerId: Long, limit: Int): List<Channel> = emptyList()
    suspend fun incrementChannelErrorCount(channelId: Long): Result<Unit>
    suspend fun resetChannelErrorCount(channelId: Long): Result<Unit>

    companion object {
        const val ALL_CHANNELS_ID = -1_000_000L
    }
}
