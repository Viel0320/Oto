package com.viel.aplayer.ui.search

import android.app.Application
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.viel.aplayer.APlayerApplication
import com.viel.aplayer.application.library.search.SearchHistoryItem
import com.viel.aplayer.application.library.search.SearchResultSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

internal const val SEARCH_INPUT_DEBOUNCE_MILLIS = 250L

class SearchViewModel(application: Application) : AndroidViewModel(application) {
    private val searchDependencies = APlayerApplication.getSearchScreenDependencies(application)
    private val searchLibraryReadModel = searchDependencies.searchLibraryReadModel
    private val searchLibraryCommands = searchDependencies.searchLibraryCommands

    private val _isVisible = MutableStateFlow(false)
    val isVisible: StateFlow<Boolean> = _isVisible.asStateFlow()

    fun setVisible(visible: Boolean) {
        _isVisible.value = visible
    }

    private val _query = MutableStateFlow(TextFieldValue(""))
    val query: StateFlow<TextFieldValue> = _query.asStateFlow()

    val searchHistory: StateFlow<List<SearchHistoryItem>> = searchLibraryReadModel.searchHistory
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val searchResults: StateFlow<List<SearchResultSnapshot>> = _query
        .toBackpressuredSearchResults(searchLibraryReadModel::search)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun onQueryChange(newQuery: TextFieldValue) {
        _query.value = newQuery
    }

    fun clearQuery() {
        _query.value = TextFieldValue("")
    }

    fun saveSearchHistory(queryToSave: String) {
        if (queryToSave.isNotBlank()) {
            viewModelScope.launch {
                searchLibraryCommands.saveSearchHistory(queryToSave)
            }
        }
    }

    fun search(query: String) {
        _query.value = TextFieldValue(query, selection = TextRange(query.length))
        saveSearchHistory(query)
    }

    fun deleteHistory(history: SearchHistoryItem) {
        viewModelScope.launch {
            searchLibraryCommands.deleteSearchHistory(history)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            searchLibraryCommands.clearSearchHistory()
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
internal fun Flow<TextFieldValue>.toBackpressuredSearchResults(
    search: (String) -> Flow<List<SearchResultSnapshot>>
): Flow<List<SearchResultSnapshot>> {
    return map { fieldValue -> fieldValue.text.trim() }
        .debounce(SEARCH_INPUT_DEBOUNCE_MILLIS.milliseconds)
        .distinctUntilChanged()
        .flatMapLatest(search)
}
