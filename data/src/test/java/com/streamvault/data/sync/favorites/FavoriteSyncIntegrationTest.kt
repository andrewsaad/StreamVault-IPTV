package com.streamvault.data.sync.favorites

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.RoomDatabaseTransactionRunner
import com.streamvault.data.local.StreamVaultDatabase
import com.streamvault.data.local.entity.ChannelEntity
import com.streamvault.data.local.entity.FavoriteEntity
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.repository.FavoriteRepositoryImpl
import com.streamvault.domain.manager.FavoriteSyncError
import com.streamvault.domain.manager.FavoriteSyncJoinMode
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.ProviderType
import java.security.MessageDigest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Several simulated TVs, each with its own in-memory database, syncing through an in-test server
 * that implements the v1 contract (CAS on quoted SHA-256 ETags). No network is involved.
 */
@RunWith(RobolectricTestRunner::class)
class FavoriteSyncIntegrationTest {
    private val server = FakeSyncServer()
    private val tvs = mutableListOf<Tv>()

    @After
    fun tearDown() {
        tvs.forEach { it.database.close() }
    }

    @Test
    fun `merge join unions both TVs and later edits flow both ways`() = runTest {
        val a = tv(providerId = 1, streams = listOf(1, 2, 3, 5))
        val b = tv(providerId = 7, streams = listOf(1, 2, 3, 5))
        a.addLive(1); a.addLive(2)
        b.addLive(2); b.addLive(3)

        a.pair(); b.pair()
        assertThat(a.engine.sync()).isEqualTo(FavoriteSyncOutcome.Synced)
        assertThat(b.engine.sync()).isEqualTo(FavoriteSyncOutcome.Synced)
        assertThat(a.engine.sync()).isEqualTo(FavoriteSyncOutcome.Synced)
        assertThat(a.globalLiveStreams()).containsExactly(1L, 2L, 3L)
        assertThat(b.globalLiveStreams()).containsExactly(1L, 2L, 3L)

        // Concurrent offline edits on both TVs merge per item.
        a.removeLive(2)
        b.addLive(5)
        a.engine.sync(); b.engine.sync(); a.engine.sync()

        assertThat(a.globalLiveStreams()).containsExactly(1L, 3L, 5L)
        assertThat(b.globalLiveStreams()).containsExactly(1L, 3L, 5L)
    }

    @Test
    fun `reorder on one TV becomes the order on the other`() = runTest {
        val a = tv(1, listOf(1, 2, 3))
        val b = tv(7, listOf(1, 2, 3))
        a.addLive(1); a.addLive(2); a.addLive(3)
        a.pair(); b.pair()
        a.engine.sync(); b.engine.sync()

        val favorites = a.repository.getFavorites(1L, ContentType.LIVE).first()
        a.repository.reorderFavorites(listOf(favorites[2], favorites[0], favorites[1]))
        a.engine.sync(); b.engine.sync()

        assertThat(b.globalLiveStreams()).containsExactly(3L, 1L, 2L).inOrder()
    }

    @Test
    fun `catalog maintenance deletions do not spread and come back when the catalog does`() = runTest {
        val a = tv(1, listOf(1, 2))
        val b = tv(7, listOf(1, 2))
        a.addLive(1); a.addLive(2)
        a.pair(); b.pair()
        a.engine.sync(); b.engine.sync()

        // A provider refresh on TV A drops stream 2, then cleanup deletes the stale favorite row.
        a.database.openHelper.writableDatabase.execSQL("DELETE FROM channels WHERE provider_id = 1 AND stream_id = 2")
        a.database.favoriteDao().deleteMissingLiveFavorites()
        a.engine.applyLocal()
        assertThat(a.engine.sync()).isEqualTo(FavoriteSyncOutcome.Synced)
        b.engine.sync()

        assertThat(b.globalLiveStreams()).containsExactly(1L, 2L)
        assertThat(a.state.unmatchedCount()).isEqualTo(1)

        // The next catalog commit brings stream 2 back; the hook re-applies the synced favorite.
        a.insertChannels(listOf(2))
        a.engine.applyLocal()
        assertThat(a.globalLiveStreams()).containsExactly(1L, 2L)
        assertThat(a.state.unmatchedCount()).isEqualTo(0)
    }

