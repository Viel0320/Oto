package com.viel.aplayer.application.library.search

import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookProgressEntity
import com.viel.aplayer.data.entity.BookWithProgress
import com.viel.aplayer.data.gateway.SearchHistoryGateway
import com.viel.aplayer.data.store.SearchHistoryEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Search Library Module Test (Locks history command delegation at the scene boundary)
 * Verifies the module exposes history and filters history writes before reaching the persistence gateway.
 */
class SearchLibraryModuleTest {

    @Test
    fun searchHistoryIsExposedFromGateway() = runBlocking {
        val history = listOf(SearchHistoryEntry(query = "compose", timestamp = 1L))
        val gateway = FakeSearchHistoryGateway(history)
        val module = moduleFor(gateway)

        assertEquals(
            // Expected History Projection (Assert the scene item shape instead of the DataStore entry)
            // This locks the Search module as the only place that maps persistence timestamps into createdAt.
            listOf(SearchHistoryItem(query = "compose", createdAt = 1L)),
            module.searchHistory.first()
        )
    }

    @Test
    fun searchProjectsPlannerRowsIntoSceneSnapshots() = runBlocking {
        val gateway = FakeSearchHistoryGateway()
        val module = DefaultSearchLibraryModule(
            searchHistoryGateway = gateway,
            queryPlanner = SearchQueryPlanner(
                audiobooks = flowOf(emptyList()),
                searchAudiobooks = {
                    flowOf(
                        listOf(
                            book(
                                id = "book-id",
                                title = "Projected",
                                author = "Author",
                                narrator = "Narrator",
                                totalDurationMs = 200L,
                                thumbnailPath = "thumb.jpg",
                                coverPath = "cover.jpg",
                                lastScannedAt = 42L,
                                progressMs = 50L
                            )
                        )
                    )
                },
                filterByYear = { flowOf(emptyList()) },
                filterByAuthor = { flowOf(emptyList()) },
                filterByNarrator = { flowOf(emptyList()) }
            )
        )

        val results = module.search("projected").first()

        assertEquals(
            listOf(
                SearchResultSnapshot(
                    id = "book-id",
                    title = "Projected",
                    author = "Author",
                    narrator = "Narrator",
                    totalDurationMs = 200L,
                    thumbnailPath = "thumb.jpg",
                    coverPath = "cover.jpg",
                    coverLastUpdated = 42L,
                    progressPercent = 25
                )
            ),
            results
        )
    }

    @Test
    fun saveSearchHistoryIgnoresBlankInput() = runBlocking {
        val gateway = FakeSearchHistoryGateway()
        val module = moduleFor(gateway)

        module.saveSearchHistory("   ")

        assertTrue(gateway.addedQueries.isEmpty())
    }

    @Test
    fun saveSearchHistoryTrimsBeforeDelegating() = runBlocking {
        val gateway = FakeSearchHistoryGateway()
        val module = moduleFor(gateway)

        module.saveSearchHistory("  megachurch  ")

        assertEquals(listOf("megachurch"), gateway.addedQueries)
    }

    @Test
    fun deleteSearchHistoryDelegatesToGateway() = runBlocking {
        val gateway = FakeSearchHistoryGateway()
        val module = moduleFor(gateway)
        val entry = SearchHistoryItem(query = "old", createdAt = 2L)

        module.deleteSearchHistory(entry)

        assertEquals(listOf("old"), gateway.deletedQueries)
    }

    @Test
    fun clearSearchHistoryDelegatesToGateway() = runBlocking {
        val gateway = FakeSearchHistoryGateway()
        val module = moduleFor(gateway)

        module.clearSearchHistory()

        assertEquals(1, gateway.clearCount)
    }

    private fun moduleFor(gateway: FakeSearchHistoryGateway): DefaultSearchLibraryModule {
        // Module Fixture (Supplies a deterministic planner because these tests target history behavior)
        // The planner still returns a valid Flow so read-model construction remains complete.
        return DefaultSearchLibraryModule(
            searchHistoryGateway = gateway,
            queryPlanner = SearchQueryPlanner(
                audiobooks = flowOf(emptyList()),
                searchAudiobooks = { query -> flowOf(listOf(book(query))) },
                filterByYear = { flowOf(emptyList()) },
                filterByAuthor = { flowOf(emptyList()) },
                filterByNarrator = { flowOf(emptyList()) }
            )
        )
    }

    private fun book(
        id: String,
        title: String = id,
        author: String = "",
        narrator: String = "",
        totalDurationMs: Long = 0L,
        thumbnailPath: String? = null,
        coverPath: String? = null,
        lastScannedAt: Long = 0L,
        progressMs: Long? = null
    ): BookWithProgress {
        // Search Row Fixture (Supplies gateway-native data for adapter projection tests)
        // The module test can assert scene snapshot mapping while keeping Room row construction inside the fake planner.
        return BookWithProgress(
            book = BookEntity(
                id = id,
                rootId = "root",
                // Update SearchLibraryModuleTest: Change sourceType in helper to use type-safe AudiobookSchema.SourceType.SINGLE_AUDIO enum.
                sourceType = AudiobookSchema.SourceType.SINGLE_AUDIO,
                title = title,
                author = author,
                narrator = narrator,
                totalDurationMs = totalDurationMs,
                thumbnailPath = thumbnailPath,
                coverPath = coverPath,
                lastScannedAt = lastScannedAt
            ),
            progress = progressMs?.let { position ->
                BookProgressEntity(bookId = id, globalPositionMs = position)
            }
        )
    }

    private class FakeSearchHistoryGateway(
        initialHistory: List<SearchHistoryEntry> = emptyList()
    ) : SearchHistoryGateway {
        val addedQueries = mutableListOf<String>()
        val deletedQueries = mutableListOf<String>()
        var clearCount = 0

        override val searchHistory: Flow<List<SearchHistoryEntry>> = MutableStateFlow(initialHistory)

        override suspend fun addToHistory(query: String) {
            addedQueries += query
        }

        override suspend fun deleteFromHistory(query: String) {
            // Fake History Delete Capture (Mirror the production query-only deletion seam)
            // Tests verify that scene history items are translated to stable query text before reaching persistence.
            deletedQueries += query
        }

        override suspend fun clearHistory() {
            clearCount += 1
        }
    }
}
