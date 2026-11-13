package com.streamvault.app

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * Classes Gson reads back from DataStore, backups or the database. If R8 renames their fields or enum constants,
 * a release build writes JSON that the next release cannot read and the user's settings silently reset.
 */
class ProguardGsonKeepRulesTest {

    private val gsonPersistedClasses = listOf(
        "com.streamvault.domain.settings.PlaybackQualityPreferences",
        "com.streamvault.domain.settings.PlaybackQualityLimit",
        "com.streamvault.domain.settings.PlaybackQualityProfile",
        "com.streamvault.domain.sync.AutomaticSyncSettings",
        "com.streamvault.domain.sync.AutomaticSyncConfiguration",
        "com.streamvault.domain.sync.AutomaticSyncStatus",
        "com.streamvault.domain.sync.AutomaticSyncOutcome",
        "com.streamvault.domain.sync.RefreshSection",
        "com.streamvault.data.provider.ProviderConfigRevisionEnvelope",
        "com.streamvault.data.manager.BackupManagerImpl\$ReplaceScopePayload",
        "com.streamvault.data.manager.PendingBackupRestoreCoordinator\$ReplaceScopePayload",
        // Types nested inside the envelope, covered by the domain model package rule.
        "com.streamvault.domain.model.Provider",
        "com.streamvault.domain.model.ProviderAccountRuntime"
    )

    @Test
    fun `every gson persisted class keeps all of its members`() {
        val keepPatterns = fullKeepPatterns(File("proguard-rules.pro").readText())

        gsonPersistedClasses.forEach { className ->
            assertWithMessage("no -keep class ... { *; } rule covers $className")
                .that(keepPatterns.any { it.matches(className) })
                .isTrue()
        }
    }

    @Test
    fun `pattern matching follows proguard wildcards`() {
        val patterns = fullKeepPatterns(
            """
            -keep class com.example.model.** { *; }
            -keep class com.example.manager.Portable* { *; }
            -keepclassmembers class com.example.remote.** { <fields>; }
            """.trimIndent()
        )

        val matches = { name: String -> patterns.any { it.matches(name) } }
        assertWithMessage("double star crosses packages").that(matches("com.example.model.deep.Thing")).isTrue()
        assertWithMessage("single star stays in its package").that(matches("com.example.manager.PortableX")).isTrue()
        assertWithMessage("single star does not cross packages")
            .that(matches("com.example.manager.sub.PortableX")).isFalse()
        assertWithMessage("member-only rules do not count").that(matches("com.example.remote.Dto")).isFalse()
    }

    private fun fullKeepPatterns(rules: String): List<Regex> =
        rules.lineSequence()
            .map(String::trim)
            .mapNotNull { KEEP_ALL_MEMBERS.matchEntire(it)?.groupValues?.get(1) }
            .map(::toRegex)
            .toList()

    private fun toRegex(pattern: String): Regex {
        val regex = StringBuilder()
        var index = 0
        while (index < pattern.length) {
            val char = pattern[index]
            when {
                pattern.startsWith("**", index) -> {
                    regex.append(".*")
                    index++
                }
                char == '*' -> regex.append("[^.]*")
                char == '?' -> regex.append("[^.]")
                else -> regex.append(Regex.escape(char.toString()))
            }
            index++
        }
        return Regex(regex.toString())
    }

    private companion object {
        val KEEP_ALL_MEMBERS = Regex("""-keep class (\S+) \{ \*; \}""")
    }
}
