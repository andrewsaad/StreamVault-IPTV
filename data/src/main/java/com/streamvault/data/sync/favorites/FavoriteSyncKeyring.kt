package com.streamvault.data.sync.favorites

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns the stored passphrase and the household salt into [FavoriteSyncKeys]. PBKDF2 is costly on
 * a Fire TV stick, so the result is kept in memory and in the secret store: it runs once per
 * passphrase and salt, not once per sync or per recorded edit.
 */
@Singleton
class FavoriteSyncKeyring(
    private val secretStore: FavoriteSyncSecretStore,
    private val iterations: Int
) {
    @Inject
    constructor(secretStore: FavoriteSyncSecretStore) : this(secretStore, FavoriteSyncCrypto.PBKDF2_ITERATIONS)

    private class Entry(val passphrase: String, val keys: FavoriteSyncKeys)

    @Volatile
    private var cached: Entry? = null

    /** Keys for [salt], or null when sync is not paired. May derive; call off the main thread. */
    fun keys(config: FavoriteSyncConfig, salt: ByteArray): FavoriteSyncKeys? {
        val passphrase = config.passphrase?.takeIf { config.isComplete } ?: return null
        cached?.let { entry ->
            if (entry.passphrase == passphrase && entry.keys.salt.contentEquals(salt)) return entry.keys
        }
        val stored = config.derivedKey?.takeIf { config.derivedKeySalt?.contentEquals(salt) == true }
        val masterKey = stored ?: FavoriteSyncCrypto.deriveMasterKey(passphrase, salt, iterations).also { derived ->
            // Only persist when the pairing did not change while deriving.
            val latest = secretStore.load()
            if (latest.passphrase == passphrase) secretStore.save(latest.withDerivedKey(derived, salt))
        }
        return FavoriteSyncKeys(masterKey, salt).also { cached = Entry(passphrase, it) }
    }
}
