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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SearchViewModel(application: Application) : AndroidViewModel(application) {
    // Search Scene Dependency View (Resolve only search-specific read and command interfaces)
    // This keeps SearchViewModel on the new scene boundary while preserving the existing AndroidViewModel construction path.
    private val searchDependencies = APlayerApplication.getSearchScreenDependencies(application)
    private val searchLibraryReadModel = searchDependencies.searchLibraryReadModel
    private val searchLibraryCommands = searchDependencies.searchLibraryCommands

    // Visibility Flow (Search Overlay Animation Signal)
    // Reactive state flow controlling whether the SearchOverlay is visible.
    // Triggers slide-in and fade-in animations when true, and slide-out to hide when false.
    private val _isVisible = MutableStateFlow(false)
    val isVisible: StateFlow<Boolean> = _isVisible.asStateFlow()

    // Public interface to modify SearchOverlay visibility state.
    fun setVisible(visible: Boolean) {
        _isVisible.value = visible
    }

    private val _query = MutableStateFlow(TextFieldValue(""))
    val query: StateFlow<TextFieldValue> = _query.asStateFlow()

    // Search History Stream (Expose module-owned history records to the overlay)
    // The read model owns the storage gateway, so the ViewModel only converts it to lifecycle-aware StateFlow.
    val searchHistory: StateFlow<List<SearchHistoryItem>> = searchLibraryReadModel.searchHistory
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    val searchResults: StateFlow<List<SearchResultSnapshot>> = _query
        .map { it.text }
        .flatMapLatest(searchLibraryReadModel::search)
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
                // Search History Save Command (Delegate persistence to the search scene module)
                // The module normalizes input and owns the history gateway, leaving the ViewModel as a UI intent adapter.
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
            // Search History Delete Command (Delegate single-item removal to the search scene module)
            // The command interface receives scene-owned items and hides the DataStore entry shape from UI state.
            searchLibraryCommands.deleteSearchHistory(history)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            // Search History Clear Command (Delegate bulk removal to the search scene module)
            // SearchViewModel no longer needs access to the broader library command surface for this action.
            searchLibraryCommands.clearSearchHistory()
        }
    }
}
