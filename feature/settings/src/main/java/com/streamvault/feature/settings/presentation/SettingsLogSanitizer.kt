package com.streamvault.feature.settings.presentation

/**
 * Builds log-safe failure descriptions. Failure messages can embed the M3U, EPG or backup url the
 * user entered, including its login, so they must never reach logcat unredacted and the raw
 * throwable (whose message and stack trace repeat that text) must not be logged either.
 */
internal object SettingsLogSanitizer {
    private val urlRegex = Regex("""[A-Za-z][A-Za-z0-9+.\-]*://\S+""")
    private val credentialQueryRegex = Regex(
        """([?&;](?:username|user|password|pass|pwd|token|auth|key|apikey)=)[^&#\s]+""",
        RegexOption.IGNORE_CASE
    )

    fun redactMessage(message: String?): String {
        if (message.isNullOrBlank()) return "unknown"
        return credentialQueryRegex
            .replace(message) { match -> match.groupValues[1] + "<redacted>" }
            .let { urlRegex.replace(it, "<url>") }
    }

    /** "ExceptionClass: redacted message", or just the redacted message when there is no exception. */
    fun describe(message: String?, error: Throwable?): String {
        val redacted = redactMessage(message)
        val className = error?.javaClass?.simpleName?.takeIf { it.isNotBlank() } ?: return redacted
        return "$className: $redacted"
    }
}
