package com.viel.aplayer.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Library Facade Retirement Guard (Prevents UI dependencies from reopening the retired broad facade)
 * Locks phase-five boundaries so presentation code and dependency views keep using scene-specific seams.
 */
class LibraryFacadeRetirementArchitectureTest {
    @Test
    fun uiSourcesDoNotImportLibraryFacade() {
        val uiSources = resolveSourceRoot().resolve("ui")
            .walkTopDown()
            .filter { file -> file.isFile && file.extension == "kt" }
            .toList()

        assertTrue(
            "UI sources must consume scene-specific dependency views instead of importing the retired LibraryFacade.",
            uiSources.all { file -> !file.readText().contains("import com.viel.aplayer.data.LibraryFacade") }
        )
    }

    @Test
    fun presentationDependenciesDoNotExposeLibraryPresentationDependencies() {
        // Title: Update di path in test (Point to application/di/dependencies/PresentationDependencies.kt)
        // Changes relative path to target the dependencies subdirectory under application/di.
        val presentationDependencies = resolveSourceRoot()
            .resolve("application/di/dependencies/PresentationDependencies.kt")
            .readText()

        assertTrue(
            "Presentation dependency views must not expose the retired facade or its legacy transition interface.",
            !presentationDependencies.contains("LibraryPresentationDependencies") &&
                !presentationDependencies.contains("libraryFacade") &&
                !presentationDependencies.contains("import com.viel.aplayer.data.LibraryFacade")
        )
    }

    @Test
    fun appContainerDoesNotExposeLibraryFacadeThroughUiDependencyViews() {
        val appContainer = resolveSourceRoot().resolve("AppContainer.kt").readText()

        assertTrue(
            "AppContainer must not re-export LibraryFacade through the composition root or UI dependency views.",
            !appContainer.contains("LibraryPresentationDependencies") &&
                !appContainer.contains("override val libraryFacade") &&
                !appContainer.contains("val libraryFacade:") &&
                !appContainer.contains("import com.viel.aplayer.data.LibraryFacade")
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
            ?: error("Could not locate app source root for library facade retirement architecture test.")
    }
}
