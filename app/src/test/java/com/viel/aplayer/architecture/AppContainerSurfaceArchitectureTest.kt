package com.viel.aplayer.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Locks the public container to dependency views.
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
        return substringAfter("interface AppContainer")
            .substringBefore("internal interface ProcessContainer")
    }

    private fun String.processContainerDeclaration(): String {
        return substringAfter("internal interface ProcessContainer")
            .substringBefore("@UnstableApi")
    }

    private fun resolveSourceRoot(): File {
        val candidates = listOf(
            File("src/main/java/com/viel/aplayer"),
            File("app/src/main/java/com/viel/aplayer")
        )
        return candidates.firstOrNull { candidate -> candidate.isDirectory }
            ?: error("Could not locate app source root for app-container surface architecture test.")
    }

    companion object {
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
