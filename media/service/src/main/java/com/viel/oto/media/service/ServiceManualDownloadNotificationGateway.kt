package com.viel.oto.media.service

import com.viel.oto.application.download.ManualDownloadNotificationGateway
import com.viel.oto.data.entity.DownloadMetadataEntity

/**
 * Adapts the Android manual-download notification renderer to the application gateway contract.
 *
 * The renderer remains service-owned, while application orchestration depends only on its stable
 * notification gateway interface.
 */
class ServiceManualDownloadNotificationGateway(
    private val delegate: AndroidManualDownloadNotificationGateway
) : ManualDownloadNotificationGateway {
    override suspend fun publish(metadata: DownloadMetadataEntity) {
        delegate.publish(metadata)
    }

    override suspend fun cancel(bookId: String) {
        delegate.cancel(bookId)
    }
}
