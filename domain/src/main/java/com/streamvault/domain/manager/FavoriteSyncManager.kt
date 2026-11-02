package com.streamvault.domain.manager

import kotlinx.coroutines.flow.StateFlow

/**
 * Keeps favorites and custom favorite groups in step across the household's TVs through one
 * encrypted document on a LAN sync server. Edits merge per item, so any TV can change favorites.
 *
 * Sync never opens streams and never asks a provider to refresh; it only reads and writes the
 * local favorites tables and the sync server.
 */
interface FavoriteSyncManager {
    val status: StateFlow<FavoriteSyncStatus>

    /**
     * Saves the pairing and turns sync on. A blank [passphrase] keeps the existing key when one
     * is stored. [joinMode] decides how this TV's favorites meet the server copy on first sync.
     */
    suspend fun enable(
        serverUrl: String,
        token: String,
        passphrase: String,
        joinMode: FavoriteSyncJoinMode
    ): FavoriteSyncSetupResult

    /** Turns sync off. Local favorites stay as they are. */
    suspend fun disable()

    /** Requests an immediate pull and push. */
    fun syncNow()

    /** Pulls when the Favorites screen opens and the last check is older than 30 seconds. */
    fun onFavoritesScreenShown()

    /** Foreground/background transitions drive the pull-on-open and the 60 second poll. */
    fun onAppForegroundChanged(foreground: Boolean)
}

enum class FavoriteSyncJoinMode {
    /** Union of this TV and the server copy. */
    MERGE,

    /** Replace this TV's favorites with the server copy. */
    USE_SERVER,

    /** Replace the server copy (and so the other TVs) with this TV's favorites. */
    UPLOAD_THIS_TV
}

enum class FavoriteSyncSetupResult {
    SAVED,
    INVALID_URL,
    MISSING_TOKEN,
    PASSPHRASE_TOO_SHORT
}

enum class FavoriteSyncError {
    SERVER_UNREACHABLE,
    UNAUTHORIZED,
    PAIRING_MISMATCH,
    TOO_LARGE,
    SERVER_ERROR,
    UNSUPPORTED_DOCUMENT,
    CONFLICT
}

data class FavoriteSyncStatus(
    val enabled: Boolean = false,
    val configured: Boolean = false,
    val serverUrl: String = "",
    val running: Boolean = false,
    val lastSyncAtMs: Long? = null,
    val hasPendingChanges: Boolean = false,
    val unmatchedCount: Int = 0,
    val error: FavoriteSyncError? = null,
    /** False when pairing secrets fell back to unencrypted app-private storage. */
    val secretsEncrypted: Boolean = true
)
