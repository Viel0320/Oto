package com.viel.aplayer.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Startup Warmup Architecture Rule (Protects cold-start di laziness)
 * Verifies startup wiring keeps freshness reads on narrow persistence adapters before stale ABS roots schedule deeper synchronization work.
 */
class StartupWarmupArchitectureTest {
    @Test
    fun `startup warmup construction should not bind wide graph callable references`() {
        val sourceRoot = resolveSourceRoot()
        val applicationSource = sourceRoot.resolve("APlayerApplication.kt").readText()
        val createStartupWarmupBody = applicationSource
            .substringAfter("internal fun createStartupWarmup")
            .substringBefore("override fun newImageLoader")

        val forbiddenBoundReferences = listOf(
            "libraryRootGateway::",
            "scanScheduler::",
            "absCatalogSynchronizer::",
            "autoRewindManager::"
        )
        val violations = forbiddenBoundReferences.filter { forbiddenReference ->
            createStartupWarmupBody.contains(forbiddenReference)
        }

        // Bound Reference Laziness Guard (Keeps receiver resolution out of warmup construction)
        // Binding these di properties would allocate scanner, VFS, ABS catalog, or media recovery objects before the startup freshness gate runs.
        assertTrue(
            buildString {
                appendLine("createStartupWarmup must not bind wide di callable references.")
                violations.forEach { violation -> appendLine("- $violation") }
            },
            violations.isEmpty()
        )
        assertTrue(createStartupWarmupBody.contains("DefaultStartupWarmupDependencies("))
        assertTrue(createStartupWarmupBody.contains("libraryRootDaoProvider"))
        assertTrue(createStartupWarmupBody.contains("absCatalogStoreProvider"))
    }

    @Test
    fun `startup warmup adapter should read freshness without wide graph adapters`() {
        val sourceRoot = resolveSourceRoot()
        val adapterSource = sourceRoot.resolve("application/startup/DefaultStartupWarmupDependencies.kt").readText()

        val forbiddenAdapterTypes = listOf(
            "LibraryRootGateway",
            "ScanScheduler",
            "AbsCatalogSynchronizer",
            "AutoRewindManager",
            "VfsFileInterface",
            "CoverRecoveryHelper"
        )
        val violations = forbiddenAdapterTypes.filter { forbiddenType ->
            adapterSource.contains(forbiddenType)
        }

        // Persistence Adapter Boundary (Keeps startup freshness on Room-backed reads)
        // The adapter may read root rows and ABS sync state, but it must not import the wider di services it is meant to avoid.
        assertTrue(
            buildString {
                appendLine("DefaultStartupWarmupDependencies must stay free of wide di adapter types.")
                violations.forEach { violation -> appendLine("- $violation") }
            },
            violations.isEmpty()
        )
        assertTrue(adapterSource.contains("getActiveAbsRootsOnce()"))
        assertTrue(adapterSource.contains("getSyncState(rootId)"))
    }

    private fun resolveSourceRoot(): File {
        // Source Root Resolution (Supports both module and repository working directories)
        // Gradle can execute JVM tests from different directories, so the test checks both stable source-root candidates.
        val candidates = listOf(
            File("src/main/java/com/viel/aplayer"),
            File("app/src/main/java/com/viel/aplayer")
        )
        return candidates.firstOrNull { candidate -> candidate.isDirectory }
            ?: error("Could not locate app source root for startup warmup architecture test.")
    }
}
