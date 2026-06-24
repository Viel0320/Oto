package com.viel.oto.data.book

import com.viel.oto.data.dao.BookDao
import com.viel.oto.data.db.AudiobookSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Implements BookDeletionGateway.
 *
 * Performs a logical soft delete by flipping status to DELETED rather than erasing records, so a later
 * rescan does not re-import the same book as a duplicate.
 */
class BookDeletionGatewayImpl(
    private val bookDao: BookDao
) : BookDeletionGateway {

    override suspend fun deleteBook(bookId: String) = withContext(Dispatchers.IO) {
        val book = bookDao.getBookById(bookId)
        if (book != null) {
            bookDao.updateBookStatus(bookId, AudiobookSchema.BookStatus.DELETED)
        }
    }
}
