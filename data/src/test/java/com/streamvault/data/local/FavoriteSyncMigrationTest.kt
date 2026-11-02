package com.streamvault.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class FavoriteSyncMigrationTest {

    @Test
    fun `migration 91 to 92 adds the group sync id and state table on an existing database`() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(91) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE virtual_groups (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                "provider_id INTEGER NOT NULL, name TEXT NOT NULL, icon_emoji TEXT, " +
                                "position INTEGER NOT NULL, created_at INTEGER NOT NULL, content_type TEXT NOT NULL)"
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        val db = helper.writableDatabase
        db.execSQL("INSERT INTO virtual_groups VALUES (3, 1, 'Kids', NULL, 0, 0, 'LIVE')")

        FeatureMigrationsV91To92.MIGRATION_91_92.migrate(db)

        db.query("SELECT name, sync_uuid FROM virtual_groups WHERE id = 3").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("Kids")
            assertThat(cursor.isNull(1)).isTrue()
        }
        db.execSQL("INSERT INTO favorite_sync_state (`key`, value) VALUES ('dirty', '1')")
        db.query("SELECT value FROM favorite_sync_state WHERE `key` = 'dirty'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("1")
        }
        db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_virtual_groups_sync_uuid'").use { cursor ->
            assertThat(cursor.count).isEqualTo(1)
        }
        helper.close()
    }

    @Test
    fun `exported schema 92 matches the migration`() {
        val schema = JSONObject(File("schemas/com.streamvault.data.local.StreamVaultDatabase/92.json").readText())
            .getJSONObject("database")
        assertThat(schema.getInt("version")).isEqualTo(92)
        val entities = schema.getJSONArray("entities")
        val tables = (0 until entities.length()).map { entities.getJSONObject(it) }.associateBy { it.getString("tableName") }
        assertThat(tables).containsKey("favorite_sync_state")
        val groupColumns = tables.getValue("virtual_groups").getJSONArray("fields")
        assertThat((0 until groupColumns.length()).map { groupColumns.getJSONObject(it).getString("columnName") })
            .contains("sync_uuid")
    }
}
