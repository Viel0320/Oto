package com.viel.oto.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins Candidate 5 home-scene dependency narrowing.
 * Protects LibraryViewModel from regressing to the full LibraryFacade while the facade remains a transition seam for other screens.
 */
class HomeLibraryReadModelArchitectureTest {

    @Test
    fun libraryViewModelDependsOnHomeSceneInterfacesOnly() {
        val libraryViewModelSource = ArchitectureSourceRoots.uiMainFile("ui/home/LibraryViewModel.kt").readText()

        assertTrue(
            "LibraryViewModel must consume raw HomeLibraryReadModel streams instead of the full LibraryFacade bus.",
            libraryViewModelSource.contains("homeLibraryReadModel.audiobooks") &&
                libraryViewModelSource.contains("homeLibraryReadModel.hasRegisteredLibraryRoots")
        )
        assertTrue(
            "LibraryViewModel must organize Home catalog sorting and grouping through one policy pass.",
            libraryViewModelSource.contains("HomeCatalogSortPolicy.organize(") &&
                !libraryViewModelSource.contains("HomeCatalogSortPolicy.sort(") &&
                !libraryViewModelSource.contains("HomeCatalogSortPolicy.groupLabel(")
        )
        assertTrue(
            "LibraryViewModel must send home commands through HomeLibraryUseCases.",
            listOf(
                "homeLibraryUseCases.scheduleColdStartSync()",
                "homeLibraryUseCases.updateReadStatus(",
                "homeLibraryUseCases.regenerateCoverAndMetadata("
            ).all { expectedCall -> libraryViewModelSource.contains(expectedCall) }
        )
        assertTrue(
            "LibraryViewModel must not reference LibraryFacade directly or through the old dependency property.",
            !libraryViewModelSource.contains("import com.viel.oto.data.LibraryFacade") &&
                !libraryViewModelSource.contains("homeDependencies.libraryFacade") &&
                !libraryViewModelSource.contains("private val libraryFacade")
        )
    }

    @Test
    fun homeSceneAdapterDoesNotRewrapTheFullFacade() {
        val readModelSource = ArchitectureSourceRoots.applicationMainFile("application/library/home/HomeLibraryReadModel.kt").readText().replace("\r\n", "\n")
        val readModelInterface = readModelSource.substringAfter("interface HomeLibraryReadModel")
            .substringBefore("/**\n * Home Library Use Cases")

        assertTrue(
            "HomeLibraryReadModel adapters must be backed by granular gateways rather than LibraryFacade.",
            !readModelSource.contains("import com.viel.oto.data.LibraryFacade") &&
                !readModelSource.contains(": LibraryFacade") &&
                !readModelSource.contains("LibraryFacade(")
        )
        assertTrue(
            "HomeLibraryUseCases should stay small and scene-level.",
            listOf(
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
            readModelSource.contains("package com.viel.oto.application.library.home")
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
        val sourceRoot = ArchitectureSourceRoots.uiMain()
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
                !source.contains("import com.viel.oto.data.entity")
            )
        }
    }

}
