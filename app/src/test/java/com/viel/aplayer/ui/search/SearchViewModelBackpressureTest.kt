package com.viel.aplayer.ui.search

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.viel.aplayer.application.library.search.SearchResultSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks query text throttling before search read-model work starts.
 * Verifies rapid text-entry churn is collapsed with virtual time so Room-backed leading-wildcard scans are not started per keypress.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelBackpressureTest {

    @Test
    fun rapidInputsCollapseIntoLastTrimmedDistinctSearch() = runTest {
        val queryFlow = MutableStateFlow(TextFieldValue(""))
        val requestedQueries = mutableListOf<String>()

        val collectionJob = backgroundScope.launch {
            queryFlow.toBackpressuredSearchResults { query ->
                requestedQueries += query
                flowOf(listOf(searchResult(query)))
            }.collect()
        }
        runCurrent()

        queryFlow.value = TextFieldValue("D")
        runCurrent()
        advanceTimeBy(80)

        queryFlow.value = TextFieldValue("Du")
        runCurrent()
        advanceTimeBy(80)

        queryFlow.value = TextFieldValue("Dune")
        runCurrent()
        advanceTimeBy(SEARCH_INPUT_DEBOUNCE_MILLIS - 1)
        runCurrent()

        assertTrue(requestedQueries.isEmpty())

        advanceTimeBy(1)
        runCurrent()

        assertEquals(listOf("Dune"), requestedQueries)

        queryFlow.value = TextFieldValue(" Dune ", selection = TextRange(" Dune ".length))
        runCurrent()
        advanceTimeBy(SEARCH_INPUT_DEBOUNCE_MILLIS)
        runCurrent()

        assertEquals(
            listOf("Dune"),
            requestedQueries
        )

        queryFlow.value = TextFieldValue("Dune Messiah")
        runCurrent()
        advanceTimeBy(SEARCH_INPUT_DEBOUNCE_MILLIS)
        runCurrent()

        assertEquals(listOf("Dune", "Dune Messiah"), requestedQueries)

        collectionJob.cancel()
    }

    private fun searchResult(query: String): SearchResultSnapshot {
        return SearchResultSnapshot(
            id = query.ifBlank { "blank-query" },
            title = query,
            author = "",
            narrator = "",
            totalDurationMs = 0L,
            thumbnailPath = null,
            coverPath = null,
            coverLastUpdated = 0L,
            progressPercent = 0
        )
    }
}
