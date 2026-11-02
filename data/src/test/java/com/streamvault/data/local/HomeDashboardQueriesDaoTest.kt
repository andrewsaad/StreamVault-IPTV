package com.streamvault.data.local

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.entity.ChannelEntity
import com.streamvault.data.local.entity.MovieEntity
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.local.entity.SeriesEntity
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
class HomeDashboardQueriesDaoTest {

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
    fun `fresh movies by categories keeps only the requested categories newest first`() = runTest {
        insertProviders()
        database.movieDao().insertAll(
            listOf(
                movie(1L, "Old Egyptian", categoryId = 10L, addedAt = 100L),
                movie(2L, "New Egyptian", categoryId = 11L, addedAt = 300L),
                movie(3L, "English", categoryId = 20L, addedAt = 900L),
                movie(4L, "Undated Egyptian", categoryId = 10L, addedAt = 0L),
                movie(5L, "Mid Egyptian", categoryId = 10L, addedAt = 200L),
                movie(6L, "Other provider", categoryId = 10L, addedAt = 999L, providerId = 2L),
            )
        )

        val newest = database.movieDao().getFreshByCategoriesPreview(1L, listOf(10L, 11L), limit = 2).first()

        assertThat(newest.map { it.name }).containsExactly("New Egyptian", "Mid Egyptian").inOrder()
    }

    @Test
    fun `fresh series by categories orders by last update and skips never updated rows`() = runTest {
        insertProviders()
        database.seriesDao().insertAll(
            listOf(
                series(1L, "Turkish A", categoryId = 30L, lastModified = 50L),
                series(2L, "Turkish B", categoryId = 31L, lastModified = 70L),
                series(3L, "Turkish never updated", categoryId = 30L, lastModified = 0L),
                series(4L, "Arabic", categoryId = 40L, lastModified = 999L),
            )
        )

        val newest = database.seriesDao().getFreshByCategoriesPreview(1L, listOf(30L, 31L), limit = 20).first()

        assertThat(newest.map { it.name }).containsExactly("Turkish B", "Turkish A").inOrder()
    }

    @Test
    fun `category queries use an index instead of scanning the table`() {
        val db = database.openHelper.readableDatabase
        val moviePlan = db.query(
            "EXPLAIN QUERY PLAN SELECT * FROM movies WHERE provider_id = 1 AND category_id IN (10, 11) " +
                "AND added_at > 0 ORDER BY added_at DESC, name ASC, id ASC LIMIT 20"
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("detail"))) }
        }.joinToString("\n")
        val seriesPlan = db.query(
            "EXPLAIN QUERY PLAN SELECT * FROM series WHERE provider_id = 1 AND category_id IN (10, 11) " +
                "AND last_modified > 0 ORDER BY last_modified DESC, name ASC, id ASC LIMIT 20"
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("detail"))) }
        }.joinToString("\n")

        assertThat(moviePlan).contains("USING INDEX")
        assertThat(seriesPlan).contains("USING INDEX")
    }

    @Test
    fun `sports candidates list beIN channels first and ignore unrelated names`() = runTest {
        insertProviders()
        database.channelDao().insertAll(
            listOf(
                channel(1L, "Sky Sports Main Event", number = 1),
                channel(2L, "beIN_SPORTS_1Xtra_1080p", number = 9),
                channel(3L, "MBC 1", number = 2),
                channel(4L, "beIN Sport 1 FHD Q", number = 5),
                channel(5L, "SSC Sport 1", number = 3),
                channel(6L, "BEIN SPORTS 2", number = 4, providerId = 2L),
                channel(7L, "Al Jazeera", number = 6),
            )
        )

        val candidates = database.channelDao().getSportsNameCandidates(1L, limit = 10)

        assertThat(candidates.map { it.name }).containsExactly(
            "beIN Sport 1 FHD Q",
            "beIN_SPORTS_1Xtra_1080p",
            "Sky Sports Main Event",
            "SSC Sport 1",
        ).inOrder()
        assertThat(database.channelDao().getSportsNameCandidates(1L, limit = 1).map { it.id })
            .containsExactly(4L)
    }

    private suspend fun insertProviders() {
        database.providerDao().insert(ProviderEntity(id = 1L, name = "One", type = ProviderType.M3U))
        database.providerDao().insert(ProviderEntity(id = 2L, name = "Two", type = ProviderType.M3U))
    }

    private fun movie(id: Long, name: String, categoryId: Long, addedAt: Long, providerId: Long = 1L) = MovieEntity(
        id = id,
        streamId = id,
        name = name,
        categoryId = categoryId,
        providerId = providerId,
        addedAt = addedAt
    )

    private fun series(id: Long, name: String, categoryId: Long, lastModified: Long) = SeriesEntity(
        id = id,
        seriesId = id,
        name = name,
        categoryId = categoryId,
        providerId = 1L,
        lastModified = lastModified
    )

    private fun channel(id: Long, name: String, number: Int, providerId: Long = 1L) = ChannelEntity(
        id = id,
        streamId = id,
        name = name,
        number = number,
        providerId = providerId
    )
}
