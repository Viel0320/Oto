package com.viel.aplayer.ui.search

import android.app.Application
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import com.viel.aplayer.data.LibraryRepository
import com.viel.aplayer.data.entity.BookWithProgress
import com.viel.aplayer.data.store.SearchHistoryEntry

class SearchViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = LibraryRepository.getInstance(application)

    private val _query = MutableStateFlow(TextFieldValue(""))
    val query: StateFlow<TextFieldValue> = _query.asStateFlow()

    val searchHistory: StateFlow<List<SearchHistoryEntry>> = repository.searchHistory
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
                            "year" -> if (content.isEmpty()) repository.audiobooks else repository.filterByYear(content)
                            "writer", "author" -> if (content.isEmpty()) repository.audiobooks else repository.filterByAuthor(content)
                            "narrator" -> if (content.isEmpty()) repository.audiobooks else repository.filterByNarrator(content)
                            else -> repository.audiobooks
                        }
                    } else {
                        repository.searchAudiobooks(token)
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
                repository.addToHistory(queryToSave.trim())
            }
        }
    }

    fun search(query: String) {
        _query.value = TextFieldValue(query, selection = TextRange(query.length))
        saveSearchHistory(query)
    }

    fun deleteHistory(history: SearchHistoryEntry) {
        viewModelScope.launch {
            repository.deleteFromHistory(history)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            repository.clearHistory()
        }
    }
}