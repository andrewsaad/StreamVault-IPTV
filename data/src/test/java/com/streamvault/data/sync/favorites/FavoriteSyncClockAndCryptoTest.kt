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
    fun `same passphrase gives the same keys on every TV`() {
        val a = FavoriteSyncKeys(FavoriteSyncCrypto.deriveMasterKey("family passphrase", iterations = 10))
        val b = FavoriteSyncKeys(FavoriteSyncCrypto.deriveMasterKey("family passphrase", iterations = 10))
        val identity = "http://provider.example|user|XTREAM_CODES|"

        assertThat(a.providerWireKey(identity)).isEqualTo(b.providerWireKey(identity))
        assertThat(a.providerWireKey(identity)).doesNotContain("provider")
        assertThat(String(FavoriteSyncCrypto.decrypt(b, FavoriteSyncCrypto.encrypt(a, "hello".toByteArray())))).isEqualTo("hello")
    }

    @Test
    fun `ciphertext hides the document and a wrong passphrase is a pairing mismatch`() {
        val keys = FavoriteSyncKeys(FavoriteSyncCrypto.deriveMasterKey("right passphrase", iterations = 10))
        val other = FavoriteSyncKeys(FavoriteSyncCrypto.deriveMasterKey("wrong passphrase", iterations = 10))
        val blob = FavoriteSyncCrypto.encrypt(keys, "{\"providers\":{\"secret-name\":{}}}".toByteArray())

        assertThat(String(blob, Charsets.ISO_8859_1)).doesNotContain("secret-name")
        assertThrows(FavoriteSyncPairingMismatchException::class.java) { FavoriteSyncCrypto.decrypt(other, blob) }
        assertThrows(FavoriteSyncPairingMismatchException::class.java) { FavoriteSyncCrypto.decrypt(keys, "junk".toByteArray()) }
    }
}
