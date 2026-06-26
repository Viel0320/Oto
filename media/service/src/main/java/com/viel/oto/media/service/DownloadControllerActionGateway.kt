package com.viel.oto.media.service

import com.viel.oto.application.download.DownloadController

/**
 * Routes manual-download notification actions into the application download command surface.
 *
 * A provider avoids constructing DownloadController while Koin is still creating the download
 * runtime graph for the Media3 DownloadService.
 */
class DownloadControllerActionGateway(
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
