package com.streamvault.data.local

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.entity.FavoriteEntity
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.ProviderStatus
import com.streamvault.domain.model.ProviderType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class FavoriteStaleCleanupDaoTest {

    private lateinit var database: StreamVaultDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            StreamVaultDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `stale favorites of a provider with a failed or partial sync are kept`() = runTest {
        ProviderStatus.entries.forEach { status ->
            insertProviderWithStaleFavorite(status.ordinal + 1L, status)
        }

        val deleted = database.favoriteDao().deleteMissingLiveFavorites()

        val surviving = ProviderStatus.entries.filter { status ->
            liveFavoriteCount(status.ordinal + 1L) == 1
        }
        assertThat(surviving).containsExactly(ProviderStatus.ERROR, ProviderStatus.PARTIAL)
        assertThat(deleted).isEqualTo(ProviderStatus.entries.size - 2)
    }

    @Test
    fun `stale favorites are deleted after a healthy sync`() = runTest {
        insertProviderWithStaleFavorite(1L, ProviderStatus.PARTIAL)
        assertThat(database.favoriteDao().deleteMissingLiveFavorites()).isEqualTo(0)
        assertThat(liveFavoriteCount(1L)).isEqualTo(1)

        database.providerDao().update(
            database.providerDao().getById(1L)!!.copy(status = ProviderStatus.ACTIVE)
        )

        assertThat(database.favoriteDao().deleteMissingLiveFavorites()).isEqualTo(1)
        assertThat(liveFavoriteCount(1L)).isEqualTo(0)
    }

    private suspend fun insertProviderWithStaleFavorite(providerId: Long, status: ProviderStatus) {
        database.providerDao().insert(
            ProviderEntity(
                id = providerId,
                name = "P$providerId",
                type = ProviderType.XTREAM_CODES,
                serverUrl = "x",
                status = status
            )
        )
        // No channel row exists for this content id, so the favorite is stale.
        database.favoriteDao().insert(
            FavoriteEntity(providerId = providerId, contentId = 900L + providerId, contentType = ContentType.LIVE, position = 0)
        )
    }

    private suspend fun liveFavoriteCount(providerId: Long): Int =
        database.favoriteDao().getGlobalByType(providerId, "LIVE").first().size
}
