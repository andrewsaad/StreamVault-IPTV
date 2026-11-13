package com.streamvault.app

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * Gson reads settings, backups and staging records back by field name. If R8 renames the fields or enum constants
 * of such a class, a release build writes JSON that the next release cannot read, and the user's data silently
 * falls back to defaults. This test finds the classes Gson reads by scanning main sources for `fromJson(...,
 * X::class.java)` and `TypeToken<...>()` targets, and checks that a keep rule covers each one.
 */
class ProguardGsonKeepRulesTest {

    /**
     * The scan sees only the top-level class Gson is asked for. These are written with `toJson(Any)` or nested
     * inside a scanned class, so they are listed by hand; each must have a full `-keep class ... { *; }` rule.
     */
    private val unscannedGsonClasses = listOf(
        "com.streamvault.domain.settings.PlaybackQualityLimit",
        "com.streamvault.domain.settings.PlaybackQualityProfile",
        "com.streamvault.domain.sync.AutomaticSyncConfiguration",
        "com.streamvault.domain.sync.AutomaticSyncOutcome",
        "com.streamvault.domain.sync.RefreshSection",
        "com.streamvault.data.manager.BackupManagerImpl\$ReplaceScopePayload",
        "com.streamvault.domain.model.Provider",
        "com.streamvault.domain.model.ProviderAccountRuntime"
    )

    @Test
    fun `every class gson reads back is covered by a keep rule`() {
        val rules = File("proguard-rules.pro").readText()
        val covering = fullKeepPatterns(rules) + fieldKeepPatterns(rules)
        val scan = scanGsonTargets(File("..").canonicalFile)

        assertWithMessage("Gson targets whose class could not be resolved; extend the scan or the skip list")
            .that(scan.unresolved).isEmpty()
        // Guards the scan itself: if the patterns stop matching, the coverage check below would pass vacuously.
        assertWithMessage("Gson targets found by the source scan").that(scan.targets.size).isAtLeast(40)
        assertThat(scan.targets).containsAtLeast(
            "com.streamvault.domain.settings.PlaybackQualityPreferences",
            "com.streamvault.domain.sync.AutomaticSyncSettings",
            "com.streamvault.data.provider.ProviderConfigRevisionEnvelope",
            "com.streamvault.data.manager.PendingBackupRestoreCoordinator\$ReplaceScopePayload"
        )

        val uncovered = scan.targets.filterNot { target -> covering.any { it.matches(target) } }
        assertWithMessage("Gson-read classes with no keep rule in app/proguard-rules.pro").that(uncovered).isEmpty()
    }

    @Test
    fun `classes gson sees only through toJson or nesting keep all members`() {
        val keepPatterns = fullKeepPatterns(File("proguard-rules.pro").readText())

        unscannedGsonClasses.forEach { className ->
            assertWithMessage("no -keep class ... { *; } rule covers $className")
                .that(keepPatterns.any { it.matches(className) })
                .isTrue()
        }
    }

    @Test
    fun `pattern matching follows proguard wildcards`() {
        val rules = """
            -keep class com.example.model.** { *; }
            -keep class com.example.manager.Portable* { *; }
            -keepclassmembers class com.example.remote.** { <fields>; }
            -keepclassmembers class com.example.other.** { <methods>; }
        """.trimIndent()
        val full = fullKeepPatterns(rules)
        val any = full + fieldKeepPatterns(rules)

        val matches = { patterns: List<Regex>, name: String -> patterns.any { it.matches(name) } }
        assertWithMessage("double star crosses packages").that(matches(full, "com.example.model.deep.Thing")).isTrue()
        assertWithMessage("single star stays in its package").that(matches(full, "com.example.manager.PortableX")).isTrue()
        assertWithMessage("single star does not cross packages")
            .that(matches(full, "com.example.manager.sub.PortableX")).isFalse()
        assertWithMessage("field rules count for gson targets").that(matches(any, "com.example.remote.Dto")).isTrue()
        assertWithMessage("field rules are not full keeps").that(matches(full, "com.example.remote.Dto")).isFalse()
        assertWithMessage("method rules do not keep field names").that(matches(any, "com.example.other.Dto")).isFalse()
    }

