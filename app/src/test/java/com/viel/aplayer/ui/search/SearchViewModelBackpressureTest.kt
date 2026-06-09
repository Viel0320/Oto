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
 * Search ViewModel Backpressure Test (Locks query text throttling before search read-model work starts)
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
                // Search Request Recorder (Capture the read-model boundary call)
                // The fake returns a lightweight result while the assertion focuses on how many query texts reach search().
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
            // Trimmed Distinct Query Guard (Selection and surrounding-space edits must not restart Room work)
            // The same semantic query remains a single read-model request even when TextFieldValue metadata changes.
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
        // Minimal Search Snapshot Fixture (Satisfy the scene read-model return shape)
        // Backpressure behavior only depends on Flow activation, so stable placeholder fields keep the test focused.
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
