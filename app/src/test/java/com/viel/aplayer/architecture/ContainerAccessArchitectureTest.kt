package com.viel.aplayer.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Locks callers onto typed dependency views.
 * Prevents UI, media runtime, and WorkManager callers from regressing to the full AppContainer provider.
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
                appendLine("Guarded callers must use typed dependency views instead of the full AppContainer.")
                appendLine("Use APlayerApplication.get*Dependencies(...) from the dependencies package.")
                violations.forEach { violation -> appendLine("- $violation") }
            },
            violations.isEmpty()
        )
    }

    @Test
    fun defaultAppContainerClosesGraphsInStableLifecycleOrder() {
        val sourceRoot = resolveSourceRoot()
        val appContainerSource = sourceRoot.resolve("AppContainer.kt")
        val text = appContainerSource.readText()

        val closeMethod = text.substringAfter("override fun close()")

        assertTrue(
            "DefaultAppContainer.close() must delegate di teardown to closeAppGraphsInLifecycleOrder(...).",
            closeMethod.contains("closeAppGraphsInLifecycleOrder(")
        )

        assertTrue(
            "DefaultAppContainer.close() must include MediaGraph now that it owns playback runtime resources.",
            closeMethod.contains("media = media")
        )

        assertTrue(
            "DefaultAppContainer.close() should not close DataGraph until it owns closeable resources.",
            !closeMethod.contains("data.close()") && !closeMethod.contains("data = data")
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
            "AppContainer.kt",
            "di/dependencies/PresentationDependencies.kt",
            "di/graph/LibraryGraph.kt"
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
                name = "the direct .container property",
                regex = Regex("""\.container\b""")
            )
        )
    }
}