    @Test
    fun `items missing from a catalog stay pending until the catalog has them`() = runTest {
        val a = tv(1, listOf(1, 4))
        val b = tv(7, listOf(1))
        a.pair(); b.pair()
        a.engine.sync(); b.engine.sync()

        a.addLive(4)
        a.engine.sync(); b.engine.sync()
        assertThat(b.globalLiveStreams()).isEmpty()
        assertThat(b.state.unmatchedCount()).isEqualTo(1)

        // B's own edit must not drop the pending item from the shared document.
        b.addLive(1)
        b.engine.sync(); a.engine.sync()
        assertThat(a.globalLiveStreams()).containsExactly(4L, 1L)

        b.insertChannels(listOf(4))
        b.engine.applyLocal()
        assertThat(b.globalLiveStreams()).containsExactly(4L, 1L)
    }

    @Test
    fun `backup restore spreads to other TVs as a fresh change`() = runTest {
        val a = tv(1, listOf(1, 2, 3))
        val b = tv(7, listOf(1, 2, 3))
        a.addLive(1); a.addLive(2)
        a.pair(); b.pair()
        a.engine.sync(); b.engine.sync()

        // Simulates a REPLACE_EXISTING restore on B that brings back only stream 3.
        b.database.favoriteDao().deleteByProviderAndType(7L, ContentType.LIVE.name)
        b.database.favoriteDao().insert(FavoriteEntity(providerId = 7L, contentId = b.channelId(3), contentType = ContentType.LIVE))
        b.recorder.restoreApplied(listOf(FavoriteSyncRestoreScope(7L, ContentType.LIVE, replaceGlobal = true, replaceGroups = true)))
        b.engine.sync(); a.engine.sync()

        assertThat(a.globalLiveStreams()).containsExactly(3L)
    }

    @Test
    fun `groups sync with members, renames and deletes`() = runTest {
        val a = tv(1, listOf(1, 2))
        val b = tv(7, listOf(1, 2))
        a.pair(); b.pair()
        a.engine.sync(); b.engine.sync()

        val group = a.repository.createGroup(1L, "Sports", "S", ContentType.LIVE).getOrNull()!!
        a.repository.addFavorite(1L, a.channelId(1), ContentType.LIVE, group.id)
        a.engine.sync(); b.engine.sync()

        val bGroup = b.database.virtualGroupDao().getByType(7L, ContentType.LIVE.name).first().single()
        assertThat(bGroup.name).isEqualTo("Sports")
        assertThat(b.groupStreams(bGroup.id)).containsExactly(1L)

        b.repository.renameGroup(bGroup.id, "Football")
        b.engine.sync(); a.engine.sync()
        assertThat(a.database.virtualGroupDao().getById(group.id)?.name).isEqualTo("Football")

        a.repository.deleteGroup(group.id)
        a.engine.sync(); b.engine.sync()
        assertThat(b.database.virtualGroupDao().getByType(7L, ContentType.LIVE.name).first()).isEmpty()
        assertThat(b.globalLiveStreams()).containsExactly(1L)
    }

    @Test
    fun `same named groups created before pairing are merged on join`() = runTest {
        val a = tv(1, listOf(1, 2))
        val b = tv(7, listOf(1, 2))
        val aGroup = a.repository.createGroup(1L, "Kids", null, ContentType.LIVE).getOrNull()!!
        a.repository.addFavorite(1L, a.channelId(1), ContentType.LIVE, aGroup.id)
        val bGroup = b.repository.createGroup(7L, " kids ", null, ContentType.LIVE).getOrNull()!!
        b.repository.addFavorite(7L, b.channelId(2), ContentType.LIVE, bGroup.id)

        a.pair(); b.pair()
        a.engine.sync(); b.engine.sync(); a.engine.sync()

        assertThat(a.database.virtualGroupDao().getByType(1L, ContentType.LIVE.name).first()).hasSize(1)
        assertThat(b.database.virtualGroupDao().getByType(7L, ContentType.LIVE.name).first()).hasSize(1)
        assertThat(a.groupStreams(aGroup.id)).containsExactly(1L, 2L)
        assertThat(b.groupStreams(bGroup.id)).containsExactly(1L, 2L)
    }

