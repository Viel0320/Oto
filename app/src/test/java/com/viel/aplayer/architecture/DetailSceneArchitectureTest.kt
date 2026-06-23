package com.viel.aplayer.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the stage-two detail dependency migration.
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
        val candidates = listOf(
            File("src/main/java/com/viel/aplayer"),
            File("app/src/main/java/com/viel/aplayer")
        )
        return candidates.firstOrNull { candidate -> candidate.isDirectory }
            ?: error("Could not locate app source root for detail scene architecture test.")
    }

    private fun forbiddenDetailRoomBoundaryTerms(): List<String> {
        return listOf(
            "com.viel.aplayer.data.entity",
            "BookWithProgress",
            "BookEntity",
            "ChapterWithBookFile"
        )
    }
}
