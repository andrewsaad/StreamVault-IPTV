package com.streamvault.data.preferences

/** Encodes "<epochMillis>|<endpoint fingerprint>"; never the XMLTV URL, which embeds credentials. */
internal fun encodeProviderXmltvUnavailable(at: Long, sourceKey: String): String = "$at|$sourceKey"

/** Returns the remembered time only while it belongs to the same provider endpoint. */
internal fun decodeProviderXmltvUnavailable(encoded: String?, sourceKey: String): Long? {
    if (encoded.isNullOrBlank() || !encoded.contains('|')) return null
    val at = encoded.substringBefore('|').toLongOrNull()?.takeIf { it > 0L } ?: return null
    return at.takeIf { encoded.substringAfter('|') == sourceKey }
}
