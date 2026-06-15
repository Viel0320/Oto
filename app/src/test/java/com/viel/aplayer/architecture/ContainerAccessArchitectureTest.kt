package com.viel.aplayer.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Container Access Architecture Rule (Locks callers onto typed dependency views)
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

        // Graph Close Policy Delegation (Pins DefaultAppContainer to the lifecycle helper)
        // The executable ordering behavior lives in di tests, while this architecture test keeps the composition root wired to that policy.
        val closeMethod = text.substringAfter("override fun close()")

        assertTrue(
            "DefaultAppContainer.close() must delegate di teardown to closeAppGraphsInLifecycleOrder(...).",
            closeMethod.contains("closeAppGraphsInLifecycleOrder(")
        )

        // Media Graph Lifetime Check (Keep playback runtime teardown routed through the lifecycle helper)
        // MediaGraph now owns initialized playback resources, so DefaultAppContainer.close() must pass it into the shared di close policy.
        assertTrue(
            "DefaultAppContainer.close() must include MediaGraph now that it owns playback runtime resources.",
            closeMethod.contains("media = media")
        )

        // Data Graph Lifetime Check (Keep persistence di non-closeable until it owns explicit shutdown resources)
        // DataGraph still exposes database and store providers only, so the container should not invent a data close path in this change.
        assertTrue(
            "DefaultAppContainer.close() should not close DataGraph until it owns closeable resources.",
            !closeMethod.contains("data.close()") && !closeMethod.contains("data = data")
        )
    }

    @Test
    fun guardedCallerRulesCoverCurrentAndFutureDependencyConsumerFamilies() {
        // Guarded Family Coverage (Locks the rule to package families rather than individual files)
        // New UI routes, media runtime classes, and Worker callers stay covered without requiring per-file allowlist updates.
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
            // Title: Update di paths in unguarded examples (Point to the correct nested packages under di/)
            // Changes path elements to match the new nested package locations.
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
        // Source Root Resolution (Supports both module and repository working directories)
        // Gradle can execute JVM tests from different directories, so the test checks both stable source-root candidates.
        val candidates = listOf(
            File("src/main/java/com/viel/aplayer"),
            File("app/src/main/java/com/viel/aplayer")
        )
        return candidates.firstOrNull { candidate -> candidate.isDirectory }
            ?: error("Could not locate app source root for container access architecture test.")
    }

    private fun File.isGuardedCaller(sourceRoot: File): Boolean {
        // Guarded Caller Selection (Restricts the rule to consumers that should depend on typed views)
        // The composition root and application provider may use the full container; screens, media runtime, and workers may not.
        val relativePath = relativeTo(sourceRoot).invariantSeparatorsPath
        return isGuardedCallerPath(relativePath)
    }

    private fun isGuardedCallerPath(relativePath: String): Boolean {
        // Guarded Caller Path Rule (Groups dependency consumers by architectural responsibility)
        // UI and media packages are fully guarded, and any future Worker class is guarded wherever WorkManager code lives.
        return relativePath.startsWith("ui/") ||
            relativePath.startsWith("media/") ||
            relativePath.endsWith("Worker.kt")
    }

    private fun File.findFullContainerAccesses(sourceRoot: File): List<String> {
        val text = readText()
        val relativePath = relativeTo(sourceRoot).invariantSeparatorsPath

        return forbiddenPatterns.flatMap { forbiddenPattern ->
            forbiddenPattern.regex.findAll(text).map { match ->
                // Violation Location Mapping (Reports source coordinates that are easy to act on)
                // Counting newlines before the match keeps the architecture test independent from compiler diagnostics.
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
        // Forbidden Container Access Patterns (Detect both provider calls and direct property access)
        // Typed dependency providers remain allowed because they return narrow interfaces from the dependencies package.
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