    @Test
    fun `scan resolves imports, aliases, nested and same package classes`() {
        val root = java.nio.file.Files.createTempDirectory("gson-scan").toFile()
        try {
            val main = File(root, "lib/src/main/java/com/streamvault/sample").apply { mkdirs() }
            File(main, "Models.kt").writeText(
                """
                package com.streamvault.sample

                data class SamePackage(val a: Int)
                """.trimIndent()
            )
            File(main, "Reader.kt").writeText(
                """
                package com.streamvault.sample

                import com.google.gson.reflect.TypeToken
                import com.streamvault.other.Imported
                import com.streamvault.other.Aliased as Renamed
                import com.streamvault.wild.*

                class Reader {
                    private data class Nested(val b: Int)

                    fun read(gson: Gson, json: String) {
                        gson.fromJson(json, SamePackage::class.java)
                        gson.fromJson(json as String, Nested::class.java)
                        gson.fromJson(json, com.streamvault.qualified.Thing::class.java)
                        gson.fromJson(json, JsonObject::class.java)
                        gson.fromJson<List<Imported>>(json, object : TypeToken<List<Imported>>() {}.type)
                        gson.fromJson<Map<String, Renamed>>(json, object : TypeToken<Map<String, Renamed>>() {}.type)
                        gson.fromJson(json, FromWildcard::class.java)
                        gson.fromJson(json, Missing::class.java)
                    }
                }
                """.trimIndent()
            )
            File(root, "lib/src/main/java/com/streamvault/wild").apply { mkdirs() }
                .resolve("FromWildcard.kt")
                .writeText("package com.streamvault.wild\n\nclass FromWildcard\n")
            File(root, "lib/src/test/java/com/streamvault/sample").apply { mkdirs() }
                .resolve("Ignored.kt")
                .writeText("package com.streamvault.sample\n\nfun t(g: Gson) = g.fromJson(\"\", TestOnly::class.java)\n")

            val scan = scanGsonTargets(root)

            assertThat(scan.targets).containsExactly(
                "com.streamvault.sample.SamePackage",
                "com.streamvault.sample.Reader\$Nested",
                "com.streamvault.qualified.Thing",
                "com.streamvault.other.Imported",
                "com.streamvault.other.Aliased",
                "com.streamvault.wild.FromWildcard"
            )
            assertThat(scan.unresolved).containsExactly("Missing")
        } finally {
            root.deleteRecursively()
        }
    }

    private data class GsonScan(val targets: Set<String>, val unresolved: Set<String>)

    private class SourceFile(val text: String) {
        val packageName: String = PACKAGE.find(text)?.groupValues?.get(1).orEmpty()
        val declarations = mutableMapOf<String, String>()
        val declared = mutableListOf<String>()
        val imports = mutableMapOf<String, String>()
        val wildcardImports = mutableListOf<String>()

        init {
            var outer: String? = null
            DECLARATION.findAll(text).forEach { match ->
                val name = match.groupValues[2]
                val qualified = if (match.groupValues[1].isEmpty()) {
                    qualify(packageName, name).also { outer = it }
                } else {
                    outer?.let { "$it\$$name" } ?: qualify(packageName, name)
                }
                declarations.putIfAbsent(name, qualified)
                declared += qualified
            }
            IMPORT.findAll(text).forEach { match ->
                val path = match.groupValues[1]
                when {
                    match.groupValues[2].isNotEmpty() -> wildcardImports += path
                    else -> imports[match.groupValues[3].ifEmpty { path.substringAfterLast('.') }] = path
                }
            }
        }
    }

