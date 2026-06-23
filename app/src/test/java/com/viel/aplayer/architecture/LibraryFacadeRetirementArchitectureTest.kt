package com.viel.aplayer.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Prevents UI dependencies from reopening the retired broad facade.
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
        val presentationDependencies = resolveSourceRoot()
            .resolve("di/dependencies/PresentationDependencies.kt")
            .readText()

        assertTrue(
            "Presentation dependency views must not expose the retired facade or its legacy transition interface.",
            !presentationDependencies.contains("LibraryPresentationDependencies") &&
                !presentationDependencies.contains("libraryFacade") &&
                !presentationDependencies.contains("import com.viel.aplayer.data.LibraryFacade")
        )
    }

    @Test
    fun dependencyViewModuleDoesNotExposeLibraryFacadeThroughUiDependencyViews() {
        val dependencyViewModule = resolveSourceRoot()
            .resolve("di/koin/DependencyViewModule.kt")
            .readText()

        assertTrue(
            "DependencyViewModule must not re-export LibraryFacade through UI dependency views.",
            !dependencyViewModule.contains("LibraryPresentationDependencies") &&
                !dependencyViewModule.contains("override val libraryFacade") &&
                !dependencyViewModule.contains("val libraryFacade:") &&
                !dependencyViewModule.contains("import com.viel.aplayer.data.LibraryFacade")
        )
    }

    private fun resolveSourceRoot(): File {
        val candidates = listOf(
            File("src/main/java/com/viel/aplayer"),
            File("app/src/main/java/com/viel/aplayer")
        )
        return candidates.firstOrNull { candidate -> candidate.isDirectory }
            ?: error("Could not locate app source root for library facade retirement architecture test.")
    }
}
