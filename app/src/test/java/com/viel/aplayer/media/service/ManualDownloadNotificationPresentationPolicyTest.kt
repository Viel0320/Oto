package com.viel.aplayer.media.service

import com.viel.aplayer.data.entity.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ManualDownloadNotificationPresentationPolicyTest {
    @Test
    fun `title should include author title and progress percent`() {
        val title = ManualDownloadNotificationPresentationPolicy.title(
            author = "Author",
            bookTitle = "Book Title",
            progressPercent = 42
        )

        // Notification Title Contract (Pin the author-title-progress format requested for book downloads)
        // The three-space separator before progress keeps the title readable in dense Android notification rows.
        assertEquals("Author - Book Title   42%", title)
    }

    @Test
    fun `actions should follow durable download status`() {
        // Notification Action Contract (Keep SystemUI task actions aligned with the in-app management row)
        // This mapping prevents active downloads from losing pause or cancel controls during repeated progress refreshes.
        assertEquals(
            listOf(ManualDownloadNotificationAction.Pause, ManualDownloadNotificationAction.Cancel),
            ManualDownloadNotificationPresentationPolicy.actionsFor(DownloadStatus.QUEUED)
        )
        assertEquals(
            listOf(ManualDownloadNotificationAction.Pause, ManualDownloadNotificationAction.Cancel),
            ManualDownloadNotificationPresentationPolicy.actionsFor(DownloadStatus.DOWNLOADING)
        )
        assertEquals(
            listOf(ManualDownloadNotificationAction.Resume, ManualDownloadNotificationAction.Cancel),
            ManualDownloadNotificationPresentationPolicy.actionsFor(DownloadStatus.PAUSED)
        )
        assertEquals(
            listOf(ManualDownloadNotificationAction.Retry, ManualDownloadNotificationAction.Cancel),
            ManualDownloadNotificationPresentationPolicy.actionsFor(DownloadStatus.FAILED)
        )
        assertEquals(
            emptyList<ManualDownloadNotificationAction>(),
            ManualDownloadNotificationPresentationPolicy.actionsFor(DownloadStatus.COMPLETED)
        )
    }
}
