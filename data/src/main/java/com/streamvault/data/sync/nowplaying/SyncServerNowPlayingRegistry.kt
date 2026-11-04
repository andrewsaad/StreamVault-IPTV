package com.streamvault.data.sync.nowplaying

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.streamvault.data.sync.favorites.FavoriteSyncCrypto
import com.streamvault.data.sync.favorites.FavoriteSyncFetchResult
import com.streamvault.data.sync.favorites.FavoriteSyncKeyring
import com.streamvault.data.sync.favorites.FavoriteSyncKeys
import com.streamvault.data.sync.favorites.FavoriteSyncSecretStore
import com.streamvault.data.sync.favorites.FavoriteSyncStateStore
import com.streamvault.data.sync.favorites.FavoriteSyncStoreResult
import com.streamvault.data.sync.favorites.FavoriteSyncTransport
import com.streamvault.data.sync.favorites.HttpFavoriteSyncTransport
import com.streamvault.domain.sync.NowPlayingRegistry
import com.streamvault.domain.sync.OtherViewer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Builds the transport for the now-playing document, or null when the URL is not http(s). */
fun interface NowPlayingTransportFactory {
    fun create(serverUrl: String, token: String): FavoriteSyncTransport?
}

class HttpNowPlayingTransportFactory @Inject constructor() : NowPlayingTransportFactory {
    override fun create(serverUrl: String, token: String): FavoriteSyncTransport? =
        HttpFavoriteSyncTransport.documentUrl(serverUrl, SyncServerNowPlayingRegistry.DOCUMENT_ID)
            ?.let { HttpFavoriteSyncTransport(it, token) }
}

/** This TV's stable board key and the name other TVs show for it. */
interface NowPlayingDevice {
    val id: String
    val name: String
}

class AndroidNowPlayingDevice @Inject constructor(
    @param:ApplicationContext private val context: Context
) : NowPlayingDevice {
    // A random id made once per install: never derived from hardware identifiers.
    override val id: String by lazy {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_ID, null) ?: UUID.randomUUID().toString().replace("-", "").take(16).also {
            prefs.edit().putString(KEY_ID, it).apply()
        }
    }

    // The name set in the TV's settings ("Kitchen"), as on the Android TV home screen.
    override val name: String
        get() = Settings.Global.getString(context.contentResolver, DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL

    private companion object {
        const val DEVICE_NAME = "device_name"
        const val PREFS = "now_playing"
        const val KEY_ID = "device_id"
    }
}

/**
 * [NowPlayingRegistry] over the home sync server, reusing the favorites pairing: same server,
 * token and household keys, its own document. Writes go through one queue so a stop can never
 * overtake the start before it, and a lost compare-and-swap re-reads and retries. Nothing here
 * may slow or fail playback, so every error is swallowed.
 */
