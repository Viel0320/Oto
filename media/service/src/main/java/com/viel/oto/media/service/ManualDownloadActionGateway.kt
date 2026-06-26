package com.viel.oto.media.service

/**
 * Executes user commands emitted from manual-download notification actions.
 *
 * Broadcast receivers live in the service module, while download orchestration remains in the app
 * application layer behind this command gateway.
 */
interface ManualDownloadActionGateway {
    suspend fun pauseDownload(bookId: String)

    suspend fun resumeDownload(bookId: String)

    suspend fun retryDownload(bookId: String)

    suspend fun cancelDownload(bookId: String)
}
