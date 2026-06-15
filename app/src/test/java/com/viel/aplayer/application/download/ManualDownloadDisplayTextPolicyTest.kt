package com.viel.aplayer.application.download

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

        // Progress Label Contract (Pin the shared management and notification title shape)
        // The label starts with percent so users can scan task progress before reading metadata.
        assertEquals("78%   Author - Book Title", label)
    }

    @Test
    fun `progress label should truncate long authors to sixteen characters`() {
        val label = ManualDownloadDisplayTextPolicy.progressBookLabel(
            progressPercent = 78,
            author = "AuthorNameThatIsFarTooLongToFitInHeader",
            bookTitle = "Book Title"
        )

        // Long Author Contract (Keep title visible by compacting the author field first)
        // Sixteen visible author characters include the ellipsis marker used by both notification and management rows.
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

        // Supplemental Label Contract (Keep supporting text focused on compact task state instead of duplicating headline progress)
        // Percent and localized file-unit words belong outside this shared status label, leaving only status, numeric files, and size.
        assertEquals("Paused · 15/45 · 16 MB/60 MB", label)
    }

    @Test
    fun `supplemental label should omit byte range when size metadata is unknown`() {
        val label = ManualDownloadDisplayTextPolicy.taskSupplementalLabel(
            statusText = "Queued",
            completedFiles = 0,
            totalFiles = 3
        )

        // Unknown Size Contract (Avoid placeholder byte text when the transfer total is not available)
        // The compact label still preserves status and numeric file progress for queued or metadata-only tasks.
        assertEquals("Queued · 0/3", label)
    }
}