@Singleton
class SyncServerNowPlayingRegistry internal constructor(
    private val secretStore: FavoriteSyncSecretStore,
    private val state: FavoriteSyncStateStore,
    private val keyring: FavoriteSyncKeyring,
    private val transportFactory: NowPlayingTransportFactory,
    private val device: NowPlayingDevice,
    private val nowMs: () -> Long,
    private val scope: CoroutineScope,
    private val refreshMs: Long = REFRESH_MS
) : NowPlayingRegistry {

    @Inject
    constructor(
        secretStore: FavoriteSyncSecretStore,
        state: FavoriteSyncStateStore,
        keyring: FavoriteSyncKeyring,
        transportFactory: NowPlayingTransportFactory,
        device: NowPlayingDevice
    ) : this(
        secretStore, state, keyring, transportFactory, device, System::currentTimeMillis,
        CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
            Log.w(TAG, "Now-playing task failed: ${error.javaClass.simpleName}")
        })
    )

    private class Session(val transport: FavoriteSyncTransport, val keys: FavoriteSyncKeys)

    private val writes = Channel<(NowPlayingDocument, Long) -> NowPlayingDocument>(Channel.UNLIMITED)

    @Volatile private var reportedChannel: String? = null
    @Volatile private var reportedAtMs = 0L
    private var refreshJob: Job? = null

    init {
        scope.launch {
            for (change in writes) {
                try {
                    write(change)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w(TAG, "Now-playing update failed: ${error.javaClass.simpleName}")
                }
            }
        }
    }

    override fun reportPlaying(channelName: String) {
        val now = nowMs()
        if (channelName == reportedChannel && now - reportedAtMs < REFRESH_MS) return
        reportedChannel = channelName
        reportedAtMs = now
        enqueueUpsert(channelName)
        // Keep the entry fresh for as long as this TV plays, even if nobody calls again.
        synchronized(this) {
            refreshJob?.cancel()
            refreshJob = scope.launch {
                while (true) {
                    delay(refreshMs)
                    val channel = reportedChannel ?: break
                    reportedAtMs = nowMs()
                    enqueueUpsert(channel)
                }
            }
        }
    }

    private fun enqueueUpsert(channelName: String) {
        val id = device.id
        val name = device.name
        writes.trySend { document, at -> NowPlayingBoard.upsert(document, id, name, channelName, at) }
    }

    override fun reportStopped() {
        if (reportedChannel == null) return
        reportedChannel = null
        reportedAtMs = 0L
        synchronized(this) {
            refreshJob?.cancel()
            refreshJob = null
        }
        val id = device.id
        writes.trySend { document, at -> NowPlayingBoard.remove(document, id, at) }
    }

    override suspend fun otherViewer(): OtherViewer? = try {
        withTimeoutOrNull(LOOKUP_TIMEOUT_MS) {
            val session = session() ?: return@withTimeoutOrNull null
            val (document, _) = read(session) ?: return@withTimeoutOrNull null
            NowPlayingBoard.otherViewer(document, device.id, nowMs())
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.w(TAG, "Now-playing lookup failed: ${error.javaClass.simpleName}")
        null
    }

    private suspend fun write(change: (NowPlayingDocument, Long) -> NowPlayingDocument) {
        val session = session() ?: return
        repeat(MAX_ATTEMPTS) {
            val (current, etag) = read(session) ?: return
            val next = change(current, nowMs())
            if (etag != null && next == current) return
            val body = FavoriteSyncCrypto.encrypt(session.keys, NowPlayingBoard.encode(next).toByteArray(Charsets.UTF_8), AAD)
            when (session.transport.store(body, etag)) {
                is FavoriteSyncStoreResult.Stored -> return
                FavoriteSyncStoreResult.Conflict -> Unit // Another TV wrote first: read again and redo the change.
            }
        }
    }

    /** Current board and its ETag (null ETag: no board yet), or null when it cannot be read. */
    private suspend fun read(session: Session): Pair<NowPlayingDocument, String?>? =
        when (val fetched = session.transport.fetch(ifNoneMatch = null)) {
            FavoriteSyncFetchResult.Missing -> NowPlayingDocument.EMPTY to null
            is FavoriteSyncFetchResult.NotModified -> null
            is FavoriteSyncFetchResult.Found -> {
                // A board from before the household salt changed is stale by definition: start over on it.
                if (!FavoriteSyncCrypto.saltOf(fetched.body).contentEquals(session.keys.salt)) {
                    NowPlayingDocument.EMPTY to fetched.etag
                } else {
                    val document = NowPlayingBoard.decode(FavoriteSyncCrypto.decrypt(session.keys, fetched.body, AAD).toString(Charsets.UTF_8))
                    if (document.schema > NowPlayingDocument.SCHEMA) null else document to fetched.etag
                }
            }
        }

    /** Null until favorites sync is paired and has synced once (the household salt comes from that). */
    private suspend fun session(): Session? {
        val config = secretStore.load().takeIf { it.enabled && it.isComplete } ?: return null
        val salt = state.salt() ?: return null
        val keys = withContext(Dispatchers.Default) { keyring.keys(config, salt) } ?: return null
        val transport = transportFactory.create(config.serverUrl, config.token) ?: return null
        return Session(transport, keys)
    }

    companion object {
        const val DOCUMENT_ID = "streamvault-now-playing"
        const val REFRESH_MS = 2 * 60_000L
        private const val LOOKUP_TIMEOUT_MS = 5_000L
        private const val MAX_ATTEMPTS = 4
        private const val TAG = "NowPlaying"
        private val AAD = "streamvault-now-playing-v1".toByteArray(Charsets.UTF_8)
    }
}
