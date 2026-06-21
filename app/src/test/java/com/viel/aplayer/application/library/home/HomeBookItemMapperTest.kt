package com.viel.aplayer.application.library.home

import com.viel.aplayer.application.library.LibraryBookSourceType
import com.viel.aplayer.application.library.LibraryBookStatus
import com.viel.aplayer.application.library.LibraryReadStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit test for HomeBookItem to DetailBookItem mapping logic.
 */
class HomeBookItemMapperTest {

    @Test
    fun testToDetailBookItemMapping() {
        val homeBook = HomeBookItem(
            id = "test-id",
            rootId = "test-root-id",
            sourceType = LibraryBookSourceType.SINGLE_AUDIO,
            status = LibraryBookStatus.READY,
            title = "Test Title",
            author = "Test Author",
            narrator = "Test Narrator",
            description = "Test Description",
            year = "2026",
            series = "Test Series",
            totalDurationMs = 12345L,
            totalFileSize = 67890L,
            coverPath = "path/to/cover",
            thumbnailPath = "path/to/thumbnail",
            lastScannedAt = 111L,
            addedAt = 222L,
            readStatus = LibraryReadStatus.IN_PROGRESS,
            progressPercent = 45,
            lastPlayedAt = 333L,
            isFinished = false,
            isInProgress = true,
            isNotStarted = false
        )

        val detailBook = homeBook.toDetailBookItem()

        assertEquals(homeBook.id, detailBook.id)
        assertEquals(homeBook.rootId, detailBook.rootId)
        assertEquals(homeBook.sourceType, detailBook.sourceType)
        assertEquals(homeBook.title, detailBook.title)
        assertEquals(homeBook.author, detailBook.author)
        assertEquals(homeBook.narrator, detailBook.narrator)
        assertEquals(homeBook.description, detailBook.description)
        assertEquals(homeBook.year, detailBook.year)
        assertEquals(homeBook.totalDurationMs, detailBook.totalDurationMs)
        assertEquals(homeBook.totalFileSize, detailBook.totalFileSize)
        assertEquals(homeBook.coverPath, detailBook.coverPath)
        assertEquals(homeBook.thumbnailPath, detailBook.thumbnailPath)
        assertEquals(homeBook.lastScannedAt, detailBook.lastScannedAt)
        assertEquals(homeBook.progressPercent, detailBook.progressPercent)
        assertEquals(homeBook.readStatus, detailBook.readStatus)
    }
}
