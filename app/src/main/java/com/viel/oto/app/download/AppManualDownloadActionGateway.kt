package com.viel.oto.app.download

import com.viel.oto.application.download.DownloadController
import com.viel.oto.media.service.ManualDownloadActionGateway

/**
 * Bridges notification action broadcasts into the application download command surface.
 *
 * A provider is used so the broadcast receiver does not force DownloadController construction while
 * Koin is still creating the download graph.
 */
class AppManualDownloadActionGateway(
    private val downloadControllerProvider: () -> DownloadController
) : ManualDownloadActionGateway {
    override suspend fun pauseDownload(bookId: String) {
        downloadControllerProvider().pauseDownload(bookId)
    }

    override suspend fun resumeDownload(bookId: String) {
        downloadControllerProvider().resumeDownload(bookId)
    }

    override suspend fun retryDownload(bookId: String) {
        downloadControllerProvider().downloadBook(bookId)
    }

    override suspend fun cancelDownload(bookId: String) {
        downloadControllerProvider().cancelDownload(bookId)
    }
}
