package com.streamvault.data.manager

import android.content.Context
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.StreamVaultDatabase
import com.streamvault.data.local.entity.EpgSourceEntity
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.local.entity.ProviderEpgSourceEntity
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.provider.toProviderSnapshot
import com.streamvault.domain.manager.BackupExportPlan
import com.streamvault.domain.manager.RecordingManager
import com.streamvault.domain.model.LegacyProvider
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.RecordingStorageState
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.ProviderSnapshotRepository
import java.io.File
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Preset rows are device-local (FR-026), so a backup must not carry them to another device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupExportPresetSourcesTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val database = Room.inMemoryDatabaseBuilder(context, StreamVaultDatabase::class.java)
        .allowMainThreadQueries().build()
    private val output = File.createTempFile("preset-export-", ".json", context.cacheDir)

    private companion object {
        const val PROVIDER_ID = 18L
        const val HAND_ADDED_URL = "https://example.com/mine.xml"
        const val PRESET_URL = "https://example.com/preset.xml.gz"
    }

    @After
    fun tearDown() {
        database.close()
        output.delete()
    }

    private suspend fun export(): com.google.gson.JsonObject {
        val preferences = PreferencesRepository(
            context, database.channelPreferenceDao(), database.playbackQualityPreferenceDao(),
            database.searchHistoryDao(), mock()
        )
        val recordings: RecordingManager = mock()
        whenever(recordings.observeRecordingItems()).thenReturn(flowOf(emptyList()))
        whenever(recordings.observeStorageState()).thenReturn(flowOf(RecordingStorageState()))
        val provider = LegacyProvider(
            id = PROVIDER_ID, name = "Source", type = ProviderType.XTREAM_CODES,
            serverUrl = "https://example.com", username = "user", password = "secret"
        )
        val snapshots: ProviderSnapshotRepository = mock()
        whenever(snapshots.getSnapshot(PROVIDER_ID)).thenReturn(provider.toProviderSnapshot())
        database.providerDao().insert(ProviderEntity(id = PROVIDER_ID, name = provider.name, type = provider.type))
        val handAddedId = database.epgSourceDao().insert(EpgSourceEntity(name = "Mine", url = HAND_ADDED_URL))
        val presetId = database.epgSourceDao().insert(EpgSourceEntity(name = "epgshare01 AE1", url = PRESET_URL))
        database.providerEpgSourceDao().insert(
            ProviderEpgSourceEntity(providerId = PROVIDER_ID, epgSourceId = handAddedId, priority = 0)
        )
        database.providerEpgSourceDao().insert(
            ProviderEpgSourceEntity(providerId = PROVIDER_ID, epgSourceId = presetId, priority = 1)
        )
        val manager = BackupManagerImpl(
            context, preferences, mock(), database.providerDao(), database.favoriteDao(), database.virtualGroupDao(),
            database.playbackHistoryDao(), database.movieDao(), database.episodeDao(),
            mock<com.streamvault.domain.repository.CategoryRepository>().also {
                whenever(it.getCategories(PROVIDER_ID)).thenReturn(flowOf(emptyList()))
            }, database.recordingScheduleDao(), recordings,
            object : DatabaseTransactionRunner {
                override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
            }, Gson(), providerSnapshotRepository = snapshots, channelDao = database.channelDao(),
            seriesDao = database.seriesDao(), epgSourceDao = database.epgSourceDao(),
            providerEpgSourceDao = database.providerEpgSourceDao(),
            presetSources = { setOf(presetId) },
        )
        val providersOnly = BackupExportPlan(false, true, false, false, false, false, false)
        assertThat(manager.exportConfig(output.toURI().toString(), providersOnly)).isInstanceOf(Result.Success::class.java)
        return JsonParser.parseString(output.readText()).asJsonObject
    }

    @Test
    fun `export_leavesOutPresetRows_andKeepsHandAddedOnes`() = runBlocking<Unit> {
        val json = export()

        val sourceUrls = json["epgSources"].asJsonArray.map { it.asJsonObject["url"].asString }
        assertThat(sourceUrls).containsExactly(HAND_ADDED_URL)
    }

    @Test
    fun `export_leavesOutPresetAssignments_andKeepsHandAddedOnes`() = runBlocking<Unit> {
        val json = export()

        val assignedUrls = json["providerEpgAssignments"].asJsonArray.map { it.asJsonObject["sourceUrl"].asString }
        assertThat(assignedUrls).containsExactly(HAND_ADDED_URL)
    }
}
