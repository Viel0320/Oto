package com.viel.aplayer.application.download

import com.viel.aplayer.data.dao.BookDao
import com.viel.aplayer.data.entity.BookFileEntity

interface DownloadBookFileReader {
    /**
     * Download File Inventory (Return the book files eligible for download reconciliation)
     * Implementations can hide Room details while preserving the existing BookDao.getAllFilesForBookList source of truth.
     */
    suspend fun getDownloadFilesForBook(bookId: String): List<BookFileEntity>
}

class RoomDownloadBookFileReader(
    private val bookDao: BookDao
) : DownloadBookFileReader {
    override suspend fun getDownloadFilesForBook(bookId: String): List<BookFileEntity> =
        bookDao.getAllFilesForBookList(bookId)
}
