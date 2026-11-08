package com.streamvault.data.epg

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.RoomDatabaseTransactionRunner
import com.streamvault.data.local.StreamVaultDatabase
import com.streamvault.data.local.entity.ChannelEntity
import com.streamvault.data.local.entity.ProgramEntity
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.parser.XmltvParser
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.repository.EpgSourceRepositoryImpl
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.EpgMatchType
import com.streamvault.domain.model.EpgPresetCatalog
import com.streamvault.domain.model.EpgSourceType
import com.streamvault.domain.model.ProviderType
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * F16: presets end to end over a real database, store, repository, parser and resolver; only
 * the network is faked. Covers the paths the architecture review called risky.
 */
@RunWith(RobolectricTestRunner::class)
class EpgPresetIntegrationTest {

    private lateinit var db: StreamVaultDatabase
    private lateinit var store: EpgPresetStateStore
    private lateinit var repository: EpgSourceRepositoryImpl
    private lateinit var manager: EpgPresetManagerImpl
    private val payloads = mutableMapOf<String, String>()
    private val okHttpClient: OkHttpClient = mock()
    // The repository stamps rows with the real clock, so the store and the guide use it too.
    private val now = System.currentTimeMillis()
    private val xmltvTime = java.text.SimpleDateFormat("yyyyMMddHHmmss Z", java.util.Locale.US)
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }

    private val ae1 = EpgPresetCatalog.byId(EpgPresetCatalog.EGYPT)!!.sources.single()

    companion object {
        private const val PROVIDER = 7L
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), StreamVaultDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = testPresetStore { now }
        val builder: OkHttpClient.Builder = mock()
        whenever(okHttpClient.newBuilder()).thenReturn(builder)
        whenever(builder.readTimeout(any<Long>(), any())).thenReturn(builder)
        whenever(builder.build()).thenReturn(okHttpClient)
        whenever(okHttpClient.newCall(any())).thenAnswer { invocation ->
            val request = invocation.arguments[0] as Request
            val call: Call = mock()
            val body = payloads[request.url.toString()]
            val response = Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(if (body == null) 404 else 200)
                .message(if (body == null) "Not Found" else "OK")
                .body((body?.let(::gzip) ?: ByteArray(0)).toResponseBody())
                .build()
            doAnswer { (it.arguments[0] as Callback).onResponse(call, response); null }
                .whenever(call).enqueue(any())
            call
        }
        val preferences: PreferencesRepository = mock()
        whenever(preferences.getHiddenCategoryIds(any(), eq(ContentType.LIVE))).thenReturn(flowOf(emptySet()))
        val engine = EpgResolutionEngine(
            channelDao = db.channelDao(),
            channelEpgMappingDao = db.channelEpgMappingDao(),
            providerEpgSourceDao = db.providerEpgSourceDao(),
            epgChannelDao = db.epgChannelDao(),
            epgProgrammeDao = db.epgProgrammeDao(),
            programDao = db.programDao(),
            providerSnapshotDao = db.providerSnapshotDao(),
            presetSources = store,
        )
        repository = EpgSourceRepositoryImpl(
            context = RuntimeEnvironment.getApplication(),
            epgSourceDao = db.epgSourceDao(),
            providerEpgSourceDao = db.providerEpgSourceDao(),
            channelEpgMappingDao = db.channelEpgMappingDao(),
            epgChannelDao = db.epgChannelDao(),
            epgProgrammeDao = db.epgProgrammeDao(),
            xmltvParser = XmltvParser(),
            okHttpClient = okHttpClient,
            resolutionEngine = engine,
            preferencesRepository = preferences,
            transactionRunner = RoomDatabaseTransactionRunner(db),
            refreshGate = store,
        )
        manager = EpgPresetManagerImpl(repository, EpgPresetProviderDirectory { listOf(PROVIDER) }, store)
    }

    @After
    fun tearDown() = db.close()

    private fun gzip(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray()) }
        return out.toByteArray()
    }

    private fun guide(vararg channels: Pair<String, String>, programmes: Boolean = true): String = buildString {
        append("<tv>")
        channels.forEach { (id, name) -> append("<channel id=\"$id\"><display-name>$name</display-name></channel>") }
        if (programmes) {
            val start = xmltvTime.format(java.util.Date(now - 30 * 60_000L))
            val stop = xmltvTime.format(java.util.Date(now + 90 * 60_000L))
            channels.forEach { (id, _) ->
                append("<programme channel=\"$id\" start=\"$start\" stop=\"$stop\"><title>Match</title></programme>")
            }
        }
        append("</tv>")
    }

    private suspend fun givenProviderWith(vararg channels: ChannelEntity) {
        db.providerDao().insert(ProviderEntity(id = PROVIDER, name = "Provider", type = ProviderType.M3U))
        db.channelDao().insertAll(channels.toList())
    }

    private fun channel(id: Long, name: String, epgId: String? = null) =
        ChannelEntity(id = id, name = name, epgChannelId = epgId, streamId = id, providerId = PROVIDER)

    private suspend fun mappingOf(channelId: Long) = db.channelEpgMappingDao().getForChannel(PROVIDER, channelId)

    @Test
    fun enablingEgypt_importsTheFile_andMatchesAnUnmatchedChannelByLooseName() = runTest {
        givenProviderWith(channel(1, "EG: ON Sport HD"))
        payloads[ae1.url] = guide("ON.Sport.eg" to "ON Sport")

        assertThat(manager.setEnabled(EpgPresetCatalog.EGYPT, true).isSuccess).isTrue()

        val sourceId = store.current().sources.getValue(ae1.key).sourceId
        assertThat(db.epgChannelDao().getBySources(listOf(sourceId)).map { it.xmltvChannelId }).containsExactly("ON.Sport.eg")
        val mapping = mappingOf(1)!!
        assertThat(mapping.epgSourceId).isEqualTo(sourceId)
        assertThat(mapping.xmltvChannelId).isEqualTo("ON.Sport.eg")
        assertThat(mapping.matchType).isEqualTo(EpgMatchType.NORMALIZED_NAME_LOOSE.name)
    }

    @Test
    fun aPreset_neverDisplacesAHandAddedExactMatch_orTheProvidersOwnGuide() = runTest {
        givenProviderWith(channel(1, "ON Sport", epgId = "mine.on"), channel(2, "Nile Sport", epgId = "nile.native"))
        // A guide source the user added by hand, matching channel 1 by id.
        payloads["https://example.com/mine.xml"] = guide("mine.on" to "ON Sport")
        val mine = repository.addSource("Mine", "https://example.com/mine.xml").getOrNull()!!
        repository.assignSourceToProvider(PROVIDER, mine.id, 0)
        repository.refreshSource(mine.id)
        // The provider's own guide covers channel 2.
        db.programDao().insertAll(
            listOf(ProgramEntity(providerId = PROVIDER, channelId = "nile.native", title = "News", startTime = now, endTime = now + 3_600_000L))
        )
        repository.resolveForProvider(PROVIDER, emptySet())
        payloads[ae1.url] = guide("ON.Sport.eg" to "ON Sport", "Nile.Sport.eg" to "Nile Sport")

        manager.setEnabled(EpgPresetCatalog.EGYPT, true)

        assertThat(mappingOf(1)!!.epgSourceId).isEqualTo(mine.id)
        assertThat(mappingOf(1)!!.matchType).isEqualTo(EpgMatchType.EXACT_ID.name)
        assertThat(mappingOf(2)!!.sourceType).isEqualTo(EpgSourceType.PROVIDER.name)
    }

    @Test
    fun anImportLostToProcessDeath_isRetriedByReconcileInsideTheGap() = runTest {
        givenProviderWith(channel(1, "ON Sport"))
        payloads[ae1.url] = guide("ON.Sport.eg" to "ON Sport")
        manager.setEnabled(EpgPresetCatalog.EGYPT, true)
        val sourceId = store.current().sources.getValue(ae1.key).sourceId
        // What a process death mid-import leaves: the attempt recorded, no outcome, no guide rows.
        db.epgSourceDao().updateRefreshSuccess(sourceId, 0L)
        db.epgProgrammeDao().deleteBySource(sourceId)
        db.epgChannelDao().deleteBySource(sourceId)
        db.channelEpgMappingDao().deleteByProvider(PROVIDER)
        assertThat(store.isInsideAttemptGap(store.current(), sourceId)).isTrue()

        manager.reconcile()

        assertThat(db.epgChannelDao().getBySources(listOf(sourceId))).hasSize(1)
        assertThat(mappingOf(1)!!.epgSourceId).isEqualTo(sourceId)
    }

    @Test
    fun anEmptyFile_keepsTheLastGoodGuideAndShowsTheFailure() = runTest {
        givenProviderWith(channel(1, "ON Sport"))
        payloads[ae1.url] = guide("ON.Sport.eg" to "ON Sport")
        manager.setEnabled(EpgPresetCatalog.EGYPT, true)
        val sourceId = store.current().sources.getValue(ae1.key).sourceId
        payloads[ae1.url] = guide("ON.Sport.eg" to "ON Sport", programmes = false)
        store.clearRefreshAttempt(sourceId)
        db.epgSourceDao().updateRefreshSuccess(sourceId, 0L) // past the 5-minute rule

        val result = manager.refreshNow(EpgPresetCatalog.EGYPT)

        assertThat(result.isSuccess).isFalse()
        assertThat(db.epgProgrammeDao().countBySource(sourceId)).isEqualTo(1)
        val status = manager.observe().first().single { it.presetId == EpgPresetCatalog.EGYPT }
        assertThat(status.lastError).isNotNull()
        assertThat(mappingOf(1)!!.epgSourceId).isEqualTo(sourceId)
    }

    @Test
    fun turningThePresetOff_removesItsRowGuideAndMatches_andKeepsAHandAddedSource() = runTest {
        givenProviderWith(channel(1, "ON Sport"), channel(2, "Mine TV", epgId = "mine.tv"))
        payloads["https://example.com/mine.xml"] = guide("mine.tv" to "Mine TV")
        val mine = repository.addSource("Mine", "https://example.com/mine.xml").getOrNull()!!
        repository.assignSourceToProvider(PROVIDER, mine.id, 0)
        repository.refreshSource(mine.id)
        payloads[ae1.url] = guide("ON.Sport.eg" to "ON Sport")
        manager.setEnabled(EpgPresetCatalog.EGYPT, true)
        val sourceId = store.current().sources.getValue(ae1.key).sourceId

        manager.setEnabled(EpgPresetCatalog.EGYPT, false)

        assertThat(db.epgSourceDao().getById(sourceId)).isNull()
        assertThat(db.epgChannelDao().getBySources(listOf(sourceId))).isEmpty()
        assertThat(db.epgProgrammeDao().countBySource(sourceId)).isEqualTo(0)
        assertThat(mappingOf(1)!!.epgSourceId).isNull()
        assertThat(db.epgSourceDao().getById(mine.id)).isNotNull()
        assertThat(mappingOf(2)!!.epgSourceId).isEqualTo(mine.id)
    }
}
