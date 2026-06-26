package com.viel.oto.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards release-safe Koin module wiring.
 *
 * Providers whose body only resolves another definition create a second synthetic factory.
 * Release shrinking can merge those tiny lambdas with neighboring providers, which has produced
 * recursive Scope.get() crashes in release builds.
 */
class KoinModuleArchitectureTest {

    @Test
    fun koinModulesDoNotUseRedirectOnlyAliasProviders() {
        val violations = resolveDiRoots()
            .flatMap { diRoot ->
                diRoot.walkTopDown()
                    .filter { file -> file.isFile && file.extension == "kt" }
                    .flatMap { file -> file.findRedirectOnlyAliasProviders(diRoot) }
            }
            .toList()

        assertTrue(
            buildString {
                appendLine("Koin modules must use direct contract registrations or bind/binds instead of redirect-only providers.")
                violations.forEach { violation -> appendLine("- $violation") }
            },
            violations.isEmpty()
        )
    }

    @Test
    fun redirectOnlyAliasDetectionCoversKoinDefinitionKinds() {
        val source = """
            module {
                single<Contract> { get<Implementation>() }
                factory<Contract> { get() }
                scoped<Contract> { getOrNull<Implementation>(named("qualified")) as Contract }
                viewModel<Contract> {
                    get<Implementation>()
                }
                single<Contract> { Implementation(get()) }
            }
        """.trimIndent()
        val snippets = source.findRedirectOnlyProviderSnippets()

        assertTrue(
            "All redirect-only Koin definition kinds should be detected.",
            snippets.size == 4
        )
        assertTrue(
            "Constructor providers that merely inject dependencies must remain valid.",
            snippets.none { snippet -> snippet.text.contains("Implementation(get())") }
        )
    }

    /**
     * Finds Koin definitions whose provider body only resolves another Koin definition.
     */
    private fun File.findRedirectOnlyAliasProviders(diRoot: File): List<String> {
        val text = readText()
        val relativePath = relativeTo(diRoot).invariantSeparatorsPath
        return text.findRedirectOnlyProviderSnippets().map { snippet ->
            val line = text.take(snippet.startIndex).count { char -> char == '\n' } + 1
            "$relativePath:$line redirects through `${snippet.text}`"
        }.toList()
    }

    /**
     * Extracts Koin provider definitions before checking whether the body is only a get call.
     */
    private fun String.findRedirectOnlyProviderSnippets(): List<ProviderSnippet> {
        val snippets = mutableListOf<ProviderSnippet>()
        var searchIndex = 0
        while (searchIndex < length) {
            val match = koinDefinitionStartRegex.find(this, searchIndex) ?: break
            val openBraceIndex = match.range.last
            val closeBraceIndex = findMatchingBrace(openBraceIndex) ?: break
            val body = substring(openBraceIndex + 1, closeBraceIndex)
            if (body.isRedirectOnlyResolution()) {
                snippets += ProviderSnippet(
                    startIndex = match.range.first,
                    text = match.value.lineSequence().first().trim()
                )
            }
            searchIndex = closeBraceIndex + 1
        }
        return snippets
    }

    private fun String.findMatchingBrace(openBraceIndex: Int): Int? {
        var depth = 0
        for (index in openBraceIndex until length) {
            when (this[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return null
    }

    private fun String.isRedirectOnlyResolution(): Boolean =
        redirectOnlyProviderBodyRegex.matches(trim())

    private fun resolveDiRoots(): List<File> {
        val candidates = listOf(
            File("src/main/java/com/viel/oto/di"),
            File("app/src/main/java/com/viel/oto/di"),
            File("application/src/main/java/com/viel/oto/di"),
            File("../application/src/main/java/com/viel/oto/di"),
            File("data/store/src/main/java/com/viel/oto/di"),
            File("../data/store/src/main/java/com/viel/oto/di"),
            File("library/import/src/main/java/com/viel/oto/di"),
            File("../library/import/src/main/java/com/viel/oto/di"),
            File("media/metadata/src/main/java/com/viel/oto/di"),
            File("../media/metadata/src/main/java/com/viel/oto/di"),
            File("media/playback/src/main/java/com/viel/oto/di"),
            File("../media/playback/src/main/java/com/viel/oto/di"),
            File("media/service/src/main/java/com/viel/oto/di"),
            File("../media/service/src/main/java/com/viel/oto/di")
        )
        return candidates
            .filter { candidate -> candidate.isDirectory }
            .distinctBy { candidate -> candidate.canonicalFile }
            .also { roots ->
                check(roots.isNotEmpty()) {
                    "Could not locate Koin module source roots for Koin module architecture test."
                }
            }
    }

    private data class ProviderSnippet(
        val startIndex: Int,
        val text: String
    )

    private companion object {
        private val koinDefinitionStartRegex = Regex(
            pattern = """\b(?:single|factory|scoped|viewModel)\s*(?:<[^>{}]+>)?\s*(?:\([^{}]*\)\s*)?\{"""
        )

        private val redirectOnlyProviderBodyRegex = Regex(
            pattern = """^(?:get|getOrNull)\s*(?:<[^>{}]+>)?\s*\(.*\)\s*(?:as\s+[A-Za-z0-9_.]+)?$""",
            options = setOf(RegexOption.DOT_MATCHES_ALL)
        )
    }
}
