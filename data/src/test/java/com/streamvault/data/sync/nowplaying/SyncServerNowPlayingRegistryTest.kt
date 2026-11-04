package com.streamvault.data.sync.nowplaying

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.StreamVaultDatabase
import com.streamvault.data.sync.favorites.FavoriteSyncConfig
import com.streamvault.data.sync.favorites.FavoriteSyncCrypto
import com.streamvault.data.sync.favorites.FavoriteSyncFetchResult
import com.streamvault.data.sync.favorites.FavoriteSyncKeyring
import com.streamvault.data.sync.favorites.FavoriteSyncPairingMismatchException
import com.streamvault.data.sync.favorites.FavoriteSyncSecretStore
import com.streamvault.data.sync.favorites.FavoriteSyncStateStore
import com.streamvault.data.sync.favorites.FavoriteSyncStoreResult
import com.streamvault.data.sync.favorites.FavoriteSyncTransport
import com.streamvault.domain.sync.OtherViewer
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Simulated TVs sharing one in-test server that implements the v1 contract; real dispatchers. */
@RunWith(RobolectricTestRunner::class)
class SyncServerNowPlayingRegistryTest {
    private val server = FakeServer()
    private val tvs = mutableListOf<Tv>()
    private val salt = FavoriteSyncCrypto.newSalt()

    @After
    fun tearDown() {
        tvs.forEach { it.close() }
    }

    @Test
    fun `another TV sees what this TV plays and stop removes it`() = runBlocking<Unit> {
        val kitchen = tv("k", "Kitchen")
        val living = tv("l", "Living Room")

        kitchen.registry.reportPlaying("beIN 1")
        eventually { assertThat(server.stores).isEqualTo(1) }

        assertThat(living.registry.otherViewer()).isEqualTo(OtherViewer("Kitchen", "beIN 1", kitchen.nowMs))
        assertThat(kitchen.registry.otherViewer()).isNull()

        kitchen.registry.reportStopped()
        eventually { assertThat(server.stores).isEqualTo(2) }
        assertThat(living.registry.otherViewer()).isNull()
    }

    @Test
    fun `refreshes are throttled but a channel change is sent at once`() = runBlocking<Unit> {
        val kitchen = tv("k", "Kitchen")

        kitchen.registry.reportPlaying("beIN 1")
        kitchen.registry.reportPlaying("beIN 1")
        kitchen.nowMs += 30_000L
        kitchen.registry.reportPlaying("beIN 1")
        kitchen.registry.reportPlaying("beIN 2")
        eventually { assertThat(server.stores).isEqualTo(2) }
        kitchen.nowMs += SyncServerNowPlayingRegistry.REFRESH_MS
        kitchen.registry.reportPlaying("beIN 2")
        eventually { assertThat(server.stores).isEqualTo(3) }

        delay(200)
        assertThat(server.stores).isEqualTo(3)
    }

    @Test
    fun `a playing TV keeps refreshing on its own until it stops`() = runBlocking<Unit> {
        val kitchen = tv("k", "Kitchen", refreshMs = 100L)
        // An unchanged board is never rewritten, so the clock has to move like a real one.
        val clock = launch(Dispatchers.Default) {
            while (isActive) {
                kitchen.nowMs += 1_000L
                delay(20)
            }
        }

        kitchen.registry.reportPlaying("beIN 1")
        eventually { assertThat(server.stores).isAtLeast(3) }
        kitchen.registry.reportStopped()
        clock.cancel()
        delay(150)
        val afterStop = server.stores

        delay(300)
        assertThat(server.stores).isEqualTo(afterStop)
        assertThat(server.board(kitchen).devices).isEmpty()
    }

    @Test
    fun `a TV that stopped refreshing is no longer reported`() = runBlocking<Unit> {
        val kitchen = tv("k", "Kitchen")
        val living = tv("l", "Living Room")

        kitchen.registry.reportPlaying("beIN 1")
        eventually { assertThat(server.stores).isEqualTo(1) }
        living.nowMs = kitchen.nowMs + NowPlayingBoard.FRESH_MS

        assertThat(living.registry.otherViewer()).isNull()
    }

    @Test
    fun `a lost compare and swap re-reads and keeps the other TV's entry`() = runBlocking<Unit> {
        val kitchen = tv("k", "Kitchen")
        val living = tv("l", "Living Room")
        living.registry.reportPlaying("MBC")
        eventually { assertThat(server.stores).isEqualTo(1) }
        // The Game Room writes between the Kitchen's read and its write.
        val game = tv("g", "Game Room")
        server.beforeNextStore = {
            server.beforeNextStore = null
            game.registry.reportPlaying("beIN 3")
            eventually { assertThat(server.stores).isEqualTo(2) }
        }

        kitchen.registry.reportPlaying("beIN 1")
        eventually { assertThat(server.stores).isEqualTo(3) }

        assertThat(server.conflicts).isEqualTo(1)
        assertThat(server.board(game).devices.keys).containsExactly("l", "g", "k")
    }

    @Test
    fun `the board is encrypted and cannot pass for the favorites document`() = runBlocking<Unit> {
        val kitchen = tv("k", "Kitchen")

        kitchen.registry.reportPlaying("beIN 1")
        eventually { assertThat(server.stores).isEqualTo(1) }

        val body = server.body!!
        assertThat(String(body, Charsets.ISO_8859_1)).doesNotContain("Kitchen")
        val keys = kitchen.keys()
        assertThrows(FavoriteSyncPairingMismatchException::class.java) {
            FavoriteSyncCrypto.decrypt(keys, body)
        }
    }

