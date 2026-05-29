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
    // 在 M5b.2 迁移中，将旧仓库 LibraryRepository 替换为高层业务门面 libraryFacade。
    // 这解除了 ViewModel 与庞大重量级底层的直接强耦合，将所有操作直接委托路由给分域 Gateway 服务。
    private val libraryFacade = (application as APlayerApplication).container.libraryFacade

    // 
    // 控制同 Activity 内非独立搜索悬浮层 (SearchOverlay) 是否可见的响应式状态流。
    // 设为 true 时展现滑入与渐显动画，设为 false 时进行滑出隐藏。
    private val _isVisible = MutableStateFlow(false)
    val isVisible: StateFlow<Boolean> = _isVisible.asStateFlow()

    // 
    // 提供修改非独立搜索悬浮层 (SearchOverlay) 可见状态的公共接口。
    fun setVisible(visible: Boolean) {
        _isVisible.value = visible
    }

    private val _query = MutableStateFlow(TextFieldValue(""))
    val query: StateFlow<TextFieldValue> = _query.asStateFlow()

    // 使用 libraryFacade 门面响应式观察搜索检索历史
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
                // 使用 libraryFacade 将检索历史写入持久化记录
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
            // 使用 libraryFacade 物理删除单个搜索历史条目
            libraryFacade.deleteFromHistory(history)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            // 使用 libraryFacade 一键清理全部历史检索词
            libraryFacade.clearHistory()
        }
    }
}