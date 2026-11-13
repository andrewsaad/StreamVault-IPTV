package com.streamvault.app.update

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UpdateApkSignatureCheckTest {

    private val releaseKey = UpdateApkTrust.sha256Hex("release-key".toByteArray())
    private val rotatedKey = UpdateApkTrust.sha256Hex("rotated-key".toByteArray())
    private val attackerKey = UpdateApkTrust.sha256Hex("attacker-key".toByteArray())

    private val installed = ApkSigningIdentity(PACKAGE, setOf(releaseKey))

    @Test
    fun `same package signed with the installed key is trusted`() {
        assertThat(UpdateApkTrust.isTrustedUpdate(installed, ApkSigningIdentity(PACKAGE, setOf(releaseKey))))
            .isTrue()
    }

    @Test
    fun `rotated key whose lineage includes the installed key is trusted`() {
        val archive = ApkSigningIdentity(PACKAGE, setOf(rotatedKey), pastSignerDigests = setOf(releaseKey, rotatedKey))

        assertThat(UpdateApkTrust.isTrustedUpdate(installed, archive)).isTrue()
    }

    @Test
    fun `different signer is refused even when the release digest matched`() {
        assertThat(UpdateApkTrust.isTrustedUpdate(installed, ApkSigningIdentity(PACKAGE, setOf(attackerKey))))
            .isFalse()
    }

    @Test
    fun `rotated key without the installed key in its lineage is refused`() {
        val archive = ApkSigningIdentity(PACKAGE, setOf(rotatedKey), pastSignerDigests = setOf(attackerKey))

        assertThat(UpdateApkTrust.isTrustedUpdate(installed, archive)).isFalse()
    }

    @Test
    fun `different package is refused even with the same signer`() {
        val archive = ApkSigningIdentity("com.example.other", setOf(releaseKey))

        assertThat(UpdateApkTrust.isTrustedUpdate(installed, archive)).isFalse()
    }

    @Test
    fun `every installed signer must be accepted when the app has several`() {
        val multiSigned = ApkSigningIdentity(PACKAGE, setOf(releaseKey, rotatedKey))

        assertThat(UpdateApkTrust.isTrustedUpdate(multiSigned, ApkSigningIdentity(PACKAGE, setOf(releaseKey))))
            .isFalse()
        assertThat(
            UpdateApkTrust.isTrustedUpdate(multiSigned, ApkSigningIdentity(PACKAGE, setOf(releaseKey, rotatedKey)))
        ).isTrue()
    }

    @Test
    fun `unreadable or unsigned archives fail closed`() {
        assertThat(UpdateApkTrust.isTrustedUpdate(installed, null)).isFalse()
        assertThat(UpdateApkTrust.isTrustedUpdate(null, ApkSigningIdentity(PACKAGE, setOf(releaseKey)))).isFalse()
        assertThat(UpdateApkTrust.isTrustedUpdate(installed, ApkSigningIdentity(PACKAGE, emptySet()))).isFalse()
        assertThat(
            UpdateApkTrust.isTrustedUpdate(ApkSigningIdentity(PACKAGE, emptySet()), ApkSigningIdentity(PACKAGE, emptySet()))
        ).isFalse()
    }

    @Test
    fun `digest is lowercase sha-256 hex`() {
        assertThat(UpdateApkTrust.sha256Hex("abc".toByteArray()))
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }

    private companion object {
        const val PACKAGE = "com.streamvault.app"
    }
}
