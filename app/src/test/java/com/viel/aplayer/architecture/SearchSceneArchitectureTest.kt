package com.viel.aplayer.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the stage-one search dependency migration.
 * Prevents SearchViewModel from drifting back to the broad presentation facade.
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
            "SearchViewModel must use the search scene read model and commands.",
            searchViewModelSource.contains("searchLibraryReadModel") &&
                searchViewModelSource.contains("searchLibraryCommands")
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
        val candidates = listOf(
            File("src/main/java/com/viel/aplayer"),
            File("app/src/main/java/com/viel/aplayer")
        )
        return candidates.firstOrNull { candidate -> candidate.isDirectory }
            ?: error("Could not locate app source root for search scene architecture test.")
    }
}
