package com.viel.aplayer.application.download

import com.viel.aplayer.data.entity.DownloadMetadataEntity

interface ManualDownloadNotificationGateway {
    /**
     * Project a durable book aggregate into a user-visible notification.
     * Download sync owns aggregate freshness, while concrete Android notification rendering stays behind this gateway.
     */
    suspend fun publish(metadata: DownloadMetadataEntity)

    /**
     * Remove one book-level notification when the manual task leaves Room.
     * Deletion and Media3 removal callbacks both route through this boundary so stale task notifications do not survive.
     */
    suspend fun cancel(bookId: String)

    object NoOp : ManualDownloadNotificationGateway {
        override suspend fun publish(metadata: DownloadMetadataEntity) = Unit
        override suspend fun cancel(bookId: String) = Unit
    }
}