    @Test
    fun `a board from an older household salt is replaced`() = runBlocking<Unit> {
        tv("x", "Old").let { old ->
            old.state.setSalt(FavoriteSyncCrypto.newSalt())
            old.registry.reportPlaying("beIN 9")
            eventually { assertThat(server.stores).isEqualTo(1) }
            old.close()
        }
        val kitchen = tv("k", "Kitchen")

        kitchen.registry.reportPlaying("beIN 1")
        eventually { assertThat(server.stores).isEqualTo(2) }

        assertThat(server.board(kitchen).devices.keys).containsExactly("k")
    }

    @Test
    fun `nothing is sent while favorites sync is off or has never synced`() = runBlocking<Unit> {
        val off = tv("k", "Kitchen", paired = false)
        off.registry.reportPlaying("beIN 1")
        val unsynced = tv("l", "Living Room", synced = false)
        unsynced.registry.reportPlaying("beIN 1")

        delay(300)
        assertThat(server.stores).isEqualTo(0)
        assertThat(server.fetches).isEqualTo(0)
        assertThat(off.registry.otherViewer()).isNull()
    }

    @Test
    fun `server failures never escape`() = runBlocking<Unit> {
        val kitchen = tv("k", "Kitchen")
        server.crash = true

        kitchen.registry.reportPlaying("beIN 1")
        assertThat(kitchen.registry.otherViewer()).isNull()

        delay(200)
        assertThat(server.stores).isEqualTo(0)
    }

    private suspend fun tv(
        id: String,
        name: String,
        paired: Boolean = true,
        synced: Boolean = true,
        refreshMs: Long = SyncServerNowPlayingRegistry.REFRESH_MS
    ): Tv =
        Tv(id, name, server, refreshMs).also { tv ->
            if (paired) tv.secrets.config = FavoriteSyncConfig(true, "http://sync.test", "token", PASSPHRASE)
            if (synced) tv.state.setSalt(salt)
            tvs += tv
        }

    private suspend fun eventually(check: () -> Unit) {
        withTimeout(5_000L) {
            while (true) {
                try {
                    check()
                    return@withTimeout
                } catch (notYet: AssertionError) {
                    delay(10)
                }
            }
        }
    }

    private class Tv(id: String, name: String, server: FakeServer, refreshMs: Long) {
        private val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), StreamVaultDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val secrets = InMemorySecretStore()
        val state = FavoriteSyncStateStore(database.favoriteSyncDao())
        // Few PBKDF2 rounds keep the tests fast; production uses FavoriteSyncCrypto.PBKDF2_ITERATIONS.
        private val keyring = FavoriteSyncKeyring(secrets, 10)

        @Volatile var nowMs = 1_700_000_000_000L

        val registry = SyncServerNowPlayingRegistry(
            secrets,
            state,
            keyring,
            { _, _ -> server.transport },
            object : NowPlayingDevice {
                override val id = id
                override val name = name
            },
            { nowMs },
            scope,
            refreshMs
        )

        suspend fun keys() = keyring.keys(secrets.config, state.salt()!!)!!

        fun close() {
            scope.cancel()
            database.close()
        }
    }

    private class InMemorySecretStore : FavoriteSyncSecretStore {
        @Volatile var config = FavoriteSyncConfig(false, "", "", null)
        override val isEncrypted: Boolean = true
        override fun load(): FavoriteSyncConfig = config
        override fun save(config: FavoriteSyncConfig) {
            this.config = config
        }
        override fun clear() {
            config = FavoriteSyncConfig(false, "", "", null)
        }
    }

    private class FakeServer {
        @Volatile var body: ByteArray? = null
        @Volatile var etag: String? = null
        @Volatile var stores = 0
        @Volatile var fetches = 0
        @Volatile var conflicts = 0
        @Volatile var crash = false

        /** Runs before the next store is checked, to simulate another TV writing in between. */
        @Volatile var beforeNextStore: (suspend () -> Unit)? = null

        suspend fun board(tv: Tv): NowPlayingDocument = NowPlayingBoard.decode(
            FavoriteSyncCrypto.decrypt(tv.keys(), body!!, "streamvault-now-playing-v1".toByteArray()).toString(Charsets.UTF_8)
        )

        val transport = object : FavoriteSyncTransport {
            override suspend fun fetch(ifNoneMatch: String?): FavoriteSyncFetchResult {
                if (crash) throw IllegalStateException("unexpected")
                fetches++
                val current = body ?: return FavoriteSyncFetchResult.Missing
                return FavoriteSyncFetchResult.Found(current.copyOf(), etag!!)
            }

            override suspend fun store(body: ByteArray, ifMatch: String?): FavoriteSyncStoreResult {
                if (crash) throw IllegalStateException("unexpected")
                beforeNextStore?.invoke()
                synchronized(this) {
                    val accepted = if (ifMatch == null) this@FakeServer.body == null else ifMatch == etag
                    if (!accepted) {
                        conflicts++
                        return FavoriteSyncStoreResult.Conflict
                    }
                    this@FakeServer.body = body.copyOf()
                    etag = "\"" + MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) } + "\""
                    stores++
                    return FavoriteSyncStoreResult.Stored(etag!!)
                }
            }
        }
    }

    private companion object {
        const val PASSPHRASE = "household passphrase"
    }
}
