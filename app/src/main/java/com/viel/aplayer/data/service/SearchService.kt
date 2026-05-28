package com.viel.aplayer.data.service

import com.viel.aplayer.data.store.SearchHistoryEntry
import com.viel.aplayer.data.store.SearchHistoryStore
import com.viel.aplayer.data.gateway.SearchHistoryGateway
import kotlinx.coroutines.flow.Flow

/**
 * 历史检索清册维护应用服务（实现了 SearchHistoryGateway 网关）。
 *
 * 核心设计目标：
 * 1. 彻底解耦并消灭大仓库：在 M6f 阶段直接直连注入 SearchHistoryStore 检索历史存储，完全摆脱对旧上帝仓库的委托。
 * 2. 完美平移搜索历史的 DataStore 读写及清空：精心平移了对 `addToHistory`、`deleteFromHistory` 以及 `clearHistory` 等核心数据流存取行为。
 */
class SearchService(
    private val searchHistoryStore: SearchHistoryStore
) : SearchHistoryGateway {

    override val searchHistory: Flow<List<SearchHistoryEntry>>
        get() = searchHistoryStore.history

    override suspend fun addToHistory(query: String) {
        // 详尽的中文注释：校验检索词非空后，将其异步以追加合并的方式写入 DataStore 存储
        if (query.isNotBlank()) {
            searchHistoryStore.add(query)
        }
    }

    override suspend fun deleteFromHistory(history: SearchHistoryEntry) {
        // 详尽的中文注释：物理删除指定的搜索历史条目
        searchHistoryStore.delete(history)
    }

    override suspend fun clearHistory() {
        // 详尽的中文注释：一键物理清空 DataStore 中的全部搜索历史清单
        searchHistoryStore.clear()
    }
}
