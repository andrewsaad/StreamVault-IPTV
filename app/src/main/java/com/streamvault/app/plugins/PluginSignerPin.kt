package com.streamvault.app.plugins

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.security.MessageDigest

/** Signing certificates (SHA-256, lowercase hex) of an installed plugin package. */
internal data class PluginSigners(
    val current: Set<String>,
    /** Earlier signers the package declares through APK Signature Scheme v3 key rotation. */
    val past: Set<String> = emptySet()
)

internal enum class PluginSignerDecision {
    /** The package is still signed by the pinned key, or a rotation of it. */
    TRUSTED,

    /** Enabled before signer pinning existed: trust the current signer once and pin it. */
    PIN_CURRENT,

    /** Signed by someone else (or unreadable): the plugin stays off until the user enables it again. */
    UNTRUSTED
}

/**
 * Plugins are bound by package and class name, so a different app installed under the same package name would
 * inherit the user's "enabled" choice. Pinning the signer the user enabled closes that gap.
 */
internal object PluginSignerPin {
    fun decide(pinned: Set<String>, signers: PluginSigners?): PluginSignerDecision = when {
        signers == null || signers.current.isEmpty() -> PluginSignerDecision.UNTRUSTED
        pinned.isEmpty() -> PluginSignerDecision.PIN_CURRENT
        (signers.current + signers.past).containsAll(pinned) -> PluginSignerDecision.TRUSTED
        else -> PluginSignerDecision.UNTRUSTED
    }

    fun encode(digests: Set<String>): String = digests.sorted().joinToString(",")

    fun decode(value: String?): Set<String> =
        value.orEmpty().split(',').map(String::trim).filter(String::isNotEmpty).toSet()

    fun read(context: Context, packageName: String): PluginSigners? = runCatching {
        val packageManager = context.packageManager
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(signingFlags().toLong()))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, signingFlags())
        }
        signersOf(packageInfo)
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun signingFlags(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
    } else {
        PackageManager.GET_SIGNATURES
    }

    @Suppress("DEPRECATION")
    private fun signersOf(packageInfo: PackageInfo): PluginSigners {
        var currentSigners: Array<Signature>? = null
        var pastSigners: Array<Signature>? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = packageInfo.signingInfo
            if (signingInfo != null) {
                currentSigners = signingInfo.apkContentsSigners
                if (!signingInfo.hasMultipleSigners()) {
                    pastSigners = signingInfo.signingCertificateHistory
                }
            }
        }
        val signers: Array<Signature>? =
            if (currentSigners.isNullOrEmpty()) packageInfo.signatures else currentSigners
        return PluginSigners(
            current = signers.orEmpty().mapTo(linkedSetOf<String>()) { sha256Hex(it.toByteArray()) },
            past = pastSigners.orEmpty().mapTo(linkedSetOf<String>()) { sha256Hex(it.toByteArray()) }
        )
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