    private fun scanGsonTargets(root: File): GsonScan {
        val files = root.walkTopDown()
            .onEnter { it.name !in SKIPPED_DIRECTORIES }
            .filter { it.isFile && it.extension == "kt" && "/src/main/" in it.invariantSeparatorsPath }
            .map { SourceFile(it.readText()) }
            .toList()
        val known = files.flatMap { it.declared }.toSet()

        val targets = sortedSetOf<String>()
        val unresolved = sortedSetOf<String>()
        files.forEach { file ->
            val names = FROM_JSON_CLASS.findAll(file.text).map { it.groupValues[1] } +
                TYPE_TOKEN.findAll(file.text).flatMap { token -> IDENTIFIER.findAll(token.groupValues[1]).map { it.value } }
            names.filterNot { it in NON_APP_TYPES }.forEach { name ->
                val resolved = resolve(name, file, known)
                when {
                    resolved == null -> unresolved += name
                    resolved != EXTERNAL -> targets += resolved
                }
            }
        }
        return GsonScan(targets, unresolved)
    }

    /** Returns the binary class name, [EXTERNAL] for a class outside the app, or null when it cannot be found. */
    private fun resolve(name: String, file: SourceFile, known: Set<String>): String? {
        if (name.first().isLowerCase()) return if (name.startsWith(APP_PACKAGE)) name else EXTERNAL
        val head = name.substringBefore('.')
        val nested = name.substringAfter('.', "").split('.').filter(String::isNotEmpty)
        fun withNested(qualified: String) = qualified + nested.joinToString("") { "\$$it" }

        file.imports[head]?.let { imported -> return if (imported.startsWith(APP_PACKAGE)) withNested(imported) else EXTERNAL }
        file.declarations[head]?.let { return withNested(it) }
        qualify(file.packageName, head).takeIf { it in known }?.let { return withNested(it) }
        file.wildcardImports.map { "$it.$head" }.firstOrNull { it in known }?.let { return withNested(it) }
        return null
    }

    private fun fullKeepPatterns(rules: String): List<Regex> = rulePatterns(rules, KEEP_ALL_MEMBERS)

    private fun fieldKeepPatterns(rules: String): List<Regex> = rulePatterns(rules, KEEP_FIELDS)

    private fun rulePatterns(rules: String, rule: Regex): List<Regex> =
        rules.lineSequence()
            .map(String::trim)
            .mapNotNull { rule.matchEntire(it)?.groupValues?.get(1) }
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
        const val APP_PACKAGE = "com.streamvault."
        const val EXTERNAL = "<external>"
        val SKIPPED_DIRECTORIES = setOf("build", ".git", ".gradle", "test", "androidTest", "node_modules")

        /** Kotlin, Java and Gson types that appear as fromJson or TypeToken targets but are not app classes. */
        val NON_APP_TYPES = setOf(
            "List", "MutableList", "Map", "MutableMap", "Set", "MutableSet", "Array", "String", "Long", "Int",
            "Boolean", "Double", "Float", "Any", "Unit", "T", "JsonArray", "JsonObject", "JsonElement"
        )

        val KEEP_ALL_MEMBERS = Regex("""-keep class (\S+) \{ \*; \}""")
        val KEEP_FIELDS = Regex("""-keepclassmembers class (\S+) \{ <fields>; \}""")

        val PACKAGE = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE)
        val IMPORT = Regex("""^import\s+([\w.]+?)(\.\*)?(?:\s+as\s+(\w+))?\s*${'$'}""", RegexOption.MULTILINE)
        val DECLARATION = Regex(
            """^([ \t]*)(?:(?:private|internal|public|protected|data|enum|sealed|abstract|open|inner|value|annotation)\s+)*(?:class|object|interface)\s+(\w+)""",
            RegexOption.MULTILINE
        )

        /** `fromJson(source, Target::class.java)`, where the first argument may contain one level of parentheses. */
        val FROM_JSON_CLASS = Regex("""\bfromJson(?:<[^\n]*?>)?\(\s*[^,()]*(?:\([^()]*\)[^,()]*)*,\s*([\w.]+)::class\.java""")
        val TYPE_TOKEN = Regex("""TypeToken<(.+?)>\(\)""")
        val IDENTIFIER = Regex("""[A-Za-z_][\w.]*""")

        fun qualify(packageName: String, name: String) = if (packageName.isEmpty()) name else "$packageName.$name"
    }
}
