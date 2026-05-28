package com.viel.aplayer.data.gateway

import com.viel.aplayer.data.store.SearchHistoryEntry
import kotlinx.coroutines.flow.Flow

/**
 * 领域解耦的网关接口：专注于有声书历史搜索检索词清册的响应式维护与持久化管理。
 *
 * 核心设计目标：
 * 1. 消除上帝类依赖：为上游搜索 ViewModel 等暴露专门的只读与只写历史搜索记录逻辑。
 * 2. 促进依赖倒置：隔离 Room 搜索数据实体在底层的表现形式。
 */
interface SearchHistoryGateway {

    /**
     * 响应式订阅观察全部检索历史记录列表（按时间倒序排列）。
     */
    val searchHistory: Flow<List<SearchHistoryEntry>>

    /**
     * 将一个新的检索词异步记录或追加合并入搜索历史。
     */
    suspend fun addToHistory(query: String)

    /**
     * 从历史清单中物理删除单个指定的检索词条目。
     */
    suspend fun deleteFromHistory(history: SearchHistoryEntry)

    /**
     * 一键安全清空全部的历史检索词记录。
     */
    suspend fun clearHistory()
}
