package com.viel.oto.app.download

import com.viel.oto.application.download.ManualDownloadNotificationGateway
import com.viel.oto.data.entity.DownloadMetadataEntity
import com.viel.oto.media.service.AndroidManualDownloadNotificationGateway

/**
 * Adapts the extracted Android notification renderer to the application download gateway.
 *
 * The application layer keeps its existing gateway contract; the concrete Android renderer now lives
 * in `:media:service` and is delegated through this app-side adapter.
 */
class AppManualDownloadNotificationGateway(
    private val delegate: AndroidManualDownloadNotificationGateway
) : ManualDownloadNotificationGateway {
    override suspend fun publish(metadata: DownloadMetadataEntity) {
        delegate.publish(metadata)
    }

    override suspend fun cancel(bookId: String) {
        delegate.cancel(bookId)
    }
}
