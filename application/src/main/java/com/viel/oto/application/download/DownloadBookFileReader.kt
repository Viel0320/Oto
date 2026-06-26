package com.viel.oto.application.download

import com.viel.oto.data.dao.BookDao
import com.viel.oto.data.entity.BookFileEntity

interface DownloadBookFileReader {
    /**
     * Return the book files eligible for download reconciliation.
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
