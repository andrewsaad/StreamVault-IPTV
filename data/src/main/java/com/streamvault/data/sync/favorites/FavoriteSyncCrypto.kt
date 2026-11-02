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
 * Keys derived from the pairing passphrase and the household salt. The same passphrase and salt
 * give every TV the same keys, so no key exchange is needed and the server only sees ciphertext.
 */
class FavoriteSyncKeys(masterKey: ByteArray, salt: ByteArray) {
    init {
        require(masterKey.size == FavoriteSyncCrypto.KEY_BYTES) { "Invalid favorites sync key" }
        require(salt.size == FavoriteSyncCrypto.SALT_BYTES) { "Invalid favorites sync salt" }
    }

    /** Household salt; written in the clear into every blob header. */
    val salt: ByteArray = salt.copyOf()
    internal val encryptionKey: ByteArray = FavoriteSyncCrypto.hmac(masterKey, "streamvault-favorites-enc-v1")
    private val providerKey: ByteArray = FavoriteSyncCrypto.hmac(masterKey, "streamvault-favorites-provider-v1")

    /** Wire key for a provider: an HMAC of its identity, so the document never names the server. */
    fun providerWireKey(stableIdentityKey: String): String =
        FavoriteSyncCrypto.hmac(providerKey, stableIdentityKey).toHex().take(32)
}

/**
 * Wire format: "SVF2" magic, 16-byte household salt, 12-byte IV, then AES-256-GCM over the
 * gzipped JSON document. The salt is random per household and not secret; the first TV to upload
 * picks it and every other TV reads it from the header before deriving keys.
 *
 * PBKDF2-HMAC-SHA256 is implemented over [Mac] because the platform SecretKeyFactory only offers
 * it from API 26 and the app still supports API 25 devices. Derivation is slow on purpose, so
 * [FavoriteSyncKeyring] runs it once per passphrase and salt and caches the result.
 */
object FavoriteSyncCrypto {
    const val KEY_BYTES = 32
    const val SALT_BYTES = 16
    const val PBKDF2_ITERATIONS = 310_000
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private val MAGIC = byteArrayOf('S'.code.toByte(), 'V'.code.toByte(), 'F'.code.toByte(), '2'.code.toByte())
    private val HEADER_BYTES = MAGIC.size + SALT_BYTES + IV_BYTES
    private val AAD = "streamvault-favorites-doc-v2".toByteArray(Charsets.UTF_8)
    private val random = SecureRandom()

    fun newSalt(): ByteArray = ByteArray(SALT_BYTES).also(random::nextBytes)

    fun deriveMasterKey(passphrase: String, salt: ByteArray, iterations: Int = PBKDF2_ITERATIONS): ByteArray =
        pbkdf2HmacSha256(passphrase.toByteArray(Charsets.UTF_8), salt, iterations, KEY_BYTES)

    /** Household salt from a blob header; a blob without a valid header is not ours. */
    fun saltOf(blob: ByteArray): ByteArray {
        if (blob.size < HEADER_BYTES + TAG_BITS / 8 || !blob.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw FavoriteSyncPairingMismatchException()
        }
        return blob.copyOfRange(MAGIC.size, MAGIC.size + SALT_BYTES)
    }

    fun encrypt(keys: FavoriteSyncKeys, plaintext: ByteArray): ByteArray {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.encryptionKey, "AES"), GCMParameterSpec(TAG_BITS, iv))
        // The header is authenticated too, so a swapped salt fails like a wrong passphrase.
        val header = MAGIC + keys.salt + iv
        cipher.updateAAD(AAD)
        cipher.updateAAD(header)
        return header + cipher.doFinal(gzip(plaintext))
    }

    fun decrypt(keys: FavoriteSyncKeys, blob: ByteArray): ByteArray {
        if (!saltOf(blob).contentEquals(keys.salt)) throw FavoriteSyncPairingMismatchException()
        val iv = blob.copyOfRange(MAGIC.size + SALT_BYTES, HEADER_BYTES)
        val compressed = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keys.encryptionKey, "AES"), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(AAD)
            cipher.updateAAD(blob, 0, HEADER_BYTES)
            cipher.doFinal(blob, HEADER_BYTES, blob.size - HEADER_BYTES)
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
