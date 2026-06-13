package com.viel.aplayer.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Detail Scene Architecture Test (Pins the stage-two detail dependency migration)
 * Prevents DetailViewModel and new detail scene modules from drifting back to LibraryFacade or Room entity expansion.
 */
class DetailSceneArchitectureTest {

    @Test
    fun detailViewModelConsumesDetailSceneDependenciesOnly() {
        val detailViewModelSource = resolveSourceRoot().resolve("ui/detail/DetailViewModel.kt").readText()

        assertTrue(
            "DetailViewModel must not import the broad library facade.",
            !detailViewModelSource.contains("import com.viel.aplayer.data.LibraryFacade")
        )
        assertTrue(
            "DetailViewModel must not call the old library presentation dependency provider.",
            !detailViewModelSource.contains("getLibraryPresentationDependencies")
        )
        assertTrue(
            "DetailViewModel must resolve the detail-specific dependency view.",
            detailViewModelSource.contains("getDetailScreenDependencies")
        )
        assertTrue(
            "DetailViewModel must use detail read and command scene interfaces.",
            detailViewModelSource.contains("detailBookReadModel") &&
                detailViewModelSource.contains("detailBookCommands")
        )
    }

    @Test
    fun detailViewModelDoesNotDirectlyQueryFilesRootsOrAvailability() {
        val detailViewModelSource = resolveSourceRoot().resolve("ui/detail/DetailViewModel.kt").readText()

        assertTrue(
            "DetailViewModel must not directly query source files, roots, or availability gateways.",
            listOf(
                "getAllFilesForBookSync",
                "getCachedLibraryRoots",
                "getAllRootsOnce",
                "refreshDetailAvailabilityStatus",
                "observeBookById"
            ).none { forbiddenCall -> detailViewModelSource.contains(forbiddenCall) }
        )
    }

    @Test
    fun detailDependencyViewDoesNotInheritLibraryPresentationDependencies() {
        // Title: Normalize line endings (Ensure substring delimiters match regardless of OS platform checkout format)
        // Replaces Windows CRLF line endings with LF to prevent test failures on Windows environments.
        val dependenciesSource = resolveSourceRoot().resolve("dependencies/PresentationDependencies.kt").readText().replace("\r\n", "\n")
        val detailInterface = dependenciesSource.substringAfter("interface DetailScreenDependencies")
            .substringBefore("/**\n * Home Screen Dependencies")

        assertTrue(
            "DetailScreenDependencies must not inherit LibraryPresentationDependencies because that would re-expose LibraryFacade.",
            !detailInterface.substringBefore("{").contains("LibraryPresentationDependencies")
        )
        assertTrue(
            "DetailScreenDependencies must expose the detail read model.",
            detailInterface.contains("val detailBookReadModel: DetailBookReadModel")
        )
        assertTrue(
            "DetailScreenDependencies must expose the detail command interface.",
            detailInterface.contains("val detailBookCommands: DetailBookCommands")
        )
    }

    @Test
    fun newDetailModuleCodeDoesNotImportFacadeOrRoomEntities() {
        val detailModuleRoot = resolveSourceRoot().resolve("application/library/detail")
        val moduleFiles = detailModuleRoot
            .walkTopDown()
            .filter { file -> file.isFile && file.name.endsWith(".kt") }
            .toList()

        assertTrue(
            "Detail scene module files must exist for the stage-two migration.",
            moduleFiles.isNotEmpty()
        )
        assertTrue(
            "New detail scene module logic must be backed by scene projections and granular gateways rather than LibraryFacade or Room entities.",
            moduleFiles.all { file ->
                val source = file.readText()
                !source.contains("import com.viel.aplayer.data.LibraryFacade") &&
                    forbiddenDetailRoomBoundaryTerms().none { term -> source.contains(term) }
            }
        )
    }

    @Test
    fun detailUiCodeDoesNotUseRoomEntityBoundaryTypes() {
        val detailUiRoot = resolveSourceRoot().resolve("ui/detail")
        val uiFiles = detailUiRoot
            .walkTopDown()
            .filter { file -> file.isFile && file.name.endsWith(".kt") }
            .toList()

        assertTrue(
            "Detail UI files must render DetailSnapshot and DetailBookItem rather than Room entity projections.",
            uiFiles.all { file ->
                val source = file.readText()
                forbiddenDetailRoomBoundaryTerms().none { term -> source.contains(term) }
            }
        )
    }

    @Test
    fun detailSnapshotExposesSceneItemInsteadOfRoomProjection() {
        val snapshotSource = resolveSourceRoot()
            .resolve("application/library/detail/DetailSnapshot.kt")
            .readText()

        assertTrue(
            "DetailSnapshot must define the DetailBookItem scene projection.",
            snapshotSource.contains("data class DetailBookItem")
        )
        assertTrue(
            "DetailSnapshot must not expose Room entity or relation projection types.",
            forbiddenDetailRoomBoundaryTerms().none { term -> snapshotSource.contains(term) }
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
            ?: error("Could not locate app source root for detail scene architecture test.")
    }

    private fun forbiddenDetailRoomBoundaryTerms(): List<String> {
        // Detail Boundary Forbidden Terms (Catch both imports and direct type-name regressions)
        // Boundary files should traffic in DetailSnapshot and DetailBookItem, not Room entities or Room relation projections.
        return listOf(
            "com.viel.aplayer.data.entity",
            "BookWithProgress",
            "BookEntity",
            "ChapterWithBookFile"
        )
    }
}
