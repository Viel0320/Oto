package com.viel.aplayer.ui.viewmodel

import com.viel.aplayer.data.BookmarkEntity
import com.viel.aplayer.data.LibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 书签业务逻辑管理器。
 * 负责书签的增、删、改操作。
 */
class BookmarkManager(
    private val repository: LibraryRepository,
    private val scope: CoroutineScope
) {
    /**
     * 添加书签。
     */
    fun addBookmark(uri: String, position: Long, title: String) {
        scope.launch {
            repository.addBookmark(uri, position, title)
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
            val updatedBookmark = bookmark.copy(
                title = newTitle,
                createdAt = System.currentTimeMillis()
            )
            repository.updateBookmark(updatedBookmark)
        }
    }
}
