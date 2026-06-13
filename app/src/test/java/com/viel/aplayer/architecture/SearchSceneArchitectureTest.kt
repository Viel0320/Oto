package com.viel.aplayer.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Search Scene Architecture Test (Pins the stage-one search dependency migration)
 * Prevents SearchViewModel and SearchScreenDependencies from drifting back to the broad presentation facade.
 */
class SearchSceneArchitectureTest {

    @Test
    fun searchViewModelConsumesSearchSceneDependenciesOnly() {
        val searchViewModelSource = resolveSourceRoot().resolve("ui/search/SearchViewModel.kt").readText()

        assertTrue(
            "SearchViewModel must not import the broad library facade.",
            !searchViewModelSource.contains("import com.viel.aplayer.data.LibraryFacade")
        )
        assertTrue(
            "SearchViewModel must not call the old library presentation dependency provider.",
            !searchViewModelSource.contains("getLibraryPresentationDependencies")
        )
        assertTrue(
            "SearchViewModel must resolve the search-specific dependency view.",
            searchViewModelSource.contains("getSearchScreenDependencies")
        )
        assertTrue(
            "SearchViewModel must use the search scene read model and commands.",
            searchViewModelSource.contains("searchLibraryReadModel") &&
                searchViewModelSource.contains("searchLibraryCommands")
        )
    }

    @Test
    fun searchDependencyViewDoesNotInheritLibraryPresentationDependencies() {
        // Title: Update di path in test (Point to application/di/dependencies/PresentationDependencies.kt)
        // Changes relative path to target the dependencies subdirectory under application/di.
        val dependenciesSource = resolveSourceRoot().resolve("application/di/dependencies/PresentationDependencies.kt").readText().replace("\r\n", "\n")
        val searchInterface = dependenciesSource.substringAfter("interface SearchScreenDependencies")
            .substringBefore("/**\n * Home Screen Dependencies")

        assertTrue(
            "SearchScreenDependencies must not inherit LibraryPresentationDependencies because that would re-expose the broad facade.",
            !searchInterface.substringBefore("{").contains("LibraryPresentationDependencies")
        )
        assertTrue(
            "SearchScreenDependencies must expose the search read model.",
            searchInterface.contains("val searchLibraryReadModel: SearchLibraryReadModel")
        )
        assertTrue(
            "SearchScreenDependencies must expose the search command interface.",
            searchInterface.contains("val searchLibraryCommands: SearchLibraryCommands")
        )
    }

    @Test
    fun searchModuleDoesNotImportTheBroadFacade() {
        val searchModuleSource = resolveSourceRoot()
            .resolve("application/library/search/DefaultSearchLibraryModule.kt")
            .readText()

        assertTrue(
            "DefaultSearchLibraryModule must be backed by granular gateways and planner dependencies.",
            !searchModuleSource.contains("import com.viel.aplayer.data.LibraryFacade")
        )
    }

    @Test
    fun searchReadModelAndUiExposeSceneSnapshotsInsteadOfRoomRows() {
        val sourceRoot = resolveSourceRoot()
        val readModelSource = sourceRoot.resolve("application/library/search/SearchLibraryReadModel.kt").readText()
        val commandsSource = sourceRoot.resolve("application/library/search/SearchLibraryCommands.kt").readText()
        val guardedUiFiles = listOf(
            "ui/search/SearchViewModel.kt",
            "ui/search/SearchRoute.kt",
            "ui/search/SearchScreen.kt",
            "ui/search/SearchOverlay.kt"
        )

        assertTrue(
            "SearchLibraryReadModel must return the scene snapshot type rather than the Room relation row.",
            readModelSource.contains("Flow<List<SearchResultSnapshot>>")
        )
        assertTrue(
            "SearchLibraryReadModel must not import Room entity packages.",
            !readModelSource.contains("import com.viel.aplayer.data.entity")
        )
        assertTrue(
            "SearchLibraryReadModel must not expose BookWithProgress or BookEntity in its interface.",
            !readModelSource.contains("BookWithProgress") &&
                !readModelSource.contains("BookEntity")
        )
        assertTrue(
            "Search scene interfaces must expose SearchHistoryItem instead of the DataStore SearchHistoryEntry.",
            readModelSource.contains("Flow<List<SearchHistoryItem>>") &&
                commandsSource.contains("deleteSearchHistory(history: SearchHistoryItem)") &&
                !readModelSource.contains("SearchHistoryEntry") &&
                !commandsSource.contains("SearchHistoryEntry")
        )

        guardedUiFiles.forEach { relativePath ->
            val source = sourceRoot.resolve(relativePath).readText()
            assertTrue(
                "$relativePath must not import Room entity packages for search results.",
                !source.contains("import com.viel.aplayer.data.entity")
            )
            assertTrue(
                "$relativePath must not mention Room row types in the search UI contract.",
                !source.contains("BookWithProgress") &&
                    !source.contains("BookEntity")
            )
            assertTrue(
                "$relativePath must not import the DataStore search-history entry.",
                !source.contains("import com.viel.aplayer.data.store.SearchHistoryEntry")
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
            ?: error("Could not locate app source root for search scene architecture test.")
    }
}
