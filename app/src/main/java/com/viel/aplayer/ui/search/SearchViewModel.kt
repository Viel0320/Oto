package com.viel.aplayer.ui.search

import android.app.Application
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.viel.aplayer.APlayerApplication
import com.viel.aplayer.data.entity.BookWithProgress
import com.viel.aplayer.data.store.SearchHistoryEntry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SearchViewModel(application: Application) : AndroidViewModel(application) {
    // Library Presentation Dependency View (Resolve only the facade required by search)
    // SearchViewModel queries books and history through LibraryFacade without learning settings or playback dependencies.
    private val libraryFacade = APlayerApplication.getLibraryPresentationDependencies(application).libraryFacade

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

    // Use libraryFacade facade to responsively observe search history
    val searchHistory: StateFlow<List<SearchHistoryEntry>> = libraryFacade.searchHistory
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    val searchResults: StateFlow<List<BookWithProgress>> = _query
        .map { it.text }
        .flatMapLatest { query ->
            val tokens = query.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (tokens.isEmpty()) {
                flowOf(emptyList())
            } else {
                val knownCommands = listOf("year", "author", "writer", "narrator")
                val allFlows = tokens.map { token ->
                    val parts = token.split(":", limit = 2)
                    if (parts.size == 2 && parts[0].lowercase() in knownCommands) {
                        val command = parts[0].trim().lowercase()
                        val content = parts[1].trim()
                        
                        when (command) {
                            "year" -> if (content.isEmpty()) libraryFacade.audiobooks else libraryFacade.filterByYear(content)
                            "writer", "author" -> if (content.isEmpty()) libraryFacade.audiobooks else libraryFacade.filterByAuthor(content)
                            "narrator" -> if (content.isEmpty()) libraryFacade.audiobooks else libraryFacade.filterByNarrator(content)
                            else -> libraryFacade.audiobooks
                        }
                    } else {
                        libraryFacade.searchAudiobooks(token)
                    }
                }

                combine(allFlows) { lists ->
                    if (lists.isEmpty()) return@combine emptyList()
                    lists.reduce { acc, list ->
                        val accIds = acc.map { it.book.id }.toSet()
                        list.filter { it.book.id in accIds }
                    }
                }
            }
        }
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
                // Use libraryFacade to write search history into persistent records
                libraryFacade.addToHistory(queryToSave.trim())
            }
        }
    }

    fun search(query: String) {
        _query.value = TextFieldValue(query, selection = TextRange(query.length))
        saveSearchHistory(query)
    }

    fun deleteHistory(history: SearchHistoryEntry) {
        viewModelScope.launch {
            // Use libraryFacade to physically delete a single search history item
            libraryFacade.deleteFromHistory(history)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            // Use libraryFacade to clear all historical search keywords with one click.
            libraryFacade.clearHistory()
        }
    }
}
