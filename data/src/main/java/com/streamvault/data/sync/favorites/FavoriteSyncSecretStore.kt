package com.streamvault.data.sync.favorites

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pairing configuration. The passphrase is kept (in the same encrypted store as the token) so a
 * TV can derive keys again if the household salt changes; [derivedKey] caches the slow PBKDF2
 * result for [derivedKeySalt] so it runs once per pairing rather than on every app start.
 */
class FavoriteSyncConfig(
    val enabled: Boolean,
    val serverUrl: String,
    val token: String,
    val passphrase: String?,
    val derivedKey: ByteArray? = null,
    val derivedKeySalt: ByteArray? = null
) {
    val isComplete: Boolean get() = serverUrl.isNotBlank() && token.isNotBlank() && !passphrase.isNullOrEmpty()

    fun withEnabled(enabled: Boolean) = FavoriteSyncConfig(enabled, serverUrl, token, passphrase, derivedKey, derivedKeySalt)

    fun withDerivedKey(key: ByteArray, salt: ByteArray) = FavoriteSyncConfig(enabled, serverUrl, token, passphrase, key, salt)

    // Never print the token, passphrase or key, even from a debugger-generated toString.
    override fun toString(): String = "FavoriteSyncConfig(enabled=$enabled, complete=$isComplete)"
}

interface FavoriteSyncSecretStore {
    /** False when the device keystore failed and values sit in plain app-private preferences. */
    val isEncrypted: Boolean

    fun load(): FavoriteSyncConfig

    fun save(config: FavoriteSyncConfig)

    fun clear()
}

/**
 * Stores the server URL, bearer token, passphrase and derived key in EncryptedSharedPreferences, with the same
 * app-private fallback the parental session store uses on devices with broken Keystore HALs.
 */
@Singleton
class EncryptedFavoriteSyncSecretStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) : FavoriteSyncSecretStore {

    @Volatile
    private var encrypted = true

    private val preferences: SharedPreferences by lazy {
        runCatching {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrElse { error ->
            encrypted = false
            Log.w(
                TAG,
                "EncryptedSharedPreferences unavailable on API ${Build.VERSION.SDK_INT}; " +
                    "favorites sync pairing uses the app-private fallback.",
                error
            )
            context.getSharedPreferences("${FILE_NAME}_fallback", Context.MODE_PRIVATE)
        }
    }

    override val isEncrypted: Boolean
        get() {
            preferences
            return encrypted
        }

    override fun load(): FavoriteSyncConfig = FavoriteSyncConfig(
        enabled = preferences.getBoolean(KEY_ENABLED, false),
        serverUrl = preferences.getString(KEY_SERVER_URL, null).orEmpty(),
        token = preferences.getString(KEY_TOKEN, null).orEmpty(),
        passphrase = preferences.getString(KEY_PASSPHRASE, null),
        derivedKey = bytes(KEY_DERIVED_KEY)?.takeIf { it.size == FavoriteSyncCrypto.KEY_BYTES },
        derivedKeySalt = bytes(KEY_DERIVED_SALT)?.takeIf { it.size == FavoriteSyncCrypto.SALT_BYTES }
    )

    override fun save(config: FavoriteSyncConfig) {
        preferences.edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putString(KEY_SERVER_URL, config.serverUrl)
            .putString(KEY_TOKEN, config.token)
            .putString(KEY_PASSPHRASE, config.passphrase)
            .putBytes(KEY_DERIVED_KEY, config.derivedKey)
            .putBytes(KEY_DERIVED_SALT, config.derivedKeySalt)
            // Written by the first version of this feature; never read again.
            .remove(KEY_LEGACY_MASTER_KEY)
            .commit()
    }

    private fun bytes(key: String): ByteArray? =
        preferences.getString(key, null)?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }

    private fun SharedPreferences.Editor.putBytes(key: String, value: ByteArray?): SharedPreferences.Editor =
        if (value == null) remove(key) else putString(key, Base64.encodeToString(value, Base64.NO_WRAP))

    override fun clear() {
        preferences.edit().clear().commit()
    }

    private companion object {
        const val TAG = "FavoriteSyncSecrets"
        const val FILE_NAME = "favorites_sync_pairing"
        const val KEY_ENABLED = "enabled"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_TOKEN = "token"
        const val KEY_PASSPHRASE = "passphrase"
        const val KEY_DERIVED_KEY = "derived_key"
        const val KEY_DERIVED_SALT = "derived_key_salt"
        const val KEY_LEGACY_MASTER_KEY = "master_key"
    }
}
