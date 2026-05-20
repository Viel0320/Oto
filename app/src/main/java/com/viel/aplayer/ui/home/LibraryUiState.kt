package com.viel.aplayer.ui.home

import com.viel.aplayer.data.entity.BookWithProgress

/**
 * 图书馆主页的 UI 状态聚合类。
 */
data class LibraryUiState(
    /** 书架上的所有书籍列表 */
    val audiobooks: List<BookWithProgress> = emptyList(),
    /** 当前激活的过滤类型 */
    val selectedFilter: HomeFilter = HomeFilter.NotStarted
)