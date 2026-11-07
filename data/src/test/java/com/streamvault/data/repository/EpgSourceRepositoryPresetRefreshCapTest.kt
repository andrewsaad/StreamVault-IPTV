package com.streamvault.data.repository

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.epg.EpgPresetSourceRecord
import com.streamvault.data.epg.EpgPresetStateStore
import com.streamvault.data.epg.EpgResolutionEngine
import com.streamvault.data.epg.testPresetStore
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.ChannelEpgMappingDao
import com.streamvault.data.local.dao.EpgChannelDao
import com.streamvault.data.local.dao.EpgProgrammeDao
import com.streamvault.data.local.dao.EpgSourceDao
import com.streamvault.data.local.dao.ProviderEpgSourceDao
import com.streamvault.data.local.entity.EpgSourceEntity
import com.streamvault.data.local.entity.ProviderEpgSourceEntity
import com.streamvault.data.parser.XmltvParser
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.ContentType
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** FR-007 / FR-022 / SC-005: preset sources at most twice per 24 hours; hand-added sources unchanged. */
class EpgSourceRepositoryPresetRefreshCapTest {

    private val context: Context = mock()
    private val epgSourceDao: EpgSourceDao = mock()
    private val providerEpgSourceDao: ProviderEpgSourceDao = mock()
    private val okHttpClient: OkHttpClient = mock()
    private val epgHttpClientBuilder: OkHttpClient.Builder = mock()
    private val preferencesRepository: PreferencesRepository = mock()
    private val transactionRunner = object : DatabaseTransactionRunner {
        override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    }

    private var now = 1_700_000_000_000L
    private lateinit var store: EpgPresetStateStore
    private lateinit var repository: EpgSourceRepositoryImpl

    companion object {
        private const val PROVIDER_ID = 7L
        private const val PRESET_SOURCE = 10L
        private const val HAND_SOURCE = 20L
        private const val HOUR = 60L * 60L * 1000L
    }

    @Before
    fun setup() {
        whenever(preferencesRepository.getHiddenCategoryIds(any(), eq(ContentType.LIVE))).thenReturn(flowOf(emptySet()))
        whenever(okHttpClient.newBuilder()).thenReturn(epgHttpClientBuilder)
        whenever(epgHttpClientBuilder.readTimeout(any<Long>(), any())).thenReturn(epgHttpClientBuilder)
        whenever(epgHttpClientBuilder.build()).thenReturn(okHttpClient)
        // Every attempt fails, so nothing but the cap stops the next one.
        whenever(okHttpClient.newCall(any())).thenAnswer { invocation ->
            val request = invocation.arguments[0] as Request
            val call: Call = mock()
            val response = Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(503)
                .message("Unavailable")
                .body("".toResponseBody())
                .build()
            doAnswer { (it.arguments[0] as Callback).onResponse(call, response); null }
                .whenever(call).enqueue(any())
            call
        }
        store = testPresetStore { now }
        repository = EpgSourceRepositoryImpl(
            context = context,
            epgSourceDao = epgSourceDao,
            providerEpgSourceDao = providerEpgSourceDao,
            channelEpgMappingDao = mock<ChannelEpgMappingDao>(),
            epgChannelDao = mock<EpgChannelDao>(),
            epgProgrammeDao = mock<EpgProgrammeDao>(),
            xmltvParser = mock<XmltvParser>(),
            okHttpClient = okHttpClient,
            resolutionEngine = mock<EpgResolutionEngine>(),
            preferencesRepository = preferencesRepository,
            transactionRunner = transactionRunner,
            refreshGate = store,
        )
    }

    private suspend fun givenSources(handLastRefreshAt: Long = 0L) {
        whenever(epgSourceDao.getById(PRESET_SOURCE)).thenReturn(
            EpgSourceEntity(id = PRESET_SOURCE, name = "AE1", url = "https://example.com/ae1.xml.gz")
        )
        whenever(epgSourceDao.getById(HAND_SOURCE)).thenReturn(
            EpgSourceEntity(id = HAND_SOURCE, name = "Mine", url = "https://example.com/mine.xml", lastRefreshAt = handLastRefreshAt)
        )
        whenever(providerEpgSourceDao.getEnabledForProviderSync(PROVIDER_ID)).thenReturn(
            listOf(
                ProviderEpgSourceEntity(id = 1, providerId = PROVIDER_ID, epgSourceId = HAND_SOURCE, priority = 0),
                ProviderEpgSourceEntity(id = 2, providerId = PROVIDER_ID, epgSourceId = PRESET_SOURCE, priority = 1),
            )
        )
        store.update {
            it.copy(
                enabledPresetIds = setOf("egypt"),
                sources = mapOf("AE1" to EpgPresetSourceRecord(PRESET_SOURCE, owned = true, assignedProviderIds = setOf(PROVIDER_ID))),
            )
        }
    }

    private fun requestsFor(path: String) = org.mockito.kotlin.argThat<Request> { url.encodedPath == path }

    @Test
    fun `presetSource_hourlyRefreshesOver24Hours_attemptsAtMostTwiceIncludingFailures`() = runTest {
        givenSources()

        repeat(24) { hour ->
            now = 1_700_000_000_000L + hour * HOUR
            repository.refreshAllForProvider(PROVIDER_ID)
        }

        verify(okHttpClient, times(2)).newCall(requestsFor("/ae1.xml.gz"))
        verify(epgSourceDao, times(2)).updateRefreshError(eq(PRESET_SOURCE), any(), any())
    }

    @Test
    fun `presetSource_directRefreshInsideTheGap_doesNotDownload`() = runTest {
        givenSources()

        repository.refreshSource(PRESET_SOURCE)
        now += 11 * HOUR
        repository.refreshSource(PRESET_SOURCE)

        verify(okHttpClient, times(1)).newCall(requestsFor("/ae1.xml.gz"))
    }

    @Test
    fun `presetSource_cancelledImport_doesNotBurnTheAttempt`() = runTest {
        givenSources()
        whenever(okHttpClient.newCall(any())).thenThrow(CancellationException("left the screen"))

        val thrown = runCatching { repository.refreshSource(PRESET_SOURCE) }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(CancellationException::class.java)
        assertThat(store.current().lastAttemptAtBySourceId).isEmpty()
    }

    @Test
    fun `handAddedSource_isNotCappedByThePresetGap`() = runTest {
        givenSources()

        repeat(24) { hour ->
            now = 1_700_000_000_000L + hour * HOUR
            repository.refreshAllForProvider(PROVIDER_ID)
        }

        verify(okHttpClient, times(24)).newCall(requestsFor("/mine.xml"))
        assertThat(store.current().lastAttemptAtBySourceId.keys).containsExactly(PRESET_SOURCE)
    }

    @Test
    fun `handAddedSource_keepsTheFiveMinuteRule`() = runTest {
        givenSources(handLastRefreshAt = System.currentTimeMillis() - 60_000L)

        repository.refreshSource(HAND_SOURCE)

        verify(okHttpClient, never()).newCall(requestsFor("/mine.xml"))
    }
}
