package com.viel.aplayer.ui.bookmarks

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.viel.aplayer.data.gateway.BookQueryGateway
import com.viel.aplayer.data.entity.BookmarkEntity

/**
 * 书签业务逻辑管理器。
 * 负责书签的增、删、改操作。
 */
class BookmarkManager(
    private val repository: BookQueryGateway,
    private val scope: CoroutineScope
) {
    /**
     * 添加书签。
     */
    fun addBookmark(bookId: String, position: Long, title: String) {
        scope.launch {
            repository.addBookmark(bookId, position, title)
        }
    }

    /**
     * 删除书签。
     */
    fun deleteBookmark(bookmark: BookmarkEntity) {
        scope.launch {
            repository.deleteBookmark(bookmark)
        }
    }

    /**
     * 更新书签标题。
     */
    fun updateBookmark(bookmark: BookmarkEntity, newTitle: String) {
        scope.launch {
            repository.updateBookmark(bookmark.copy(title = newTitle))
        }
    }
}