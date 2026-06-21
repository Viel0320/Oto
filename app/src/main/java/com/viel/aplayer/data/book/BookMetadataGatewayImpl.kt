package com.viel.aplayer.data.book

import com.viel.aplayer.data.dao.BookDao
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.logger.SecureLog
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable

/**
 * Implements BookMetadataGateway.
 *
 * Owns semantic metadata writes (read status, edited details, background tag updates). [updateMetadata] is
 * fire-and-forget on a private IO scope, so this service is [Closeable] and must be torn down by the graph.
 */
class BookMetadataGatewayImpl(
    private val bookDao: BookDao
) : BookMetadataGateway, Closeable {

    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        SecureLog.error("BookMetadataGatewayImpl", "协程在 BookMetadataGatewayImpl 运行中捕获到未处理异常", exception)
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)

    override suspend fun updateBookReadStatus(bookId: String, readStatus: AudiobookSchema.ReadStatus) = withContext(Dispatchers.IO) {
        bookDao.updateBookReadStatus(bookId, readStatus)
    }

    override suspend fun updateBookDetails(
        id: String,
        title: String,
        author: String,
        narrator: String,
        description: String,
        year: String,
        series: String
    ) = withContext(Dispatchers.IO) {
        bookDao.updateBookDetails(id, title, author, narrator, description, year, series)
    }

    override fun updateMetadata(
        bookId: String,
        title: String?,
        author: String?,
        narrator: String?,
        description: String?,
        duration: Long
    ) {
        scope.launch {
            val existing = bookDao.getBookById(bookId) ?: return@launch
            val newTitle = if (!title.isNullOrBlank()) title else existing.title
            val newAuthor = if (!author.isNullOrBlank()) author else existing.author
            val newNarrator = if (!narrator.isNullOrBlank()) narrator else existing.narrator
            val newDescription = if (!description.isNullOrBlank()) description else existing.description
            val newDuration = if (duration > 0) duration else existing.totalDurationMs

            if (newTitle != existing.title || newAuthor != existing.author ||
                newNarrator != existing.narrator || newDescription != existing.description ||
                newDuration != existing.totalDurationMs) {
                bookDao.updateMetadata(bookId, newTitle, newAuthor, newNarrator, newDescription, newDuration)
            }
        }
    }

    override fun close() {
        scope.cancel()
    }
}
