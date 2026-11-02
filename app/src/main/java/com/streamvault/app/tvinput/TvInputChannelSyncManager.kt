package com.streamvault.app.tvinput

import android.content.ComponentName
import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.provider.BaseColumns
import android.util.Log
import com.streamvault.app.MainActivity
import com.streamvault.app.device.isTelevisionDevice
import com.streamvault.core.navigation.PlayerNavigationRequest
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Program
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.EpgRepository
import com.streamvault.domain.repository.ProviderRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

@Singleton
class TvInputChannelSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val providerRepository: ProviderRepository,
    private val channelRepository: ChannelRepository,
    private val epgRepository: EpgRepository
) {

    suspend fun refreshTvInputCatalog() {
        refreshTvInputCatalogResult().onFailure { throwable ->
            Log.w(TAG, "TV input catalog sync failed", throwable)
        }
    }

    suspend fun refreshTvInputCatalogResult(): Result<Unit> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            runCatching {
                if (!context.isTelevisionDevice()) {
                    return@runCatching
                }
                val provider = providerRepository.getActiveProvider().first()
                if (provider == null) {
                    deleteManagedChannels()
                    return@runCatching
                }

                val channels = channelRepository.getChannels(provider.id).first()
                val providerHash = tvProviderHash(provider)
                // Row ids come from the channel publish itself; re-reading the whole TV channel
                // table is only needed when the provider did not report an inserted row's id.
                val publishedChannelIds = publishChannels(provider, providerHash, channels)
                    ?: loadExistingChannels()

                val programsByEpgId = loadPrograms(provider.id, channels)
                publishPrograms(provider, providerHash, channels, publishedChannelIds, programsByEpgId)
            }
        }
    }

    /**
     * Returns the TV row id of every published channel keyed by its stable key, or null when an
     * inserted row's id could not be read from the batch results.
     */
    private fun publishChannels(
        provider: Provider,
        providerHash: String,
        channels: List<Channel>
    ): Map<String, Long>? {
        val existing = loadExistingChannelRows()
        val targets = channels.associateBy { stableTvChannelKey(providerHash, it) }
        val operations = ArrayList<ContentProviderOperation>()
        val insertedKeys = ArrayList<String>()
        val publishedIds = HashMap<String, Long>(targets.size)
        existing.filterKeys { it !in targets }.values.forEach { row ->
            operations += ContentProviderOperation.newDelete(
                ContentUris.withAppendedId(TvContract.Channels.CONTENT_URI, row.id)
            ).build()
        }
        val insertOperations = ArrayList<ContentProviderOperation>()
        targets.forEach { (key, channel) ->
            val values = buildChannelValues(provider, key, channel)
            val row = existing[key]
            when {
                row == null -> {
                    insertOperations += ContentProviderOperation.newInsert(TvContract.Channels.CONTENT_URI)
                        .withValues(values)
                        .build()
                    insertedKeys += key
                }
                row.providerData != values.getAsString(CHANNEL_COLUMN_INTERNAL_PROVIDER_DATA) -> {
                    operations += ContentProviderOperation.newUpdate(
                        ContentUris.withAppendedId(TvContract.Channels.CONTENT_URI, row.id)
                    ).withValues(values).build()
                    publishedIds[key] = row.id
                }
                else -> publishedIds[key] = row.id
            }
        }
        applyBatches(operations, "channels")
        val insertResults = applyBatches(insertOperations, "channel inserts")
        if (insertResults.size != insertedKeys.size) return null
        insertResults.forEachIndexed { index, result ->
            val rowId = result.uri?.let { uri -> runCatching { ContentUris.parseId(uri) }.getOrNull() }
                ?.takeIf { it >= 0L }
                ?: return null
            publishedIds[insertedKeys[index]] = rowId
        }
        return publishedIds
    }

    private fun publishPrograms(
        provider: Provider,
        providerHash: String,
        channels: List<Channel>,
        channelIds: Map<String, Long>,
        programsByEpgId: Map<String, List<Program>>
    ) {
        val desired = linkedMapOf<String, ContentValues>()
        val replaceableChannelIds = linkedSetOf<Long>()
        channels.forEach { channel ->
            val programs = programsByEpgId[channel.epgChannelId].orEmpty()
            if (!shouldReplaceTvPrograms(channel, programs)) return@forEach
            val channelKey = stableTvChannelKey(providerHash, channel)
            val tvChannelId = channelIds[channelKey] ?: return@forEach
            replaceableChannelIds += tvChannelId
            programs.sortedBy { it.startTime }.take(MAX_PROGRAMS_PER_CHANNEL).forEach { program ->
                val values = buildProgramValues(tvChannelId, provider.id, channel, program)
                val fingerprint = stableTvProgramKey(channelKey, program)
                values.put(PROGRAM_COLUMN_INTERNAL_PROVIDER_DATA, fingerprint)
                desired[fingerprint] = values
            }
        }
        val existing = loadExistingPrograms(replaceableChannelIds)
        val operations = ArrayList<ContentProviderOperation>()
        existing.filterKeys { it !in desired }.values.forEach { programId ->
            operations += ContentProviderOperation.newDelete(
                ContentUris.withAppendedId(TvContract.Programs.CONTENT_URI, programId)
            ).build()
        }
        desired.filterKeys { it !in existing }.values.forEach { values ->
            operations += ContentProviderOperation.newInsert(TvContract.Programs.CONTENT_URI)
                .withValues(values)
                .build()
        }
        applyBatches(operations, "programs")
    }

    private fun loadExistingChannelRows(): Map<String, ExistingTvChannel> {
        val targetInputId = inputId()
        return context.contentResolver.query(
            TvContract.Channels.CONTENT_URI,
            arrayOf(
                BaseColumns._ID,
                CHANNEL_COLUMN_INPUT_ID,
                CHANNEL_COLUMN_INTERNAL_PROVIDER_ID,
                CHANNEL_COLUMN_INTERNAL_PROVIDER_DATA
            ),
            null,
            null,
            null
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(BaseColumns._ID)
            val inputIdIndex = cursor.getColumnIndexOrThrow(CHANNEL_COLUMN_INPUT_ID)
            val keyIndex = cursor.getColumnIndexOrThrow(CHANNEL_COLUMN_INTERNAL_PROVIDER_ID)
            val dataIndex = cursor.getColumnIndexOrThrow(CHANNEL_COLUMN_INTERNAL_PROVIDER_DATA)
            buildMap {
                while (cursor.moveToNext()) {
                    if (cursor.getString(inputIdIndex) == targetInputId) {
                        put(
                            cursor.getString(keyIndex),
                            ExistingTvChannel(cursor.getLong(idIndex), cursor.getString(dataIndex).orEmpty())
                        )
                    }
                }
            }
        }.orEmpty()
    }

    private fun loadExistingPrograms(channelIds: Set<Long>): Map<String, Long> {
        if (channelIds.isEmpty()) return emptyMap()
        val result = linkedMapOf<String, Long>()
        // TvProvider rejects SQL selection for ordinary TV inputs. It already scopes
        // this query to our package; filter the replaceable channels in one cursor
        // instead of sending an unsupported IN clause (or one Binder call/channel).
        context.contentResolver.query(
            TvContract.Programs.CONTENT_URI,
            arrayOf(BaseColumns._ID, PROGRAM_COLUMN_CHANNEL_ID, PROGRAM_COLUMN_INTERNAL_PROVIDER_DATA),
            null,
            null,
            null
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(BaseColumns._ID)
            val channelIdIndex = cursor.getColumnIndexOrThrow(PROGRAM_COLUMN_CHANNEL_ID)
            val dataIndex = cursor.getColumnIndexOrThrow(PROGRAM_COLUMN_INTERNAL_PROVIDER_DATA)
            while (cursor.moveToNext()) {
                if (cursor.getLong(channelIdIndex) !in channelIds) continue
                cursor.getString(dataIndex)?.let { result[it] = cursor.getLong(idIndex) }
            }
        }
        return result
    }

    private fun applyBatches(
        operations: List<ContentProviderOperation>,
        stage: String
    ): List<ContentProviderResult> {
        if (operations.isEmpty()) return emptyList()
        val startedAt = System.currentTimeMillis()
        val results = ArrayList<ContentProviderResult>(operations.size)
        operations.chunked(CONTENT_PROVIDER_BATCH_SIZE).forEach { chunk ->
            results += context.contentResolver.applyBatch(TvContract.AUTHORITY, ArrayList(chunk))
        }
        Log.i(TAG, "TV input $stage operations=${operations.size} took=${System.currentTimeMillis() - startedAt}ms")
        return results
    }

    private data class ExistingTvChannel(val id: Long, val providerData: String)

    private suspend fun loadPrograms(providerId: Long, channels: List<Channel>): Map<String, List<Program>> {
        val epgIds = channels.mapNotNull { it.epgChannelId?.takeIf(String::isNotBlank) }
        if (epgIds.isEmpty()) return emptyMap()

        val now = System.currentTimeMillis()
        val start = now - PROGRAM_LOOKBACK_MS
        val end = now + PROGRAM_LOOKAHEAD_MS
        val merged = mutableMapOf<String, List<Program>>()

        epgIds.distinct().chunked(EPG_QUERY_CHUNK_SIZE).forEach { chunk ->
            merged += epgRepository.getProgramsForChannelsSnapshot(providerId, chunk, start, end)
        }
        return merged
    }

    private fun loadExistingChannels(): Map<String, Long> {
        val targetInputId = inputId()
        return context.contentResolver.query(
            TvContract.Channels.CONTENT_URI,
            arrayOf(BaseColumns._ID, CHANNEL_COLUMN_INPUT_ID, CHANNEL_COLUMN_INTERNAL_PROVIDER_ID),
            null,
            null,
            null
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(BaseColumns._ID)
            val inputIdIndex = cursor.getColumnIndexOrThrow(CHANNEL_COLUMN_INPUT_ID)
            val keyIndex = cursor.getColumnIndexOrThrow(CHANNEL_COLUMN_INTERNAL_PROVIDER_ID)
            buildMap {
                while (cursor.moveToNext()) {
                    if (cursor.getString(inputIdIndex) == targetInputId) {
                        put(cursor.getString(keyIndex), cursor.getLong(idIndex))
                    }
                }
            }
        }.orEmpty()
    }

    private fun buildChannelValues(provider: Provider, channelKey: String, channel: Channel): ContentValues = ContentValues().apply {
        put(CHANNEL_COLUMN_INPUT_ID, inputId())
        put(CHANNEL_COLUMN_TYPE, TvContract.Channels.TYPE_OTHER)
        put(CHANNEL_COLUMN_SERVICE_TYPE, TvContract.Channels.SERVICE_TYPE_AUDIO_VIDEO)
        put(CHANNEL_COLUMN_DISPLAY_NUMBER, channel.number.toString())
        put(CHANNEL_COLUMN_DISPLAY_NAME, channel.name)
        put(CHANNEL_COLUMN_DESCRIPTION, channel.categoryName ?: "IPTV")
        put(CHANNEL_COLUMN_INTERNAL_PROVIDER_ID, channelKey)
        put(CHANNEL_COLUMN_INTERNAL_PROVIDER_DATA, encodeChannelData(provider.id, channel))
        put(CHANNEL_COLUMN_APP_LINK_INTENT_URI, buildChannelIntent(channel).toUri(Intent.URI_INTENT_SCHEME))
    }

    private fun buildProgramValues(channelId: Long, providerId: Long, channel: Channel, program: Program): ContentValues = ContentValues().apply {
        put(PROGRAM_COLUMN_CHANNEL_ID, channelId)
        put(PROGRAM_COLUMN_TITLE, program.title)
        put(PROGRAM_COLUMN_DESCRIPTION, program.description)
        put(PROGRAM_COLUMN_START_TIME_UTC_MILLIS, program.startTime)
        put(PROGRAM_COLUMN_END_TIME_UTC_MILLIS, program.endTime)
        put(PROGRAM_COLUMN_INTERNAL_PROVIDER_DATA, "${providerId}:${channel.id}:${program.startTime}")
    }

    private fun buildChannelIntent(channel: Channel): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(
                MainActivity.EXTRA_PLAYER_REQUEST,
                PlayerNavigationRequest(
                    streamUrl = channel.streamUrl,
                    title = channel.name,
                    channelId = channel.epgChannelId,
                    internalId = channel.id,
                    categoryId = channel.categoryId,
                    providerId = channel.providerId,
                    contentType = "LIVE"
                )
            )
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    private fun deleteManagedChannels() {
        val operations = loadExistingChannels().values.map { channelId ->
            ContentProviderOperation.newDelete(
                ContentUris.withAppendedId(TvContract.Channels.CONTENT_URI, channelId)
            ).build()
        }
        applyBatches(operations, "channel deletions")
    }

    private fun inputId(): String = ComponentName(context, StreamVaultTvInputService::class.java).flattenToShortString()

    private fun encodeChannelData(providerId: Long, channel: Channel): String =
        listOf(
            providerId,
            channel.id,
            channel.epgChannelId.orEmpty(),
            sha256Prefix(
                listOf(
                    channel.streamId,
                    channel.number,
                    channel.name,
                    channel.categoryName.orEmpty(),
                    channel.streamUrl
                ).joinToString("|")
            )
        ).joinToString(ENTRY_SEPARATOR)

    private companion object {
        const val TAG = "TvInputChannelSync"
        const val CHANNEL_COLUMN_INPUT_ID = "input_id"
        const val CHANNEL_COLUMN_TYPE = "type"
        const val CHANNEL_COLUMN_SERVICE_TYPE = "service_type"
        const val CHANNEL_COLUMN_DISPLAY_NUMBER = "display_number"
        const val CHANNEL_COLUMN_DISPLAY_NAME = "display_name"
        const val CHANNEL_COLUMN_DESCRIPTION = "description"
        const val CHANNEL_COLUMN_BROWSABLE = "browsable"
        const val CHANNEL_COLUMN_INTERNAL_PROVIDER_ID = "internal_provider_id"
        const val CHANNEL_COLUMN_INTERNAL_PROVIDER_DATA = "internal_provider_data"
        const val CHANNEL_COLUMN_APP_LINK_INTENT_URI = "app_link_intent_uri"
        const val PROGRAM_COLUMN_CHANNEL_ID = "channel_id"
        const val PROGRAM_COLUMN_TITLE = "title"
        const val PROGRAM_COLUMN_DESCRIPTION = "description"
        const val PROGRAM_COLUMN_START_TIME_UTC_MILLIS = "start_time_utc_millis"
        const val PROGRAM_COLUMN_END_TIME_UTC_MILLIS = "end_time_utc_millis"
        const val PROGRAM_COLUMN_INTERNAL_PROVIDER_DATA = "internal_provider_data"
        const val EPG_QUERY_CHUNK_SIZE = 200
        const val CONTENT_PROVIDER_BATCH_SIZE = 200
        const val MAX_PROGRAMS_PER_CHANNEL = 24
        const val PROGRAM_LOOKBACK_MS = 3 * 60 * 60 * 1000L
        const val PROGRAM_LOOKAHEAD_MS = 18 * 60 * 60 * 1000L
        const val ENTRY_SEPARATOR = ":"
        val syncMutex = Mutex()
    }
}

