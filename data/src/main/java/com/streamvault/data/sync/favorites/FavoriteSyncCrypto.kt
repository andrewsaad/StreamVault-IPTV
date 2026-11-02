package com.streamvault.data.sync.favorites

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The server copy could not be decrypted: another passphrase, or a damaged blob. */
class FavoriteSyncPairingMismatchException(cause: Throwable? = null) :
    GeneralSecurityException("Favorites sync document could not be decrypted", cause)

/**
 * Keys derived once from the pairing passphrase. The same passphrase gives every TV the same
 * keys, so no key exchange is needed and the server only ever sees ciphertext.
 */
class FavoriteSyncKeys(masterKey: ByteArray) {
    init {
        require(masterKey.size == FavoriteSyncCrypto.KEY_BYTES) { "Invalid favorites sync key" }
    }

    internal val encryptionKey: ByteArray = FavoriteSyncCrypto.hmac(masterKey, "streamvault-favorites-enc-v1")
    private val providerKey: ByteArray = FavoriteSyncCrypto.hmac(masterKey, "streamvault-favorites-provider-v1")

    /** Wire key for a provider: an HMAC of its identity, so the document never names the server. */
    fun providerWireKey(stableIdentityKey: String): String =
        FavoriteSyncCrypto.hmac(providerKey, stableIdentityKey).toHex().take(32)
}

/**
 * Wire format: "SVF1" magic, 12-byte IV, then AES-256-GCM over the gzipped JSON document.
 * PBKDF2-HMAC-SHA256 is implemented over [Mac] because the platform SecretKeyFactory only offers
 * it from API 26 and the app still supports API 25 devices.
 */
object FavoriteSyncCrypto {
    const val KEY_BYTES = 32
    const val PBKDF2_ITERATIONS = 120_000
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private val MAGIC = byteArrayOf('S'.code.toByte(), 'V'.code.toByte(), 'F'.code.toByte(), '1'.code.toByte())
    private val AAD = "streamvault-favorites-doc-v1".toByteArray(Charsets.UTF_8)
    private val SALT = "streamvault-favorites-sync-salt-v1".toByteArray(Charsets.UTF_8)
    private val random = SecureRandom()

    fun deriveMasterKey(passphrase: String, iterations: Int = PBKDF2_ITERATIONS): ByteArray =
        pbkdf2HmacSha256(passphrase.toByteArray(Charsets.UTF_8), SALT, iterations, KEY_BYTES)

    fun encrypt(keys: FavoriteSyncKeys, plaintext: ByteArray): ByteArray {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.encryptionKey, "AES"), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(AAD)
        return MAGIC + iv + cipher.doFinal(gzip(plaintext))
    }

    fun decrypt(keys: FavoriteSyncKeys, blob: ByteArray): ByteArray {
        if (blob.size < MAGIC.size + IV_BYTES + TAG_BITS / 8 || !blob.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw FavoriteSyncPairingMismatchException()
        }
        val iv = blob.copyOfRange(MAGIC.size, MAGIC.size + IV_BYTES)
        val compressed = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keys.encryptionKey, "AES"), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(AAD)
            cipher.doFinal(blob, MAGIC.size + IV_BYTES, blob.size - MAGIC.size - IV_BYTES)
        } catch (error: GeneralSecurityException) {
            throw FavoriteSyncPairingMismatchException(error)
        }
        return gunzip(compressed)
    }

    internal fun hmac(key: ByteArray, message: String): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(message.toByteArray(Charsets.UTF_8))
        }

    internal fun pbkdf2HmacSha256(password: ByteArray, salt: ByteArray, iterations: Int, keyBytes: Int): ByteArray {
        require(iterations > 0 && keyBytes > 0)
        val mac = Mac.getInstance("HmacSHA256")
        // An empty HMAC key is valid per RFC 2104 but rejected by SecretKeySpec.
        mac.init(SecretKeySpec(if (password.isEmpty()) ByteArray(1) else password, "HmacSHA256"))
        val hashBytes = mac.macLength
        val output = ByteArray(keyBytes)
        var block = 1
        var offset = 0
        while (offset < keyBytes) {
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            repeat(iterations - 1) {
                u = mac.doFinal(u)
                for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
            }
            val count = minOf(hashBytes, keyBytes - offset)
            System.arraycopy(t, 0, output, offset, count)
            offset += count
            block += 1
        }
        return output
    }

    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().use { out ->
        GZIPOutputStream(out).use { it.write(bytes) }
        out.toByteArray()
    }

    private fun gunzip(bytes: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
}

private const val HEX_DIGITS = "0123456789abcdef"

// Locale-free on purpose: String.format can emit non-ASCII digits in some app locales.
internal fun ByteArray.toHex(): String = buildString(size * 2) {
    for (byte in this@toHex) {
        val value = byte.toInt() and 0xff
        append(HEX_DIGITS[value ushr 4])
        append(HEX_DIGITS[value and 0x0f])
    }
}
