package com.streamvault.app.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.File
import java.security.MessageDigest

/** Package identity and signer certificates (SHA-256, lowercase hex) of an installed app or an APK file. */
internal data class ApkSigningIdentity(
    val packageName: String,
    val signerDigests: Set<String>,
    /** Earlier signers the APK declares through APK Signature Scheme v3 key rotation. */
    val pastSignerDigests: Set<String> = emptySet()
)

/**
 * Decides whether a downloaded update may be handed to the package installer. The release digest only proves the
 * file matches what the release page lists, so the APK must also be this app, signed by the key the installed app
 * is signed with (or a rotated key whose lineage includes it). Anything unreadable is refused.
 */
internal object UpdateApkTrust {
    fun isTrustedUpdate(installed: ApkSigningIdentity?, archive: ApkSigningIdentity?): Boolean {
        if (installed == null || archive == null) return false
        if (installed.packageName.isBlank() || archive.packageName != installed.packageName) return false
        if (installed.signerDigests.isEmpty() || archive.signerDigests.isEmpty()) return false
        val acceptedSigners = archive.signerDigests + archive.pastSignerDigests
        return acceptedSigners.containsAll(installed.signerDigests)
    }

    fun installedIdentity(context: Context): ApkSigningIdentity? = runCatching {
        val packageManager = context.packageManager
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(signingFlags().toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(context.packageName, signingFlags())
        }
        identityOf(packageInfo, includeHistory = false)
    }.getOrNull()

    fun archiveIdentity(context: Context, apkFile: File): ApkSigningIdentity? = runCatching {
        val packageManager = context.packageManager
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageArchiveInfo(
                apkFile.absolutePath,
                PackageManager.PackageInfoFlags.of(signingFlags().toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageArchiveInfo(apkFile.absolutePath, signingFlags())
        }
        packageInfo?.let { identityOf(it, includeHistory = true) }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun signingFlags(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
    } else {
        PackageManager.GET_SIGNATURES
    }

    @Suppress("DEPRECATION")
    private fun identityOf(packageInfo: PackageInfo, includeHistory: Boolean): ApkSigningIdentity? {
        val packageName = packageInfo.packageName ?: return null
        var currentSigners: Array<Signature>? = null
        var pastSigners: Array<Signature>? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = packageInfo.signingInfo
            if (signingInfo != null) {
                currentSigners = signingInfo.apkContentsSigners
                if (includeHistory && !signingInfo.hasMultipleSigners()) {
                    pastSigners = signingInfo.signingCertificateHistory
                }
            }
        }
        val signers: Array<Signature>? =
            if (currentSigners.isNullOrEmpty()) packageInfo.signatures else currentSigners
        return ApkSigningIdentity(
            packageName = packageName,
            signerDigests = signers.orEmpty().mapTo(linkedSetOf<String>()) { sha256Hex(it.toByteArray()) },
            pastSignerDigests = pastSigners.orEmpty().mapTo(linkedSetOf<String>()) { sha256Hex(it.toByteArray()) }
        )
    }

    internal fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
