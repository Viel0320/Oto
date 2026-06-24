package com.viel.oto.media.service

import com.viel.oto.data.entity.DownloadStatus
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

        assertEquals("42%   Author - Book Title", title)
    }

    @Test
    fun `title should compact very long author before book title`() {
        val title = ManualDownloadNotificationPresentationPolicy.title(
            author = "AuthorNameThatIsFarTooLongToFitInHeader",
            bookTitle = "Book Title",
            progressPercent = 78
        )

        assertEquals("78%   AuthorNam... - Book Title", title)
    }

    @Test
    fun `actions should follow durable download status`() {
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