internal fun stableTvChannelKey(provider: Provider, channel: Channel): String =
    stableTvChannelKey(tvProviderHash(provider), channel)

/** Hash of the provider identity; compute once per sync and reuse for every channel/program key. */
internal fun tvProviderHash(provider: Provider): String {
    val normalizedUrl = runCatching {
        val uri = URI(provider.serverUrl.trim())
        val scheme = uri.scheme?.lowercase(Locale.ROOT).orEmpty()
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        val port = when {
            uri.port < 0 -> ""
            scheme == "http" && uri.port == 80 -> ""
            scheme == "https" && uri.port == 443 -> ""
            else -> ":${uri.port}"
        }
        "$scheme://$host$port${uri.rawPath.orEmpty().trimEnd('/')}"
    }.getOrElse { provider.serverUrl.trim().trimEnd('/').lowercase(Locale.ROOT) }
    val providerIdentity = listOf(
        normalizedUrl,
        provider.username.trim(),
        provider.type.name,
        provider.stalkerMacAddress.trim().uppercase(Locale.ROOT)
    ).joinToString("|")
    return sha256Prefix(providerIdentity)
}

internal fun stableTvChannelKey(providerHash: String, channel: Channel): String {
    val remoteStreamId = channel.streamId.takeIf { it != 0L }
        ?: error("TV input channel '${channel.name}' has no portable stream identity")
    return "$providerHash:$remoteStreamId"
}

internal fun stableTvProgramKey(channelKey: String, program: Program): String {
    val contentHash = sha256Prefix(
        listOf(program.title, program.description.orEmpty()).joinToString("|"),
        byteCount = 6
    )
    return "$channelKey:${program.startTime}:${program.endTime}:$contentHash"
}

private val HEX_DIGITS = "0123456789abcdef".toCharArray()

/** Lowercase hex of the first [byteCount] SHA-256 bytes (same output as per-byte "%02x"). */
internal fun sha256Prefix(value: String, byteCount: Int = 12): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    val count = byteCount.coerceIn(0, digest.size)
    val hex = CharArray(count * 2)
    for (index in 0 until count) {
        val unsigned = digest[index].toInt() and 0xff
        hex[index * 2] = HEX_DIGITS[unsigned ushr 4]
        hex[index * 2 + 1] = HEX_DIGITS[unsigned and 0x0f]
    }
    return String(hex)
}

internal fun shouldReplaceTvPrograms(channel: Channel, programs: List<Program>): Boolean {
    if (programs.isNotEmpty()) return true
    return channel.epgChannelId.isNullOrBlank()
}
