package com.streamvault.data.sync.favorites

import android.os.SystemClock
import android.util.Log
import com.streamvault.domain.manager.FavoriteSyncJoinMode
import com.streamvault.domain.manager.FavoriteSyncManager
import com.streamvault.domain.manager.FavoriteSyncSetupResult
import com.streamvault.domain.manager.FavoriteSyncStatus
import com.streamvault.domain.sync.AutomaticSyncActivity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Called after a provider catalog commit so unmatched synced favorites can be retried. */
interface FavoriteSyncCatalogListener {
    suspend fun onCatalogCommitted(providerId: Long) = Unit

    companion object {
        val NONE: FavoriteSyncCatalogListener = object : FavoriteSyncCatalogListener {}
    }
}

/**
 * Decides when to sync: about 3 s after a local edit, on foreground or the Favorites screen when
 * the last check is older than 30 s, every 60 s while the app is in front and nothing plays, and
 * every 6 h from WorkManager. No foreground service and no wakelocks.
 */
@Singleton
class FavoriteSyncCoordinator @Inject constructor(
    private val secretStore: FavoriteSyncSecretStore,
    private val state: FavoriteSyncStateStore,
    private val engine: FavoriteSyncEngine,
    private val signal: FavoriteSyncChangeSignal,
    private val workScheduler: FavoriteSyncWorkScheduler
) : FavoriteSyncManager, FavoriteSyncCatalogListener {

    // Last line of defence: a sync bug must surface as a status error, never as an app crash.
    private val exceptionHandler = CoroutineExceptionHandler { _, error ->
        Log.w(TAG, "Favorites sync task failed: ${error.javaClass.simpleName}")
        mutableStatus.update { it.copy(running = false) }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    private val mutableStatus = MutableStateFlow(FavoriteSyncStatus())
    override val status: StateFlow<FavoriteSyncStatus> = mutableStatus.asStateFlow()

    @Volatile private var lastAttemptAt = 0L
    @Volatile private var foreground = false
    private var pushJob: Job? = null
    private var pollJob: Job? = null

    init {
        scope.launch { signal.events.collect { schedulePush() } }
    }

    /** Process start: publishes status and keeps the periodic request in line with the toggle. */
    fun start() {
        scope.launch {
            if (secretStore.load().let { it.enabled && it.isComplete }) {
                workScheduler.schedulePeriodic()
            } else {
                workScheduler.cancelPeriodic()
            }
            refreshStatus()
        }
    }

    override suspend fun enable(
        serverUrl: String,
        token: String,
        passphrase: String,
        joinMode: FavoriteSyncJoinMode
    ): FavoriteSyncSetupResult {
        val url = serverUrl.trim().trimEnd('/')
        if (HttpFavoriteSyncTransport.documentUrl(url) == null) return FavoriteSyncSetupResult.INVALID_URL
        val trimmedToken = token.trim()
        if (trimmedToken.isEmpty()) return FavoriteSyncSetupResult.MISSING_TOKEN
        val previous = withContext(Dispatchers.IO) { secretStore.load() }
        val effectivePassphrase = when {
            passphrase.isNotEmpty() && passphrase.length < MIN_PASSPHRASE_LENGTH -> return FavoriteSyncSetupResult.PASSPHRASE_TOO_SHORT
            passphrase.isNotEmpty() -> passphrase
            else -> previous.passphrase?.takeIf { it.isNotEmpty() } ?: return FavoriteSyncSetupResult.PASSPHRASE_TOO_SHORT
        }
        val pairingChanged = previous.serverUrl != url || previous.passphrase != effectivePassphrase
        withContext(Dispatchers.IO) {
            // A new server or passphrase means a different document: start from a clean replica.
            if (pairingChanged) state.resetReplica()
            // Turning sync on, or pairing anew, always goes through the first-join choice.
            if (pairingChanged || !previous.enabled) state.setJoinMode(joinMode)
            // A cached key belongs to the old passphrase; keys are derived again on the first sync.
            val config = FavoriteSyncConfig(enabled = true, serverUrl = url, token = trimmedToken, passphrase = effectivePassphrase)
            val keepKey = previous.passphrase == effectivePassphrase
            val derivedKey = previous.derivedKey
            val derivedKeySalt = previous.derivedKeySalt
            secretStore.save(
                if (keepKey && derivedKey != null && derivedKeySalt != null) config.withDerivedKey(derivedKey, derivedKeySalt) else config
            )
            workScheduler.schedulePeriodic()
        }
        refreshStatus()
        syncNow()
        return FavoriteSyncSetupResult.SAVED
    }

    override suspend fun disable() {
        withContext(Dispatchers.IO) {
            val current = secretStore.load()
            secretStore.save(current.withEnabled(false))
            workScheduler.cancelPeriodic()
        }
        pushJob?.cancel()
        refreshStatus()
    }

    override fun syncNow() {
        scope.launch { runSync() }
    }

    override fun onFavoritesScreenShown() = pullIfStale()

    override fun onAppForegroundChanged(foreground: Boolean) {
        this.foreground = foreground
        if (!foreground) {
            pollJob?.cancel()
            pollJob = null
            return
        }
        pullIfStale()
        if (pollJob?.isActive != true) {
            pollJob = scope.launch {
                while (isActive && this@FavoriteSyncCoordinator.foreground) {
                    delay(POLL_INTERVAL_MS)
                    // A conditional GET is tiny, but playback still has priority on the network.
                    if (!AutomaticSyncActivity.playbackActive.value) runSync()
                }
            }
        }
    }

    override suspend fun onCatalogCommitted(providerId: Long) {
        scope.launch {
            engine.applyLocal()
            refreshStatus()
        }
    }

    private fun pullIfStale() {
        if (SystemClock.elapsedRealtime() - lastAttemptAt < PULL_STALE_MS && lastAttemptAt != 0L) return
        syncNow()
    }

    @Synchronized
    private fun schedulePush() {
        pushJob?.cancel()
        pushJob = scope.launch {
            refreshStatus()
            delay(PUSH_DELAY_MS)
            runSync()
        }
    }

    private suspend fun runSync() {
        val config = secretStore.load()
        if (!config.enabled || !config.isComplete) return
        lastAttemptAt = SystemClock.elapsedRealtime()
        mutableStatus.update { it.copy(running = true) }
        try {
            engine.sync()
        } finally {
            refreshStatus()
        }
    }

    private suspend fun refreshStatus() = withContext(Dispatchers.IO) {
        val config = secretStore.load()
        mutableStatus.value = FavoriteSyncStatus(
            enabled = config.enabled,
            configured = config.isComplete,
            serverUrl = config.serverUrl,
            running = false,
            lastSyncAtMs = state.lastSyncAt(),
            hasPendingChanges = state.isDirty() || state.joinMode() != null,
            unmatchedCount = state.unmatchedCount(),
            error = state.lastError(),
            secretsEncrypted = secretStore.isEncrypted
        )
    }

    private companion object {
        const val TAG = "FavoriteSync"
        const val MIN_PASSPHRASE_LENGTH = 8
        const val PUSH_DELAY_MS = 3_000L
        const val PULL_STALE_MS = 30_000L
        const val POLL_INTERVAL_MS = 60_000L
    }
}
