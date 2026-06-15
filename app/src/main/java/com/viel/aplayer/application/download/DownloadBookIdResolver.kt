package com.viel.aplayer.application.download

import com.viel.aplayer.data.dao.BookDao

interface DownloadBookIdResolver {
    /**
     * Resolve Book Id For File (Map Media3 DownloadRequest.id back to its parent book)
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
