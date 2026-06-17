package com.viel.aplayer.data.book

import com.viel.aplayer.data.dao.BookDao
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.book.BookDeletionGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Book Deletion Service (Implements BookDeletionGateway)
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
