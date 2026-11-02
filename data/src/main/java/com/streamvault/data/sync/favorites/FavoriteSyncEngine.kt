package com.streamvault.data.sync.favorites

import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.domain.manager.FavoriteSyncError
import com.streamvault.domain.manager.FavoriteSyncJoinMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException

sealed interface FavoriteSyncOutcome {
    data object Synced : FavoriteSyncOutcome
    data object NotConfigured : FavoriteSyncOutcome
    data class Failed(val error: FavoriteSyncError) : FavoriteSyncOutcome
}

fun interface FavoriteSyncTransportFactory {
    /** Null when [serverUrl] is not a usable http(s) URL. */
    fun create(serverUrl: String, token: String): FavoriteSyncTransport?
}

class HttpFavoriteSyncTransportFactory @Inject constructor() : FavoriteSyncTransportFactory {
    override fun create(serverUrl: String, token: String): FavoriteSyncTransport? =
        HttpFavoriteSyncTransport.documentUrl(serverUrl)?.let { HttpFavoriteSyncTransport(it, token) }
}

/**
 * One sync round: conditional fetch, merge, apply, then compare-and-swap upload, retrying the
 * merge when another TV wrote first. A document this TV cannot decrypt is never overwritten.
 */
@Singleton
class FavoriteSyncEngine @Inject constructor(
    private val secretStore: FavoriteSyncSecretStore,
    private val state: FavoriteSyncStateStore,
    private val identity: FavoriteSyncIdentity,
    private val applier: FavoriteSyncApplier,
    private val recorder: FavoriteSyncRecorder,
    private val transactionRunner: DatabaseTransactionRunner,
    private val transportFactory: FavoriteSyncTransportFactory
) {
    private val mutex = Mutex()

    suspend fun sync(): FavoriteSyncOutcome = mutex.withLock {
        val outcome = try {
            syncLocked()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: FavoriteSyncTransportException) {
            FavoriteSyncOutcome.Failed(error.reason.toError())
        } catch (error: FavoriteSyncPairingMismatchException) {
            FavoriteSyncOutcome.Failed(FavoriteSyncError.PAIRING_MISMATCH)
        } catch (error: SerializationException) {
            FavoriteSyncOutcome.Failed(FavoriteSyncError.UNSUPPORTED_DOCUMENT)
        } catch (error: IllegalArgumentException) {
            FavoriteSyncOutcome.Failed(FavoriteSyncError.UNSUPPORTED_DOCUMENT)
        }
        when (outcome) {
            FavoriteSyncOutcome.Synced -> {
                state.setLastSyncAt(state.nowMs())
                state.setLastError(null)
            }
            is FavoriteSyncOutcome.Failed -> state.setLastError(outcome.error)
            FavoriteSyncOutcome.NotConfigured -> Unit
        }
        outcome
    }

    /** Re-applies the replica without network, e.g. after a catalog commit added missing items. */
    suspend fun applyLocal() = mutex.withLock {
        val keys = pairedKeys() ?: return@withLock
        if (state.joinMode() != null) return@withLock
        state.setUnmatchedCount(applier.apply(state.replica(), identity.localProviderKeys(keys)))
    }

    private suspend fun syncLocked(): FavoriteSyncOutcome {
        val config = secretStore.load()
        val keys = pairedKeys(config) ?: return FavoriteSyncOutcome.NotConfigured
        val transport = transportFactory.create(config.serverUrl, config.token)
            ?: return FavoriteSyncOutcome.Failed(FavoriteSyncError.SERVER_UNREACHABLE)
        val providerKeys = identity.localProviderKeys(keys)
        var forceFetch = false
        repeat(MAX_ATTEMPTS) {
            val joinMode = state.joinMode()
            val etag = state.etag()
            if (joinMode != null || forceFetch || !state.isDirty() || etag == null) {
                val conditional = etag.takeIf { joinMode == null && !forceFetch }
                when (val fetched = transport.fetch(conditional)) {
                    is FavoriteSyncFetchResult.NotModified -> {
                        if (!state.isDirty()) return FavoriteSyncOutcome.Synced
                    }
                    FavoriteSyncFetchResult.Missing -> integrate(FavoriteSyncDocument.EMPTY, null, joinMode, providerKeys)
                    is FavoriteSyncFetchResult.Found -> {
                        val remote = decode(FavoriteSyncCrypto.decrypt(keys, fetched.body))
                        integrate(remote, fetched.etag, joinMode, providerKeys)
                    }
                }
                forceFetch = false
            }
            if (!state.isDirty()) return FavoriteSyncOutcome.Synced

            val version = state.localVersion()
            val payload = FavoriteSyncMerger.compact(state.replica(), state.nowMs())
            if (payload.providers.values.any { provider -> provider.lists.values.sumOf { it.members.size } > MAX_ITEMS_PER_PROVIDER }) {
                return FavoriteSyncOutcome.Failed(FavoriteSyncError.TOO_LARGE)
            }
            val body = FavoriteSyncCrypto.encrypt(keys, FavoriteSyncJson.encode(payload).toByteArray(Charsets.UTF_8))
            when (val stored = transport.store(body, state.etag())) {
                is FavoriteSyncStoreResult.Stored -> {
                    val clean = transactionRunner.inTransaction {
                        state.setEtag(stored.etag)
                        state.saveReplica(FavoriteSyncMerger.compact(state.replica(), state.nowMs()))
                        // An edit recorded while uploading keeps the replica dirty for the next pass.
                        (state.localVersion() == version).also { if (it) state.setDirty(false) }
                    }
                    if (clean) return FavoriteSyncOutcome.Synced
                }
                FavoriteSyncStoreResult.Conflict -> forceFetch = true
            }
        }
        return FavoriteSyncOutcome.Failed(FavoriteSyncError.CONFLICT)
    }

    private suspend fun integrate(
        remote: FavoriteSyncDocument,
        remoteEtag: String?,
        joinMode: FavoriteSyncJoinMode?,
        providerKeys: Map<Long, String>
    ) {
        val clock = state.clock()
        FavoriteSyncMerger.maxTimestamp(remote)?.let(clock::observe)
        val authoritative = joinMode == FavoriteSyncJoinMode.USE_SERVER
        val merged = transactionRunner.inTransaction {
            val merged = when (joinMode) {
                null -> FavoriteSyncMerger.merge(state.replica(), remote)
                FavoriteSyncJoinMode.USE_SERVER -> remote
                FavoriteSyncJoinMode.MERGE -> capture(remote, providerKeys, FavoriteSyncCapturePolicy.MERGE)
                FavoriteSyncJoinMode.UPLOAD_THIS_TV -> capture(remote, providerKeys, FavoriteSyncCapturePolicy.UPLOAD)
            }
            state.saveReplica(merged)
            state.setEtag(remoteEtag)
            // The replica was read inside this transaction, so equality means nothing is left to upload.
            state.setDirty(merged != remote)
            state.saveClock()
            merged
        }
        state.setUnmatchedCount(applier.apply(merged, providerKeys, authoritative))
        if (joinMode != null) state.setJoinMode(null)
    }

    /** First join: the server copy plus this TV's favorites under [policy]. */
    private suspend fun capture(
        remote: FavoriteSyncDocument,
        providerKeys: Map<Long, String>,
        policy: FavoriteSyncCapturePolicy
    ): FavoriteSyncDocument {
        var document = remote
        val clock = state.clock()
        providerKeys.forEach { (providerId, wireKey) ->
            val editor = FavoriteSyncProviderEditor(document.providers[wireKey] ?: FavoriteSyncProviderDoc(), clock)
            with(recorder) { editor.captureLocal(providerId, ALL_LIST_TYPES, policy) }
            if (editor.changed) document = document.copy(providers = document.providers + (wireKey to editor.provider))
        }
        return document
    }

    private fun decode(bytes: ByteArray): FavoriteSyncDocument {
        val document = FavoriteSyncJson.decode(bytes.toString(Charsets.UTF_8))
        if (document.schema > FavoriteSyncDocument.SCHEMA) {
            throw SerializationException("Favorites document schema ${document.schema} is newer than this app")
        }
        return document
    }

    private fun pairedKeys(config: FavoriteSyncConfig = secretStore.load()): FavoriteSyncKeys? {
        val masterKey = config.masterKey
        if (!config.enabled || !config.isComplete || masterKey == null) return null
        return FavoriteSyncKeys(masterKey)
    }

    private fun FavoriteSyncFailure.toError(): FavoriteSyncError = when (this) {
        FavoriteSyncFailure.UNREACHABLE -> FavoriteSyncError.SERVER_UNREACHABLE
        FavoriteSyncFailure.UNAUTHORIZED -> FavoriteSyncError.UNAUTHORIZED
        FavoriteSyncFailure.TOO_LARGE -> FavoriteSyncError.TOO_LARGE
        FavoriteSyncFailure.SERVER_ERROR -> FavoriteSyncError.SERVER_ERROR
    }

    companion object {
        const val MAX_ATTEMPTS = 5
        const val MAX_ITEMS_PER_PROVIDER = 5_000
        internal val ALL_LIST_TYPES = setOf(FavoriteSyncItemKeys.LIVE, FavoriteSyncItemKeys.MOVIE, FavoriteSyncItemKeys.SERIES)
    }
}
