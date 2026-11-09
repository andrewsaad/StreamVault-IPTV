package com.streamvault.data.health

import com.streamvault.data.local.entity.ChannelEntity
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.M3uConfig
import com.streamvault.domain.model.Provider
import com.streamvault.domain.model.ProviderSnapshot
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.XtreamConfig
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.MutableStateFlow
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

internal const val DAY_MS = 24L * 60L * 60L * 1000L
internal const val TEST_PROVIDER_ID = 1L
internal const val TEST_USERNAME = "secretuser"
internal const val TEST_PASSWORD = "secretpass"
internal const val TEST_SERVER = "http://provider.test:8080"

/** A clock the test moves by hand. */
internal class TestClock(var nowMillis: Long, private val zoneId: ZoneId = ZoneOffset.UTC) : Clock() {
    override fun getZone(): ZoneId = zoneId
    override fun withZone(zone: ZoneId): Clock = TestClock(nowMillis, zone)
    override fun instant(): Instant = Instant.ofEpochMilli(nowMillis)
}

/** Preferences backed by two in-memory values: the health results JSON and the hidden channel ids. */
internal class InMemoryHealthPreferences(initialHidden: Set<Long> = emptySet()) {
    val health = MutableStateFlow<String?>(null)
    val hidden = MutableStateFlow(initialHidden)
    val preferences: PreferencesRepository = mock {
        on { getChannelHealthResults(any()) } doReturn health
        on { getHiddenChannelIds(any()) } doReturn hidden
        onBlocking { setChannelHealthResults(any(), anyOrNull()) } doAnswer {
            health.value = it.getArgument<String?>(1)
            Unit
        }
        onBlocking { addHiddenChannelIds(any(), any()) } doAnswer {
            hidden.value = hidden.value + it.getArgument<Set<Long>>(1)
            Unit
        }
        onBlocking { setHiddenChannelIds(any(), any()) } doAnswer {
            hidden.value = it.getArgument<Set<Long>>(1)
            Unit
        }
    }
}

internal fun xtreamSnapshot(providerId: Long = TEST_PROVIDER_ID): ProviderSnapshot = ProviderSnapshot(
    provider = Provider(id = providerId, name = "Test", type = ProviderType.XTREAM_CODES),
    configuration = XtreamConfig(serverUrl = TEST_SERVER, username = TEST_USERNAME, password = TEST_PASSWORD),
    configurationGeneration = 1L,
)

internal fun m3uSnapshot(providerId: Long = TEST_PROVIDER_ID): ProviderSnapshot = ProviderSnapshot(
    provider = Provider(id = providerId, name = "Test", type = ProviderType.M3U),
    configuration = M3uConfig(playlistUrl = "http://playlist.test/list.m3u"),
    configurationGeneration = 1L,
)

internal fun channelEntity(
    id: Long,
    name: String = "Channel $id",
    category: String? = "News",
    categoryId: Long? = 100L,
    streamId: Long = id + 10L,
): ChannelEntity = ChannelEntity(
    id = id,
    streamId = streamId,
    name = name,
    categoryId = categoryId,
    categoryName = category,
    providerId = TEST_PROVIDER_ID,
)
