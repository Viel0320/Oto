package com.viel.aplayer.application.library.home

import com.viel.aplayer.data.db.AudiobookSchema
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit test for HomeBookItem to DetailBookItem mapping logic.
 */
class HomeBookItemMapperTest {

    @Test
    fun testToDetailBookItemMapping() {
        // Create Mock HomeBookItem (Set up dummy data to verify complete boundary mapping fields)
        // All fields from HomeBookItem should correctly propagate to the newly created DetailBookItem instance.
        val homeBook = HomeBookItem(
            id = "test-id",
            rootId = "test-root-id",
            sourceType = AudiobookSchema.SourceType.SINGLE_AUDIO,
            status = AudiobookSchema.BookStatus.READY,
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
            readStatus = AudiobookSchema.ReadStatus.IN_PROGRESS,
            progressPercent = 45,
            lastPlayedAt = 333L,
            isFinished = false,
            isInProgress = true,
            isNotStarted = false
        )

        // Perform Mapping (Convert the HomeBookItem to DetailBookItem)
        // Verifies the extension function successfully translates the data object.
        val detailBook = homeBook.toDetailBookItem()

        // Assert Fields (Validate each mapped field for correctness and equality)
        // Ensures that no fields are lost or corrupted during the layer boundary projection.
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
