package com.streamvault.data.local

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.local.entity.SyncContentHashEntity
import com.streamvault.data.local.entity.SyncContentHashScope
import com.streamvault.domain.model.ProviderType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SyncContentHashDaoTest {
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
    fun `replaceScope drops keys of categories that disappeared`() = runTest {
        insertProvider()
        val dao = database.syncContentHashDao()
        dao.replaceScope(PROVIDER_ID, SyncContentHashScope.LIVE_CATEGORY, listOf(row("1", "a"), row("2", "b")))
        dao.upsert(SyncContentHashEntity(PROVIDER_ID, SyncContentHashScope.EPG, SyncContentHashScope.SINGLE_KEY, "e", 1L))

        dao.replaceScope(PROVIDER_ID, SyncContentHashScope.LIVE_CATEGORY, listOf(row("2", "c")))

        assertThat(dao.getScope(PROVIDER_ID, SyncContentHashScope.LIVE_CATEGORY).associate { it.key to it.sha256 })
            .containsExactly("2", "c")
        assertThat(dao.get(PROVIDER_ID, SyncContentHashScope.EPG, SyncContentHashScope.SINGLE_KEY)).isEqualTo("e")
    }

    @Test
    fun `deleting a provider removes its hashes`() = runTest {
        insertProvider()
        val dao = database.syncContentHashDao()
        dao.upsert(SyncContentHashEntity(PROVIDER_ID, SyncContentHashScope.EPG, SyncContentHashScope.SINGLE_KEY, "e", 1L))

        database.providerDao().delete(PROVIDER_ID)

        assertThat(dao.get(PROVIDER_ID, SyncContentHashScope.EPG, SyncContentHashScope.SINGLE_KEY)).isNull()
    }

    @Test
    fun `migration 90 to 91 adds the hash column and table on an existing database`() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(90) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE providers (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL)")
                        db.execSQL(
                            "CREATE TABLE epg_sources (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                "name TEXT NOT NULL, url TEXT NOT NULL, etag TEXT)"
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        val db = helper.writableDatabase
        db.execSQL("INSERT INTO providers (id, name) VALUES (5, 'p')")
        db.execSQL("INSERT INTO epg_sources (id, name, url, etag) VALUES (6, 'g', 'https://example.invalid/g.xml', 'e1')")

        FeatureMigrationsV90To91.MIGRATION_90_91.migrate(db)

        db.query("SELECT etag, content_sha256 FROM epg_sources WHERE id = 6").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("e1")
            assertThat(cursor.isNull(1)).isTrue()
        }
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("INSERT INTO sync_content_hashes (provider_id, scope, `key`, sha256, updated_at) VALUES (5, 'EPG', '*', 'x', 1)")
        db.execSQL("DELETE FROM providers WHERE id = 5")
        db.query("SELECT COUNT(*) FROM sync_content_hashes").use { cursor ->
            cursor.moveToFirst()
            assertThat(cursor.getInt(0)).isEqualTo(0)
        }
        db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_sync_content_hashes_provider_id'").use { cursor ->
            assertThat(cursor.count).isEqualTo(1)
        }
        helper.close()
    }

    private fun row(key: String, sha: String) =
        SyncContentHashEntity(PROVIDER_ID, SyncContentHashScope.LIVE_CATEGORY, key, sha, 1L)

    private suspend fun insertProvider() {
        database.providerDao().insert(
            ProviderEntity(
                id = PROVIDER_ID,
                name = "Provider",
                type = ProviderType.XTREAM_CODES,
                serverUrl = "https://example.com"
            )
        )
    }

    private companion object {
        const val PROVIDER_ID = 1L
    }
}
