package com.viel.aplayer.application.download

interface DownloadController {
    /**
     * Download Book (Submit missing remote file requests for one audiobook)
     * Presentation code must complete notification-permission preflight before calling this command.
     */
    suspend fun downloadBook(bookId: String)

    /**
     * Cancel Download (Remove file-level download records and clear durable metadata)
     * Cancel is not a persistent state; a later download command starts from the remaining cache/index state.
     */
    suspend fun cancelDownload(bookId: String)

    /**
     * Pause Download (Apply a per-file stop reason for one book)
     * Book-scoped pause must not stop unrelated downloads; Media3 projects each file into STATE_STOPPED through a non-zero stop reason.
     */
    suspend fun pauseDownload(bookId: String)

    /**
     * Resume Download (Clear per-file stop reasons for one book)
     * The next DownloadIndex callback projects exact file progress while unrelated queued downloads keep their own state.
     */
    suspend fun resumeDownload(bookId: String)

    /**
     * Delete Download (Remove manual cache records and durable metadata for one book)
     * This operation is shared by cancel actions and book deletion cleanup.
     */
    suspend fun deleteDownload(bookId: String)
}

interface ManualDownloadCleanupGateway {
    /**
     * Delete Download (Book deletion cleanup seam for L1 manual cache)
     * Management use cases depend only on this narrow method and never see queue, pause, or statistics operations.
     */
    suspend fun deleteDownload(bookId: String)
}