    @Test
    fun `upload this TV replaces the server copy and use server replaces the local copy`() = runTest {
        val a = tv(1, listOf(1, 2, 3))
        a.addLive(1); a.addLive(2)
        a.pair(); a.engine.sync()

        val b = tv(7, listOf(1, 2, 3))
        b.addLive(3)
        b.pair(FavoriteSyncJoinMode.UPLOAD_THIS_TV)
        b.engine.sync(); a.engine.sync()
        assertThat(a.globalLiveStreams()).containsExactly(3L)

        val c = tv(9, listOf(1, 2, 3))
        c.addLive(1)
        c.pair(FavoriteSyncJoinMode.USE_SERVER)
        c.engine.sync()
        assertThat(c.globalLiveStreams()).containsExactly(3L)
    }

    @Test
    fun `a lost compare and swap re-merges and retries`() = runTest {
        val a = tv(1, listOf(1, 2))
        val b = tv(7, listOf(1, 2))
        a.pair(); b.pair()
        a.engine.sync(); b.engine.sync()

        a.addLive(1)
        b.addLive(2)
        // B uploads between A's fetch and A's upload, so A's first PUT gets 412 and must merge B's change.
        server.beforeNextStore = { b.engine.sync() }
        assertThat(a.engine.sync()).isEqualTo(FavoriteSyncOutcome.Synced)
        assertThat(server.conflicts).isAtLeast(1)
        b.engine.sync()

        assertThat(a.globalLiveStreams()).containsExactly(1L, 2L)
        assertThat(b.globalLiveStreams()).containsExactly(2L, 1L)
    }

    @Test
    fun `wrong passphrase never overwrites the server copy`() = runTest {
        val a = tv(1, listOf(1))
        a.addLive(1)
        a.pair(); a.engine.sync()
        val before = server.body?.copyOf()

        val c = tv(9, listOf(1))
        c.pair(passphrase = "a different passphrase")
        val outcome = c.engine.sync()

        assertThat(outcome).isEqualTo(FavoriteSyncOutcome.Failed(FavoriteSyncError.PAIRING_MISMATCH))
        assertThat(server.body).isEqualTo(before)
        assertThat(c.state.lastError()).isEqualTo(FavoriteSyncError.PAIRING_MISMATCH)
    }

    @Test
    fun `server failures map to status errors and keep local changes pending`() = runTest {
        val a = tv(1, listOf(1))
        a.pair(); a.engine.sync()
        a.addLive(1)

        server.failure = FavoriteSyncFailure.UNREACHABLE
        assertThat(a.engine.sync()).isEqualTo(FavoriteSyncOutcome.Failed(FavoriteSyncError.SERVER_UNREACHABLE))
        server.failure = FavoriteSyncFailure.UNAUTHORIZED
        assertThat(a.engine.sync()).isEqualTo(FavoriteSyncOutcome.Failed(FavoriteSyncError.UNAUTHORIZED))
        assertThat(a.state.isDirty()).isTrue()

        server.failure = null
        assertThat(a.engine.sync()).isEqualTo(FavoriteSyncOutcome.Synced)
        assertThat(a.state.isDirty()).isFalse()
        assertThat(a.state.lastError()).isNull()
    }

    @Test
    fun `nothing is recorded while sync is off and the document holds no provider secrets`() = runTest {
        val a = tv(1, listOf(1))
        a.addLive(1)
        assertThat(a.state.replica()).isEqualTo(FavoriteSyncDocument.EMPTY)

        a.pair(); a.engine.sync()
        val plaintext = String(FavoriteSyncCrypto.decrypt(a.keys, server.body!!))
        assertThat(plaintext).contains("L:1")
        assertThat(plaintext).doesNotContain("provider.example")
        assertThat(plaintext).doesNotContain("user")
    }

    // ---- harness ---------------------------------------------------------------------------

