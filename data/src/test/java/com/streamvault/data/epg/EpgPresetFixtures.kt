package com.streamvault.data.epg

import com.streamvault.data.local.dao.ChannelDao
import com.streamvault.data.local.dao.ChannelEpgMappingDao
import com.streamvault.data.local.dao.EpgChannelDao
import com.streamvault.data.local.dao.EpgProgrammeDao
import com.streamvault.data.local.dao.ProgramDao
import com.streamvault.data.local.dao.ProviderEpgSourceDao
import com.streamvault.data.local.dao.ProviderSnapshotDao
import com.streamvault.data.local.entity.ChannelEntity
import com.streamvault.data.local.entity.ChannelEpgMappingEntity
import com.streamvault.data.local.entity.EpgChannelEntity
import com.streamvault.data.local.entity.ProviderEpgSourceEntity
import com.streamvault.domain.model.EpgResolutionSummary
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atMost
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** Reads the tab-separated fixtures under src/test/resources/epg_presets. */
internal object EpgPresetFixtures {

    fun lines(name: String): List<List<String>> {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("epg_presets/$name")) {
            "missing fixture epg_presets/$name"
        }
        return stream.bufferedReader(Charsets.UTF_8).useLines { sequence ->
            sequence
                .map { it.trimEnd('\r') }
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .map { it.split('\t') }
                .toList()
        }
    }

    /** Guide channels from a `xmltv id <TAB> display name` file, all in [sourceId]. */
    fun guideChannels(name: String, sourceId: Long): List<EpgChannelEntity> =
        lines(name).mapIndexed { index, parts ->
            val displayName = parts.getOrElse(1) { "" }
            EpgChannelEntity(
                id = index + 1L,
                epgSourceId = sourceId,
                xmltvChannelId = parts[0],
                displayName = displayName,
                normalizedName = EpgNameNormalizer.normalize(displayName),
            )
        }

    /** `provider name <TAB> expected id or -` pairs; null means no match is expected. */
    fun expectations(name: String): List<Pair<String, String?>> =
        lines(name).map { parts -> parts[0] to parts.getOrNull(1)?.takeUnless { it == "-" } }

    /** One value per line (first column). */
    fun values(name: String): List<String> = lines(name).map { it[0] }
}

/** Runs [EpgResolutionEngine.resolveForProvider] over in-memory rows and returns what it would store. */
internal object EpgResolutionHarness {
    const val PROVIDER_ID = 1L

    suspend fun resolve(
        channels: List<ChannelEntity>,
        guideBySource: List<Pair<Long, List<EpgChannelEntity>>>,
        existing: List<ChannelEpgMappingEntity> = emptyList(),
    ): Pair<EpgResolutionSummary, List<ChannelEpgMappingEntity>> {
        val channelDao: ChannelDao = mock()
        val mappingDao: ChannelEpgMappingDao = mock()
        val providerEpgSourceDao: ProviderEpgSourceDao = mock()
        val epgChannelDao: EpgChannelDao = mock()
        val programDao: ProgramDao = mock()
        val snapshotDao: ProviderSnapshotDao = mock()
        whenever(programDao.getChannelIdsWithPrograms(any(), any())).thenReturn(emptyList())
        whenever(snapshotDao.getConfig(any())).thenReturn(null)
        whenever(channelDao.getByProviderSync(PROVIDER_ID)).thenReturn(channels)
        whenever(mappingDao.getForProvider(PROVIDER_ID)).thenReturn(existing)
        val sourceIds = guideBySource.map { it.first }
        whenever(providerEpgSourceDao.getEnabledForProviderSync(PROVIDER_ID)).thenReturn(
            sourceIds.mapIndexed { index, sourceId ->
                ProviderEpgSourceEntity(id = index + 1L, providerId = PROVIDER_ID, epgSourceId = sourceId, priority = index)
            }
        )
        whenever(epgChannelDao.getBySources(sourceIds)).thenReturn(guideBySource.flatMap { it.second })
        val engine = EpgResolutionEngine(
            channelDao = channelDao,
            channelEpgMappingDao = mappingDao,
            providerEpgSourceDao = providerEpgSourceDao,
            epgChannelDao = epgChannelDao,
            epgProgrammeDao = mock<EpgProgrammeDao>(),
            programDao = programDao,
            providerSnapshotDao = snapshotDao,
        )
        val summary = engine.resolveForProvider(PROVIDER_ID)
        val captor = argumentCaptor<List<ChannelEpgMappingEntity>>()
        verify(mappingDao, atMost(1)).replaceForProvider(any(), captor.capture())
        return summary to (captor.allValues.firstOrNull() ?: existing)
    }

    fun channel(id: Long, name: String, epgChannelId: String? = null) =
        ChannelEntity(id = id, name = name, epgChannelId = epgChannelId, providerId = PROVIDER_ID)

    fun guide(sourceId: Long, xmltvId: String, displayName: String, rowId: Long = 0L) = EpgChannelEntity(
        id = rowId,
        epgSourceId = sourceId,
        xmltvChannelId = xmltvId,
        displayName = displayName,
        normalizedName = EpgNameNormalizer.normalize(displayName),
    )
}
