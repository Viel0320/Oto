package com.viel.aplayer.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Locks callers onto typed dependency views or Koin injection.
 * Prevents UI, media runtime, and WorkManager callers from regressing to the retired
 * AppContainer provider or from reaching into GlobalContext.get() directly outside the
 * sanctioned composition roots.
 */
class ContainerAccessArchitectureTest {

    @Test
    fun guardedCallersDoNotResolveTheFullApplicationContainer() {
        val sourceRoot = resolveSourceRoot()
        val violations = sourceRoot
            .walkTopDown()
            .filter { file -> file.isFile && file.extension == "kt" }
            .filter { file -> file.isGuardedCaller(sourceRoot) }
            .flatMap { file -> file.findFullContainerAccesses(sourceRoot) }
            .toList()

        assertTrue(
            buildString {
                appendLine("Guarded callers must use typed dependency views or koinInject/koinViewModel instead of GlobalContext.")
                violations.forEach { violation -> appendLine("- $violation") }
            },
            violations.isEmpty()
        )
    }

    @Test
    fun productionCodeDoesNotUseRetiredProjectSingletonAccessors() {
        val sourceRoot = resolveSourceRoot()
        val violations = sourceRoot
            .walkTopDown()
            .filter { file -> file.isFile && file.extension == "kt" }
            .filterNot { file -> file.isCompositionRoot(sourceRoot) }
            .flatMap { file -> file.findRetiredSingletonAccesses(sourceRoot) }
            .toList()

        assertTrue(
            buildString {
                appendLine("Production code must use constructor injection or Koin modules instead of retired project singletons.")
                violations.forEach { violation -> appendLine("- $violation") }
            },
            violations.isEmpty()
        )
    }

    @Test
    fun guardedCallerRulesCoverCurrentAndFutureDependencyConsumerFamilies() {
        val guardedExamples = listOf(
            "ui/newfeature/NewFeatureViewModel.kt",
            "media/service/NewPlaybackRuntime.kt",
            "media/NewPlaybackHelper.kt",
            "library/sync/NewLibraryWorker.kt",
            "abs/sync/NewAbsWorker.kt"
        )
        val unguardedExamples = listOf(
            "APlayerApplication.kt",
            "di/koin/APlayerKoinApplication.kt"
        )

        assertTrue(
            "Guarded caller rules must cover UI, media, and Worker families.",
            guardedExamples.all(::isGuardedCallerPath)
        )
        assertTrue(
            "Composition roots and typed dependency definitions must stay outside the guarded caller rule.",
            unguardedExamples.none(::isGuardedCallerPath)
        )
    }

    private fun resolveSourceRoot(): File {
        val candidates = listOf(
            File("src/main/java/com/viel/aplayer"),
            File("app/src/main/java/com/viel/aplayer")
        )
        return candidates.firstOrNull { candidate -> candidate.isDirectory }
            ?: error("Could not locate app source root for container access architecture test.")
    }

    private fun File.isGuardedCaller(sourceRoot: File): Boolean {
        val relativePath = relativeTo(sourceRoot).invariantSeparatorsPath
        return isGuardedCallerPath(relativePath)
    }

    private fun isGuardedCallerPath(relativePath: String): Boolean {
        return relativePath.startsWith("ui/") ||
            relativePath.startsWith("media/") ||
            relativePath.endsWith("Worker.kt")
    }

    private fun File.findFullContainerAccesses(sourceRoot: File): List<String> {
        val text = readText()
        val relativePath = relativeTo(sourceRoot).invariantSeparatorsPath

        return forbiddenPatterns.flatMap { forbiddenPattern ->
            forbiddenPattern.regex.findAll(text).map { match ->
                val line = text.take(match.range.first).count { char -> char == '\n' } + 1
                "$relativePath:$line uses ${forbiddenPattern.name}"
            }
        }
    }

    private fun File.findRetiredSingletonAccesses(sourceRoot: File): List<String> {
        val text = readText()
        val relativePath = relativeTo(sourceRoot).invariantSeparatorsPath

        return retiredSingletonPatterns.flatMap { forbiddenPattern ->
            forbiddenPattern.regex.findAll(text).map { match ->
                val line = text.take(match.range.first).count { char -> char == '\n' } + 1
                "$relativePath:$line uses ${forbiddenPattern.name}"
            }
        }
    }

    private fun File.isCompositionRoot(sourceRoot: File): Boolean {
        val relativePath = relativeTo(sourceRoot).invariantSeparatorsPath
        return relativePath == "APlayerApplication.kt" ||
            relativePath.startsWith("di/koin/")
    }

    private data class ForbiddenPattern(
        val name: String,
        val regex: Regex
    )

    companion object {
        private val forbiddenPatterns = listOf(
            ForbiddenPattern(
                name = "APlayerApplication.getContainer(...)",
                regex = Regex("""\bAPlayerApplication\s*\.\s*getContainer\s*\(""")
            ),
            ForbiddenPattern(
                name = "APlayerApplication.getProcessContainer(...)",
                regex = Regex("""\bAPlayerApplication\s*\.\s*getProcessContainer\s*\(""")
            ),
            ForbiddenPattern(
                name = "APlayerApplication.getXxxDependencies(...)",
                regex = Regex("""\bAPlayerApplication\s*\.\s*get\w+Dependencies\s*\(""")
            ),
            ForbiddenPattern(
                name = "the direct .container property",
                regex = Regex("""\.container\b""")
            ),
            ForbiddenPattern(
                name = "GlobalContext.get()",
                regex = Regex("""\bGlobalContext\s*\.\s*get\s*\(""")
            ),
            ForbiddenPattern(
                name = "org.koin.core.context.GlobalContext.get()",
                regex = Regex("""\borg\.koin\.core\.context\.GlobalContext\s*\.\s*get\s*\(""")
            )
        )

        private val retiredSingletonPatterns = listOf(
            ForbiddenPattern(
                name = "AppDatabase.getInstance(...)",
                regex = Regex("""\bAppDatabase\s*\.\s*getInstance\s*\(""")
            ),
            ForbiddenPattern(
                name = "AppDatabase.closeInstance(...)",
                regex = Regex("""\bAppDatabase\s*\.\s*closeInstance\s*\(""")
            ),
            ForbiddenPattern(
                name = "AppSettingsRepository.getInstance(...)",
                regex = Regex("""\bAppSettingsRepository\s*\.\s*getInstance\s*\(""")
            ),
            ForbiddenPattern(
                name = "AbsCredentialStore.getInstance(...)",
                regex = Regex("""\bAbsCredentialStore\s*\.\s*getInstance\s*\(""")
            ),
            ForbiddenPattern(
                name = "SearchHistoryStore.getInstance(...)",
                regex = Regex("""\bSearchHistoryStore\s*\.\s*getInstance\s*\(""")
            ),
            ForbiddenPattern(
                name = "GlobalContext.get()",
                regex = Regex("""\bGlobalContext\s*\.\s*get\s*\(""")
            ),
            ForbiddenPattern(
                name = "org.koin.core.context.GlobalContext.get()",
                regex = Regex("""\borg\.koin\.core\.context\.GlobalContext\s*\.\s*get\s*\(""")
            )
        )
    }
}
