package com.viel.oto.application.download

import com.viel.oto.data.entity.BookFileEntity
import com.viel.oto.logger.DownloadSyncLogger

class DownloadRequestRepairer(
    private val downloadIndexSnapshotReader: DownloadIndexSnapshotReader
) {
    suspend fun findMissingFiles(bookId: String, files: List<BookFileEntity>): List<BookFileEntity> {
        val missing = files.filter { file ->
            downloadIndexSnapshotReader.getSnapshot(file.id) == null
        }
        if (missing.isNotEmpty()) {
            DownloadSyncLogger.logMissingRequestRepair(bookId, missing.size)
        }
        return missing
    }
}
