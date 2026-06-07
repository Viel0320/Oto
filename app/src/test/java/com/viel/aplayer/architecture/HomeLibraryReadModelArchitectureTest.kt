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
        val dependenciesSource = resolveSourceRoot().resolve("dependencies/PresentationDependencies.kt").readText()
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
            "LibraryViewModel must consume HomeLibraryReadModel instead of the full LibraryFacade bus.",
            libraryViewModelSource.contains("homeLibraryReadModel.audiobooks")
        )
        assertTrue(
            "LibraryViewModel must send home commands through HomeLibraryUseCases.",
            listOf(
                "homeLibraryUseCases.scheduleColdStartSync()",
                "homeLibraryUseCases.updateReadStatus(",
                "homeLibraryUseCases.regenerateCoverAndMetadata(",
                "homeLibraryUseCases.addLocalRootAndScheduleSync(",
                "homeLibraryUseCases.clearSearchHistory()",
                "homeLibraryUseCases.scheduleUserSync()"
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
        val readModelSource = resolveSourceRoot().resolve("library/readmodel/HomeLibraryReadModel.kt").readText()

        assertTrue(
            "HomeLibraryReadModel adapters must be backed by granular gateways rather than LibraryFacade.",
            !readModelSource.contains("import com.viel.aplayer.data.LibraryFacade") &&
                !readModelSource.contains(": LibraryFacade") &&
                !readModelSource.contains("LibraryFacade(")
        )
        assertTrue(
            "HomeLibraryUseCases should stay small and scene-level.",
            listOf(
                "BookQueryGateway",
                "ScanScheduler",
                "LibraryRootGateway",
                "MetadataRefreshGateway",
                "SearchHistoryGateway"
            ).all { gatewayName -> readModelSource.contains(gatewayName) }
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
            ?: error("Could not locate app source root for home library architecture test.")
    }
}
