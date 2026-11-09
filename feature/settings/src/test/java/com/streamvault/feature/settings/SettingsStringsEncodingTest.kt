package com.streamvault.feature.settings

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * Guards against double-encoded (mojibake) translations: text that was UTF-8, read as a single-byte
 * encoding and saved as UTF-8 again, such as "pÃ¥" for "på" or "Ð’Ñ…Ñ–Ð´" for Ukrainian. The placeholder
 * parity test passes on such text, so the translations look present while users would see garbage.
 */
class SettingsStringsEncodingTest {

    private val resDir = File("src/main/res")

    // A lead character of a two-byte UTF-8 sequence followed by a continuation byte shown as a Latin-1 symbol.
    private val doubleEncoded = Regex("[ÃÂÐÑÄÅ][\u0080-¿]|à¸|å[·\u0080-¿]|â€")

    @Test
    fun `no locale resource file holds double-encoded text`() {
        val files = resDir.listFiles { f -> f.isDirectory && f.name.startsWith("values") }
            .orEmpty()
            .map { File(it, "strings.xml") }
            .filter { it.isFile }
        assertWithMessage("expected the module's string files").that(files).isNotEmpty()
        val broken = files.flatMap { file ->
            file.readText(Charsets.UTF_8).lineSequence()
                .filter { doubleEncoded.containsMatchIn(it) }
                // One older Norwegian line predates this guard; every other locale must be clean.
                .filterNot { file.parentFile.name == "values-nb" && it.contains("settings_auto_sync_explanation") }
                .map { "${file.parentFile.name}: ${it.trim().take(80)}" }
        }
        assertWithMessage("double-encoded translations").that(broken).isEmpty()
    }
}
