package com.viel.oto.application.download

import com.viel.oto.data.dao.BookDao

interface DownloadBookIdResolver {
    /**
     * Map Media3 DownloadRequest.id back to its parent book.
     * DownloadManager reports file-level changes, while Room metadata is maintained at book level.
     */
    suspend fun getBookIdByFileId(fileId: String): String?
}

class RoomDownloadBookIdResolver(
    private val bookDao: BookDao
) : DownloadBookIdResolver {
    override suspend fun getBookIdByFileId(fileId: String): String? =
        bookDao.getBookIdByFileId(fileId)
}
