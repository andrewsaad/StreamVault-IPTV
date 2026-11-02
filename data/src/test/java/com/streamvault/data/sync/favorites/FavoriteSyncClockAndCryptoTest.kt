package com.streamvault.data.sync.favorites

import com.google.common.truth.Truth.assertThat
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import org.junit.Assert.assertThrows
import org.junit.Test

class FavoriteSyncClockAndCryptoTest {

    @Test
    fun `clock stays monotonic when the wall clock goes backwards`() {
        var wall = 1_000L
        val clock = FavoriteSyncClock("dev", { wall })
        val first = clock.now()
        wall = 500L
        val second = clock.now()

        assertThat(second).isGreaterThan(first)
    }

    @Test
    fun `edit after seeing a skewed remote stamp sorts after it`() {
        // The other TV runs an hour ahead; this TV's remove must still win over that TV's add.
        val remoteAdd = FavoriteSyncHlc(10_000L + 3_600_000L, 0, "fast-tv")
        val clock = FavoriteSyncClock("slow-tv", { 10_000L })
        clock.observe(remoteAdd)

        assertThat(clock.now()).isGreaterThan(remoteAdd)
    }

    @Test
    fun `timestamp encoding round trips`() {
        val stamp = FavoriteSyncHlc(123L, 4, "5f1c-uuid")
        assertThat(FavoriteSyncHlc.decode(stamp.encode())).isEqualTo(stamp)
    }

    @Test
    fun `pbkdf2 matches the platform implementation`() {
        val expected = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec("correct horse".toCharArray(), "salt-value".toByteArray(), 1_000, 256))
            .encoded

        val actual = FavoriteSyncCrypto.pbkdf2HmacSha256("correct horse".toByteArray(), "salt-value".toByteArray(), 1_000, 32)

        assertThat(actual).isEqualTo(expected)
    }

    @Test
    fun `same passphrase and salt give the same keys on every TV`() {
        val salt = FavoriteSyncCrypto.newSalt()
        val a = keys("family passphrase", salt)
        val b = keys("family passphrase", salt)
        val identity = "http://provider.example|user|XTREAM_CODES|"

        assertThat(a.providerWireKey(identity)).isEqualTo(b.providerWireKey(identity))
        assertThat(a.providerWireKey(identity)).doesNotContain("provider")
        assertThat(String(FavoriteSyncCrypto.decrypt(b, FavoriteSyncCrypto.encrypt(a, "hello".toByteArray())))).isEqualTo("hello")
    }

    @Test
    fun `each household gets its own salt and the blob header carries it`() {
        val first = FavoriteSyncCrypto.newSalt()
        val second = FavoriteSyncCrypto.newSalt()
        val identity = "http://provider.example|user|XTREAM_CODES|"

        assertThat(first).isNotEqualTo(second)
        assertThat(keys("family passphrase", first).providerWireKey(identity))
            .isNotEqualTo(keys("family passphrase", second).providerWireKey(identity))
        val blob = FavoriteSyncCrypto.encrypt(keys("family passphrase", first), "hello".toByteArray())
        assertThat(FavoriteSyncCrypto.saltOf(blob)).isEqualTo(first)
    }

    @Test
    fun `a tampered salt in the header fails like a wrong passphrase`() {
        val salt = FavoriteSyncCrypto.newSalt()
        val keys = keys("family passphrase", salt)
        val blob = FavoriteSyncCrypto.encrypt(keys, "hello".toByteArray())
        blob[4] = (blob[4].toInt() xor 1).toByte()

        assertThrows(FavoriteSyncPairingMismatchException::class.java) {
            FavoriteSyncCrypto.decrypt(keys("family passphrase", FavoriteSyncCrypto.saltOf(blob)), blob)
        }
    }

    @Test
    fun `ciphertext hides the document and a wrong passphrase is a pairing mismatch`() {
        val salt = FavoriteSyncCrypto.newSalt()
        val keys = keys("right passphrase", salt)
        val other = keys("wrong passphrase", salt)
        val blob = FavoriteSyncCrypto.encrypt(keys, "{\"providers\":{\"secret-name\":{}}}".toByteArray())

        assertThat(String(blob, Charsets.ISO_8859_1)).doesNotContain("secret-name")
        assertThrows(FavoriteSyncPairingMismatchException::class.java) { FavoriteSyncCrypto.decrypt(other, blob) }
        assertThrows(FavoriteSyncPairingMismatchException::class.java) { FavoriteSyncCrypto.saltOf("junk".toByteArray()) }
    }

    @Test
    fun `production key derivation uses at least 310k iterations`() {
        assertThat(FavoriteSyncCrypto.PBKDF2_ITERATIONS).isAtLeast(310_000)
    }

    @Test
    fun `keyring derives once and reuses the stored key after a restart`() {
        val store = MemorySecretStore(FavoriteSyncConfig(true, "http://sync.test", "token", "family passphrase"))
        val salt = FavoriteSyncCrypto.newSalt()
        val identity = "http://provider.example|user|XTREAM_CODES|"

        val first = FavoriteSyncKeyring(store, 10).keys(store.load(), salt)!!
        assertThat(store.load().derivedKeySalt).isEqualTo(salt)
        // A different iteration count would give a different key if this keyring derived again.
        val afterRestart = FavoriteSyncKeyring(store, 1).keys(store.load(), salt)!!

        assertThat(afterRestart.providerWireKey(identity)).isEqualTo(first.providerWireKey(identity))
    }

    @Test
    fun `keyring derives again for a new passphrase`() {
        val store = MemorySecretStore(FavoriteSyncConfig(true, "http://sync.test", "token", "family passphrase"))
        val salt = FavoriteSyncCrypto.newSalt()
        val keyring = FavoriteSyncKeyring(store, 10)
        val identity = "http://provider.example|user|XTREAM_CODES|"
        val first = keyring.keys(store.load(), salt)!!

        val changed = FavoriteSyncConfig(true, "http://sync.test", "token", "another passphrase")
        store.save(changed)

        assertThat(keyring.keys(changed, salt)!!.providerWireKey(identity)).isNotEqualTo(first.providerWireKey(identity))
    }

    private fun keys(passphrase: String, salt: ByteArray) =
        FavoriteSyncKeys(FavoriteSyncCrypto.deriveMasterKey(passphrase, salt, iterations = 10), salt)

    private class MemorySecretStore(private var config: FavoriteSyncConfig) : FavoriteSyncSecretStore {
        override val isEncrypted: Boolean = true
        override fun load(): FavoriteSyncConfig = config
        override fun save(config: FavoriteSyncConfig) {
            this.config = config
        }
        override fun clear() = Unit
    }
}
