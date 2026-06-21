package com.viel.aplayer.application.library.search

import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookWithProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks search directive parsing outside the ViewModel.
 * Verifies blank queries, directive routing, plain keyword search, and multi-token id intersection.
 */
class SearchQueryPlannerTest {

    @Test
    fun blankQueryReturnsEmptyListWithoutCallingSearchFunctions() = runBlocking {
        val calls = mutableListOf<String>()
        val planner = plannerWithRecorder(calls = calls)

        val results = planner.search("   ").first()

        assertTrue(results.isEmpty())
        assertTrue(calls.isEmpty())
    }

    @Test
    fun ordinaryTokenCallsFuzzyAudiobookSearch() = runBlocking {
        val calls = mutableListOf<String>()
        val planner = plannerWithRecorder(calls = calls)

        planner.search("megachurch").first()

        assertEquals(listOf("search:megachurch"), calls)
    }

    @Test
    fun yearDirectiveCallsYearFilter() = runBlocking {
        val calls = mutableListOf<String>()
        val planner = plannerWithRecorder(calls = calls)

        planner.search("year:2024").first()

        assertEquals(listOf("year:2024"), calls)
    }

    @Test
    fun authorWriterAndNarratorDirectivesDispatchToMatchingFilters() = runBlocking {
        val calls = mutableListOf<String>()
        val planner = plannerWithRecorder(calls = calls)

        planner.search("author:Asai").first()
        planner.search("writer:Ryo").first()
        planner.search("narrator:Kana").first()

        assertEquals(
            listOf(
                "author:Asai",
                "author:Ryo",
                "narrator:Kana"
            ),
            calls
        )
    }

    @Test
    fun multiTokenQueryIntersectsResultsByBookId() = runBlocking {
        val calls = mutableListOf<String>()
        val firstOnly = book("first-only")
        val shared = book("shared")
        val secondOnly = book("second-only")
        val planner = plannerWithRecorder(
            calls = calls,
            searchResults = mapOf(
                "first" to listOf(firstOnly, shared),
                "second" to listOf(shared, secondOnly)
            )
        )

        val results = planner.search("first second").first()

        assertEquals(listOf("shared"), results.map { it.book.id })
        assertEquals(listOf("search:first", "search:second"), calls)
    }

    private fun plannerWithRecorder(
        calls: MutableList<String>,
        searchResults: Map<String, List<BookWithProgress>> = emptyMap()
    ): SearchQueryPlanner {
        fun record(type: String, query: String): Flow<List<BookWithProgress>> {
            calls += "$type:$query"
            return flowOf(searchResults[query] ?: listOf(book("$type-$query")))
        }

        return SearchQueryPlanner(
            audiobooks = flowOf(listOf(book("all"))),
            searchAudiobooks = { query -> record("search", query) },
            filterByYear = { year -> record("year", year) },
            filterByAuthor = { author -> record("author", author) },
            filterByNarrator = { narrator -> record("narrator", narrator) }
        )
    }

    private fun book(id: String): BookWithProgress {
        return BookWithProgress(
            book = BookEntity(
                id = id,
                rootId = "root",
                sourceType = AudiobookSchema.SourceType.SINGLE_AUDIO,
                title = id
            ),
            progress = null
        )
    }
}
