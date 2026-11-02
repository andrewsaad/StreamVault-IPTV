package com.streamvault.data.provider

import com.streamvault.domain.model.LegacyProvider
import com.streamvault.domain.model.ProviderType
import java.net.URI
import java.util.Locale

/**
 * Device-independent provider identity shared by portable backups and favorites sync. Two
 * installs that log in to the same account produce the same key even though their local
 * provider row ids differ.
 */
object ProviderIdentityKeys {

    fun stableIdentityKey(
        serverUrl: String,
        username: String,
        providerType: ProviderType?,
        stalkerMacAddress: String?
    ): String = listOf(
        normalizeServerUrl(serverUrl),
        username.trim(),
        providerType?.name.orEmpty(),
        stalkerMacAddress.normalizedIdentity()
    ).joinToString("|")

    /** M3U usernames are not part of the identity, matching portable backup references. */
    fun stableIdentityKey(provider: LegacyProvider): String = stableIdentityKey(
        serverUrl = provider.serverUrl,
        username = provider.username.trim().takeUnless { provider.type == ProviderType.M3U }.orEmpty(),
        providerType = provider.type,
        stalkerMacAddress = provider.stalkerMacAddress.takeIf { it.isNotBlank() }
    )

    /**
     * Provider URLs are identities, not display strings. The setup flow normally stores a
     * canonical value, but backups can cross releases/devices where harmless differences such as
     * a trailing slash, URL scheme/host case, or an explicit default port are present.
     */
    fun normalizeServerUrl(value: String): String {
        val trimmed = value.trim()
        return runCatching {
            val uri = URI(trimmed)
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            val host = uri.host?.lowercase(Locale.ROOT)
            if (scheme.isNullOrBlank() || host.isNullOrBlank()) {
                return@runCatching trimmed.trimEnd('/').lowercase(Locale.ROOT)
            }
            val port = when {
                uri.port < 0 -> ""
                scheme == "http" && uri.port == 80 -> ""
                scheme == "https" && uri.port == 443 -> ""
                else -> ":${uri.port}"
            }
            val path = uri.rawPath.orEmpty().trimEnd('/')
            val query = uri.rawQuery?.let { "?$it" }.orEmpty()
            "$scheme://$host$port$path$query"
        }.getOrElse {
            trimmed.trimEnd('/').lowercase(Locale.ROOT)
        }
    }

    private fun String?.normalizedIdentity(): String = orEmpty().trim().lowercase()
}
