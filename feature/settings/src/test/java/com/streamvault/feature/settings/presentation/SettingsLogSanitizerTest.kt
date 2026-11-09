package com.streamvault.feature.settings.presentation

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class SettingsLogSanitizerTest {
    @Test
    fun redactMessage_replacesUrlsWithLogins() {
        val redacted = SettingsLogSanitizer.redactMessage(
            "Failed to load http://host.example:8080/get.php?username=alice&password=s3cret&type=m3u from the server"
        )

        assertThat(redacted).doesNotContain("alice")
        assertThat(redacted).doesNotContain("s3cret")
        assertThat(redacted).doesNotContain("host.example")
        assertThat(redacted).contains("<url>")
        assertThat(redacted).contains("from the server")
    }

    @Test
    fun redactMessage_redactsCredentialQueryValuesWithoutAScheme() {
        val redacted = SettingsLogSanitizer.redactMessage("bad request ?username=alice&password=s3cret")

        assertThat(redacted).doesNotContain("alice")
        assertThat(redacted).doesNotContain("s3cret")
        assertThat(redacted).contains("<redacted>")
    }

    @Test
    fun redactMessage_redactsUrlWithEmbeddedUserInfo() {
        val redacted = SettingsLogSanitizer.redactMessage("GET https://alice:s3cret@epg.example/guide.xml.gz failed")

        assertThat(redacted).doesNotContain("alice")
        assertThat(redacted).doesNotContain("s3cret")
        assertThat(redacted).doesNotContain("epg.example")
    }

    @Test
    fun redactMessage_blankFallsBackToUnknown() {
        assertThat(SettingsLogSanitizer.redactMessage(null)).isEqualTo("unknown")
        assertThat(SettingsLogSanitizer.redactMessage("  ")).isEqualTo("unknown")
    }

    @Test
    fun describe_usesTheClassNameAndNeverTheThrowableMessage() {
        val error = IllegalStateException("http://host.example/get.php?username=alice&password=s3cret")

        val described = SettingsLogSanitizer.describe("Could not load the list", error)

        assertThat(described).isEqualTo("IllegalStateException: Could not load the list")
        assertThat(described).doesNotContain("alice")
    }

    @Test
    fun describe_withoutExceptionReturnsTheRedactedMessage() {
        assertThat(SettingsLogSanitizer.describe("plain failure", null)).isEqualTo("plain failure")
    }

    @Test
    fun productionLogsNeverPassARawFailureMessageOrThrowable() {
        val root = File("src/main/java/com/streamvault/feature/settings")
        assertThat(root.isDirectory).isTrue()
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    val unsafe = line.contains("Log.w(") && (
                        Regex("""\$\{\w+\.message\}""").containsMatchIn(line) ||
                            Regex("""\.exception\)\s*$""").containsMatchIn(line) ||
                            Regex(""",\s*(it|error|e|exception)\)\s*$""").containsMatchIn(line)
                        )
                    if (unsafe) "${file.name}:${index + 1}" else null
                }
            }
            .toList()

        assertThat(offenders).isEmpty()
    }
}
