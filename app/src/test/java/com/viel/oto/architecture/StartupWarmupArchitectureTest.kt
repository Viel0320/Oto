package com.viel.oto.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Protects cold-start di laziness.
 * Verifies startup wiring keeps freshness reads on narrow persistence adapters before stale ABS roots schedule deeper synchronization work.
 */
class StartupWarmupArchitectureTest {
    @Test
    fun `startup warmup construction should not bind wide graph callable references`() {
        val sourceRoot = resolveSourceRoot()
        val applicationSource = sourceRoot.resolve("OtoApplication.kt").readText()
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
        val candidates = listOf(
            File("src/main/java/com/viel/oto"),
            File("app/src/main/java/com/viel/oto")
        )
        return candidates.firstOrNull { candidate -> candidate.isDirectory }
            ?: error("Could not locate app source root for startup warmup architecture test.")
    }
}