    private suspend fun tv(providerId: Long, streams: List<Long>): Tv {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), StreamVaultDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val tv = Tv(database, providerId, server)
        database.providerDao().insert(ProviderEntity(id = providerId, name = "P", type = ProviderType.XTREAM_CODES, serverUrl = "x"))
        tv.insertChannels(streams)
        tvs += tv
        return tv
    }

    private suspend fun Tv.pair(
        mode: FavoriteSyncJoinMode = FavoriteSyncJoinMode.MERGE,
        passphrase: String = PASSPHRASE
    ) {
        secrets.config = FavoriteSyncConfig(true, "http://sync.test", "token", FavoriteSyncCrypto.deriveMasterKey(passphrase, 10))
        state.setJoinMode(mode)
    }

    private class Tv(val database: StreamVaultDatabase, val providerId: Long, server: FakeSyncServer) {
        val secrets = InMemorySecretStore()
        val keys: FavoriteSyncKeys get() = FavoriteSyncKeys(secrets.config.masterKey!!)
        val dao = database.favoriteSyncDao()
        val state = FavoriteSyncStateStore(dao)
        private val identity = FavoriteSyncIdentity(database.providerDao(), { IDENTITY }, dao)
        private val runner = RoomDatabaseTransactionRunner(database)
        val recorder = FavoriteSyncRecorder(secrets, state, identity, dao, FavoriteSyncChangeSignal())
        val engine = FavoriteSyncEngine(
            secrets,
            state,
            identity,
            FavoriteSyncApplier(dao, database.favoriteDao(), identity, runner),
            recorder,
            runner,
            { _, _ -> server.transport }
        )
        val repository = FavoriteRepositoryImpl(database.favoriteDao(), database.virtualGroupDao(), runner, recorder)

        suspend fun insertChannels(streams: List<Long>) {
            streams.forEach { stream ->
                database.channelDao().insert(ChannelEntity(streamId = stream, name = "Channel $stream", providerId = providerId))
            }
        }

        suspend fun channelId(stream: Long): Long = database.channelDao().getByStreamId(providerId, stream)!!.id

        suspend fun addLive(stream: Long) {
            repository.addFavorite(providerId, channelId(stream), ContentType.LIVE, null)
        }

        suspend fun removeLive(stream: Long) {
            repository.removeFavorite(providerId, channelId(stream), ContentType.LIVE, null)
        }

        suspend fun globalLiveStreams(): List<Long> =
            database.favoriteDao().getGlobalByType(providerId, ContentType.LIVE.name).first()
                .map { favorite -> database.channelDao().getById(favorite.contentId)!!.streamId }

        suspend fun groupStreams(groupId: Long): List<Long> =
            database.favoriteDao().getByGroup(groupId).first()
                .map { favorite -> database.channelDao().getById(favorite.contentId)!!.streamId }
    }

    private class InMemorySecretStore : FavoriteSyncSecretStore {
        var config = FavoriteSyncConfig(false, "", "", null)
        override val isEncrypted: Boolean = true
        override fun load(): FavoriteSyncConfig = config
        override fun save(config: FavoriteSyncConfig) {
            this.config = config
        }
        override fun clear() {
            config = FavoriteSyncConfig(false, "", "", null)
        }
    }

    /** In-memory implementation of the v1 server contract. */
    private class FakeSyncServer {
        var body: ByteArray? = null
        var etag: String? = null
        var conflicts = 0
        var failure: FavoriteSyncFailure? = null

        /** Runs once before the next store is checked, to simulate another TV writing in between. */
        var beforeNextStore: (suspend () -> Unit)? = null

        val transport = object : FavoriteSyncTransport {
            override suspend fun fetch(ifNoneMatch: String?): FavoriteSyncFetchResult {
                failure?.let { throw FavoriteSyncTransportException(it, "fake failure") }
                val current = body ?: return FavoriteSyncFetchResult.Missing
                val currentEtag = etag!!
                return if (ifNoneMatch == currentEtag) {
                    FavoriteSyncFetchResult.NotModified(currentEtag)
                } else {
                    FavoriteSyncFetchResult.Found(current.copyOf(), currentEtag)
                }
            }

            override suspend fun store(body: ByteArray, ifMatch: String?): FavoriteSyncStoreResult {
                failure?.let { throw FavoriteSyncTransportException(it, "fake failure") }
                beforeNextStore?.let { hook ->
                    beforeNextStore = null
                    hook()
                }
                val accepted =if (ifMatch == null) this@FakeSyncServer.body == null else ifMatch == etag
                if (!accepted) {
                    conflicts++
                    return FavoriteSyncStoreResult.Conflict
                }
                this@FakeSyncServer.body = body.copyOf()
                etag = "\"" + MessageDigest.getInstance("SHA-256").digest(body).toHex() + "\""
                return FavoriteSyncStoreResult.Stored(etag!!)
            }
        }
    }

    private companion object {
        const val PASSPHRASE = "household passphrase"
        const val IDENTITY = "http://provider.example|user|XTREAM_CODES|"
    }
}
