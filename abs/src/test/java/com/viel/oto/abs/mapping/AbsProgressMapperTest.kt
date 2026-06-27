package com.viel.oto.abs.mapping

import com.viel.oto.abs.net.dto.AbsItemMediaDto
import com.viel.oto.abs.net.dto.AbsLibraryItemDto
import com.viel.oto.abs.net.dto.AbsMediaMetadataDto
import com.viel.oto.abs.net.dto.AbsTrackDto
import com.viel.oto.abs.net.dto.AbsUserProgressDto
import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.BookEntity
import com.viel.oto.data.entity.BookFileEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AbsProgressMapperTest {

    private val mapper = AbsProgressMapper()

    @Test
    fun `progress mapper should convert seconds to millis and skip missing progress`() {
        val book = BookEntity(
            id = "book-1",
            rootId = "root-1",
            sourceType = AudiobookSchema.SourceType.ABS_REMOTE,
            title = "Book"
        )
        val files = listOf(
            BookFileEntity(
                id = "file-1",
                bookId = book.id,
                rootId = "root-1",
                index = 0,
                sourcePath = "/api/items/item-1/file/1",
                sourceIdentity = "track-1",
                displayName = "track.mp3",
                durationMs = 100_000L,
                fileSize = 1L,
                lastModified = 0L
            )
        )
        val itemWithProgress = sampleItem(
            itemId = "item-1",
            progress = AbsUserProgressDto(currentTime = 12.5, isFinished = false, lastUpdate = 999L)
        )

        val progress = mapper.toProgressOrNull(itemWithProgress, book, files, 1000L)

        assertNotNull(progress)
        assertEquals(12500L, progress?.globalPositionMs)
        assertEquals(999L, progress?.lastPlayedAt)
        assertNull(mapper.toProgressOrNull(sampleItem(itemId = "item-2", progress = null), book, files, 1000L))
    }

    private fun sampleItem(itemId: String, progress: AbsUserProgressDto?): AbsLibraryItemDto =
        AbsLibraryItemDto(
            id = itemId,
            libraryId = "lib-1",
            mediaType = "book",
            title = "Book",
            updatedAt = 100L,
            media = AbsItemMediaDto(
                metadata = AbsMediaMetadataDto(title = "Book"),
                tracks = listOf(AbsTrackDto(index = 1, duration = 100.0, contentUrl = "/api/items/$itemId/file/1"))
            ),
            progress = progress
        )
}
