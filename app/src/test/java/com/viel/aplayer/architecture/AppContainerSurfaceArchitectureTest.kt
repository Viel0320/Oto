package com.viel.aplayer.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * App Container Surface Architecture Rule (Locks the public container to dependency views)
 * Prevents di-owned implementation properties from reappearing on AppContainer after they have moved to process-only wiring.
 */
class AppContainerSurfaceArchitectureTest {
    @Test
    fun publicAppContainerDoesNotExposeGraphOwnedProperties() {
        val appContainerSource = resolveSourceRoot().resolve("AppContainer.kt").readText()
        val publicSurface = appContainerSource.publicAppContainerDeclaration()
        val violations = graphOwnedPropertyNames.filter { propertyName ->
            Regex("""\b${Regex.escape(propertyName)}\b""").containsMatchIn(publicSurface)
        }

        // Public Surface Guard (Keeps callers on typed dependency views)
        // A broad AppContainer-typed reference must not recover di-owned implementations that scene, worker, and playback seams intentionally hide.
        assertTrue(
            buildString {
                appendLine("Public AppContainer must not expose di-owned implementation properties.")
                violations.forEach { violation -> appendLine("- $violation") }
            },
            violations.isEmpty()
        )
    }

    @Test
    fun graphOwnedPropertiesRemainOnInternalProcessContainer() {
        val appContainerSource = resolveSourceRoot().resolve("AppContainer.kt").readText()
        val processSurface = appContainerSource.processContainerDeclaration()
        val missingProperties = graphOwnedPropertyNames.filter { propertyName ->
            !Regex("""\bval\s+${Regex.escape(propertyName)}\b""").containsMatchIn(processSurface)
        }

        // Process Surface Guard (Keeps composition-root wiring explicit and internal)
        // The internal subtype documents which implementation adapters are reserved for process startup and di assembly.
        assertTrue(
            "DefaultAppContainer must implement the internal ProcessContainer surface.",
            Regex("""internal\s+class\s+DefaultAppContainer\([^\n]+\)\s*:\s*ProcessContainer""")
                .containsMatchIn(appContainerSource)
        )
        assertTrue(
            buildString {
                appendLine("ProcessContainer must retain the di-owned properties removed from public AppContainer.")
                missingProperties.forEach { missing -> appendLine("- $missing") }
            },
            missingProperties.isEmpty()
        )
    }

    private fun String.publicAppContainerDeclaration(): String {
        // Public Declaration Extraction (Inspect only the AppContainer contract)
        // Stopping before ProcessContainer prevents internal implementation properties from being counted as public surface violations.
        return substringAfter("interface AppContainer")
            .substringBefore("internal interface ProcessContainer")
    }

    private fun String.processContainerDeclaration(): String {
        // Process Declaration Extraction (Inspect only the internal process contract)
        // Stopping before DefaultAppContainer keeps concrete property implementations separate from the internal dependency view shape.
        return substringAfter("internal interface ProcessContainer")
            .substringBefore("@UnstableApi")
    }

    private fun resolveSourceRoot(): File {
        // Source Root Resolution (Supports both module and repository working directories)
        // Gradle can execute JVM tests from different directories, so the test checks both stable source-root candidates.
        val candidates = listOf(
            File("src/main/java/com/viel/aplayer"),
            File("app/src/main/java/com/viel/aplayer")
        )
        return candidates.firstOrNull { candidate -> candidate.isDirectory }
            ?: error("Could not locate app source root for app-container surface architecture test.")
    }

    companion object {
        // Graph-Owned Property Names (Pin known implementation adapters to the internal process surface)
        // These names come from di-owned gateways, managers, stores, and synchronizers that must not be available on public AppContainer.
        private val graphOwnedPropertyNames = listOf(
            "libraryRootGateway",
            "searchHistoryGateway",
            "playbackManager",
            "searchHistoryStore",
            "autoRewindManager",
            "absAuthorizedProgressSynchronizer",
            "absSyncTaskCoordinator"
        )
    }
}
