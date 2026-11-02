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

/** Pairing configuration. Only the derived key is kept; the passphrase itself is never stored. */
class FavoriteSyncConfig(
    val enabled: Boolean,
    val serverUrl: String,
    val token: String,
    val masterKey: ByteArray?
) {
    val isComplete: Boolean get() = serverUrl.isNotBlank() && token.isNotBlank() && masterKey != null

    // Never print the token or key, even from a debugger-generated toString.
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
 * Stores the server URL, bearer token and derived key in EncryptedSharedPreferences, with the same
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
        masterKey = preferences.getString(KEY_MASTER_KEY, null)
            ?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
            ?.takeIf { it.size == FavoriteSyncCrypto.KEY_BYTES }
    )

    override fun save(config: FavoriteSyncConfig) {
        preferences.edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putString(KEY_SERVER_URL, config.serverUrl)
            .putString(KEY_TOKEN, config.token)
            .apply {
                val key = config.masterKey
                if (key == null) remove(KEY_MASTER_KEY) else putString(KEY_MASTER_KEY, Base64.encodeToString(key, Base64.NO_WRAP))
            }
            .commit()
    }

    override fun clear() {
        preferences.edit().clear().commit()
    }

    private companion object {
        const val TAG = "FavoriteSyncSecrets"
        const val FILE_NAME = "favorites_sync_pairing"
        const val KEY_ENABLED = "enabled"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_TOKEN = "token"
        const val KEY_MASTER_KEY = "master_key"
    }
}
