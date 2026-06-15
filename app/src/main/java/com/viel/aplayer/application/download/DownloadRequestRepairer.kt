package com.viel.aplayer.application.download

import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.logger.DownloadSyncLogger

class DownloadRequestRepairer(
    private val downloadIndexSnapshotReader: DownloadIndexSnapshotReader
) {
    // Missing Request Detection (Find file-level download requests absent from Media3 DownloadIndex)
    // Recovery uses this to resubmit only missing files instead of duplicating requests already known by Media3.
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
