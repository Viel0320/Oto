package com.viel.aplayer.data.service

import com.viel.aplayer.data.BookLibraryRepository
import com.viel.aplayer.data.store.SearchHistoryEntry
import com.viel.aplayer.data.gateway.SearchHistoryGateway
import kotlinx.coroutines.flow.Flow

/**
 * 历史检索清册维护应用服务（实现了 SearchHistoryGateway 网关）。
 *
 * 核心设计目标：
 * 1. 增量重构过渡层：在迁移阶段，底层实际仍旧委托给已有的上帝仓库 [BookLibraryRepository]。
 * 2. 方便后续直连 DAO/Store：在未来 M6 阶段，可以直接在该类中去掉对 [BookLibraryRepository] 的引用，改为直接注入 DAO 实体或 SearchHistoryStore。
 */
@Suppress("DEPRECATION")
class SearchService(
    private val bookLibraryRepository: BookLibraryRepository
) : SearchHistoryGateway {

    override val searchHistory: Flow<List<SearchHistoryEntry>>
        get() = bookLibraryRepository.searchHistory

    override suspend fun addToHistory(query: String) {
        bookLibraryRepository.addToHistory(query)
    }

    override suspend fun deleteFromHistory(history: SearchHistoryEntry) {
        bookLibraryRepository.deleteFromHistory(history)
    }

    override suspend fun clearHistory() {
        bookLibraryRepository.clearHistory()
    }
}
