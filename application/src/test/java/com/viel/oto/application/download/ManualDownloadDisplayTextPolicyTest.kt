package com.viel.oto.application.download

import org.junit.Assert.assertEquals
import org.junit.Test

class ManualDownloadDisplayTextPolicyTest {
    @Test
    fun `progress label should place percent before author and title`() {
        val label = ManualDownloadDisplayTextPolicy.progressBookLabel(
            progressPercent = 78,
            author = "Author",
            bookTitle = "Book Title"
        )

        assertEquals("78%   Author - Book Title", label)
    }

    @Test
    fun `progress label should truncate long authors to sixteen characters`() {
        val label = ManualDownloadDisplayTextPolicy.progressBookLabel(
            progressPercent = 78,
            author = "AuthorNameThatIsFarTooLongToFitInHeader",
            bookTitle = "Book Title"
        )

        assertEquals("78%   AuthorNam... - Book Title", label)
    }

    @Test
    fun `supplemental label should omit percent and file unit copy`() {
        val label = ManualDownloadDisplayTextPolicy.taskSupplementalLabel(
            statusText = "Paused",
            completedFiles = 15,
            totalFiles = 45,
            downloadedSizeText = "16 MB",
            totalSizeText = "60 MB"
        )

        assertEquals("Paused · 15/45 · 16 MB/60 MB", label)
    }

    @Test
    fun `supplemental label should omit byte range when size metadata is unknown`() {
        val label = ManualDownloadDisplayTextPolicy.taskSupplementalLabel(
            statusText = "Queued",
            completedFiles = 0,
            totalFiles = 3
        )

        assertEquals("Queued · 0/3", label)
    }
}
