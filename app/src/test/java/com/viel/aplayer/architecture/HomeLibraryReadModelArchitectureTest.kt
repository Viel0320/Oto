package com.viel.aplayer.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Home Library Read Model Architecture Rule (Pins Candidate 5 home-scene dependency narrowing)
 * Protects LibraryViewModel from regressing to the full LibraryFacade while the facade remains a transition seam for other screens.
 */
class HomeLibraryReadModelArchitectureTest {

    @Test
    fun homeDependencyViewExposesSceneInterfacesInsteadOfLibraryFacade() {
        // Title: Update di path in test (Point to di/dependencies/PresentationDependencies.kt)
        // Changes relative path to target the dependencies subdirectory under di.
        val dependenciesSource = resolveSourceRoot().resolve("di/dependencies/PresentationDependencies.kt").readText().replace("\r\n", "\n")
        val homeInterface = dependenciesSource.substringAfter("interface HomeScreenDependencies")
            .substringBefore("/**\n * Settings Screen Dependencies")

        assertTrue(
            "HomeScreenDependencies must not inherit LibraryPresentationDependencies because that would re-expose LibraryFacade.",
            !homeInterface.substringBefore("{").contains("LibraryPresentationDependencies")
        )
        assertTrue(
            "HomeScreenDependencies must expose the home scene read model.",
            homeInterface.contains("val homeLibraryReadModel: HomeLibraryReadModel")
        )
        assertTrue(
            "HomeScreenDependencies must expose home scene use cases.",
            homeInterface.contains("val homeLibraryUseCases: HomeLibraryUseCases")
        )
        assertTrue(
            "HomeScreenDependencies must not expose the broad LibraryFacade property.",
            !homeInterface.contains("LibraryFacade") && !homeInterface.contains("libraryFacade")
        )
    }

    @Test
    fun libraryViewModelDependsOnHomeSceneInterfacesOnly() {
        val libraryViewModelSource = resolveSourceRoot().resolve("ui/home/LibraryViewModel.kt").readText()

        assertTrue(
            "LibraryViewModel must consume raw HomeLibraryReadModel streams instead of the full LibraryFacade bus.",
            libraryViewModelSource.contains("homeLibraryReadModel.audiobooks") &&
                libraryViewModelSource.contains("homeLibraryReadModel.hasRegisteredLibraryRoots")
        )
        assertTrue(
            "LibraryViewModel must own Home catalog sorting and grouping policy.",
            libraryViewModelSource.contains("HomeCatalogSortPolicy.sort(") &&
                libraryViewModelSource.contains("HomeCatalogSortPolicy.groupLabel(")
        )
        assertTrue(
            "LibraryViewModel must send home commands through HomeLibraryUseCases.",
            /*
             * Verify active Home Scene commands (Assert only commands that are currently owned by the Home catalog screen)
             * Removes assertion checks for relocated root setup, search history, and rescan triggers.
             */
            listOf(
                "homeLibraryUseCases.scheduleColdStartSync()",
                "homeLibraryUseCases.updateReadStatus(",
                "homeLibraryUseCases.regenerateCoverAndMetadata("
            ).all { expectedCall -> libraryViewModelSource.contains(expectedCall) }
        )
        assertTrue(
            "LibraryViewModel must not reference LibraryFacade directly or through the old dependency property.",
            !libraryViewModelSource.contains("import com.viel.aplayer.data.LibraryFacade") &&
                !libraryViewModelSource.contains("homeDependencies.libraryFacade") &&
                !libraryViewModelSource.contains("private val libraryFacade")
        )
    }

    @Test
    fun homeSceneAdapterDoesNotRewrapTheFullFacade() {
        // Title: Normalize line endings (Ensure substring delimiters match regardless of OS platform checkout format)
        // Replaces Windows CRLF line endings with LF to prevent test failures on Windows environments.
        val readModelSource = resolveSourceRoot().resolve("application/library/home/HomeLibraryReadModel.kt").readText().replace("\r\n", "\n")
        val readModelInterface = readModelSource.substringAfter("interface HomeLibraryReadModel")
            .substringBefore("/**\n * Home Library Use Cases")

        assertTrue(
            "HomeLibraryReadModel adapters must be backed by granular gateways rather than LibraryFacade.",
            !readModelSource.contains("import com.viel.aplayer.data.LibraryFacade") &&
                !readModelSource.contains(": LibraryFacade") &&
                !readModelSource.contains("LibraryFacade(")
        )
        assertTrue(
            "HomeLibraryUseCases should stay small and scene-level.",
            listOf(
                // Home Gateway Split Guard (Require separate read and metadata seams)
                // Home read models consume catalog streams while home commands update read status through metadata-only access.
                "BookCatalogGateway",
                "BookMetadataGateway",
                "ScanScheduler",
                "LibraryRootGateway",
                "MetadataRefreshGateway",
                "SearchHistoryGateway"
            ).all { gatewayName -> readModelSource.contains(gatewayName) }
        )
        assertTrue(
            "Home library interfaces must live under the application library home package.",
            readModelSource.contains("package com.viel.aplayer.application.library.home")
        )
        assertTrue(
            "HomeLibraryReadModel must expose the home scene projection rather than BookWithProgress.",
            readModelInterface.contains("val audiobooks: Flow<List<HomeBookItem>>") &&
                !readModelInterface.contains("BookWithProgress")
        )
        assertTrue(
            "HomeLibraryReadModel must not organize the Home catalog.",
            !readModelInterface.contains("observeCatalog(") &&
                !readModelSource.contains("HomeCatalogSortPolicy")
        )
    }

    @Test
    fun homeUiConsumesHomeBookProjectionInsteadOfRoomEntities() {
        val sourceRoot = resolveSourceRoot()
        val homeUiFiles = sourceRoot.resolve("ui/home")
            .walkTopDown()
            .filter { file -> file.isFile && file.name.endsWith(".kt") }
            .toList()

        assertTrue(
            "Home UI files must exist before enforcing projection boundaries.",
            homeUiFiles.isNotEmpty()
        )
        homeUiFiles.forEach { file ->
            val source = file.readText()
            assertTrue(
                "${file.toRelativeString(sourceRoot)} must not import Room entity packages.",
                !source.contains("import com.viel.aplayer.data.entity")
            )
        }
    }

    private fun resolveSourceRoot(): File {
        // Source Root Resolution (Supports both module and repository working directories)
        // Gradle can execute JVM tests from different directories, so the test checks both stable source-root candidates.
        val candidates = listOf(
            File("src/main/java/com/viel/aplayer"),
            File("app/src/main/java/com/viel/aplayer")
        )
        return candidates.firstOrNull { candidate -> candidate.isDirectory }
            ?: error("Could not locate app source root for home library architecture test.")
    }
}
