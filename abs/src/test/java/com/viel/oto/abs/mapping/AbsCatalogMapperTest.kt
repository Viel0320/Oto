package com.viel.oto.abs.mapping

import com.viel.oto.abs.net.AbsApiError
import com.viel.oto.abs.net.dto.AbsAuthorDto
import com.viel.oto.abs.net.dto.AbsAudioFileDto
import com.viel.oto.abs.net.dto.AbsChapterDto
import com.viel.oto.abs.net.dto.AbsItemMediaDto
import com.viel.oto.abs.net.dto.AbsLibraryItemDto
import com.viel.oto.abs.net.dto.AbsMediaMetadataDto
import com.viel.oto.abs.net.dto.AbsTrackDto
import com.viel.oto.abs.net.dto.AbsTrackMetadataDto
import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.LibraryRootEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AbsCatalogMapperTest {

    private val mapper = AbsCatalogMapper(AbsRemoteIdMapper())
    private val serverKey = "srv01"

    private fun root() = LibraryRootEntity(
        id = "abs:$serverKey:library:lib-1",
        sourceType = AudiobookSchema.LibrarySourceType.ABS,
        sourceUri = "https://example.com/AudiobookShelf",
        basePath = "lib-1",
        credentialId = "cred-1",
        displayName = "ABS"
    )

    @Test
    fun `toBook joins multiple author names with comma and skips blanks`() {
        val item = AbsLibraryItemDto(
            id = "item-1",
            media = AbsItemMediaDto(
                metadata = AbsMediaMetadataDto(title = "Title", authorName = "Ignored Fallback"),
                tracks = emptyList()
            ),
            authors = listOf(
                AbsAuthorDto(name = "Alice"),
                AbsAuthorDto(name = "  "),
                AbsAuthorDto(name = null),
                AbsAuthorDto(name = "Bob")
            )
        )

        val book = mapper.toBook(root(), serverKey, item, existing = null, syncedAt = 100L)

        assertEquals("Alice, Bob", book.author)
    }

    @Test
    fun `toBook falls back to metadata authorName when no author list names`() {
        val item = AbsLibraryItemDto(
            id = "item-1",
            media = AbsItemMediaDto(
                metadata = AbsMediaMetadataDto(title = "Title", authorName = "Fallback Author"),
                tracks = emptyList()
            ),
            authors = listOf(AbsAuthorDto(name = "   "))
        )

        val book = mapper.toBook(root(), serverKey, item, existing = null, syncedAt = 100L)

        assertEquals("Fallback Author", book.author)
    }

    @Test
    fun `toBook totalFileSize prefers track metadata sizes`() {
        val item = AbsLibraryItemDto(
            id = "item-1",
            media = AbsItemMediaDto(
                metadata = AbsMediaMetadataDto(title = "Title"),
                tracks = listOf(
                    AbsTrackDto(index = 0, duration = 10.0, contentUrl = "/api/a", metadata = AbsTrackMetadataDto(size = 30L)),
                    AbsTrackDto(index = 1, duration = 10.0, contentUrl = "/api/b", metadata = AbsTrackMetadataDto(size = 70L))
                ),
                audioFiles = listOf(AbsAudioFileDto(index = 0, size = 999L))
            )
        )

        val book = mapper.toBook(root(), serverKey, item, existing = null, syncedAt = 100L)

        assertEquals(100L, book.totalFileSize)
    }

    @Test
    fun `toBook totalFileSize falls back to audioFiles when track sizes absent`() {
        val item = AbsLibraryItemDto(
            id = "item-1",
            media = AbsItemMediaDto(
                metadata = AbsMediaMetadataDto(title = "Title"),
                tracks = listOf(
                    AbsTrackDto(index = 0, duration = 10.0, contentUrl = "/api/a", metadata = null),
                    AbsTrackDto(index = 1, duration = 10.0, contentUrl = "/api/b", metadata = AbsTrackMetadataDto(size = 0L))
                ),
                audioFiles = listOf(
                    AbsAudioFileDto(index = 0, size = 40L),
                    AbsAudioFileDto(index = 1, size = 60L)
                )
            )
        )

        val book = mapper.toBook(root(), serverKey, item, existing = null, syncedAt = 100L)

        assertEquals(100L, book.totalFileSize)
    }

    @Test
    fun `toBook totalDuration falls back to summed track durations when media duration missing`() {
        val item = AbsLibraryItemDto(
            id = "item-1",
            media = AbsItemMediaDto(
                metadata = AbsMediaMetadataDto(title = "Title"),
                duration = null,
                tracks = listOf(
                    AbsTrackDto(index = 0, duration = 1.5, contentUrl = "/api/a"),
                    AbsTrackDto(index = 1, duration = 2.5, contentUrl = "/api/b")
                )
            )
        )

        val book = mapper.toBook(root(), serverKey, item, existing = null, syncedAt = 100L)

        assertEquals(4_000L, book.totalDurationMs)
    }

    @Test
    fun `toBook throws MALFORMED_ITEM when id missing`() {
        val item = AbsLibraryItemDto(id = null, media = AbsItemMediaDto(metadata = AbsMediaMetadataDto(title = "Title")))

        val error = assertThrows(AbsApiError::class.java) {
            mapper.toBook(root(), serverKey, item, existing = null, syncedAt = 100L)
        }
        assertEquals("MALFORMED_ITEM", error.code)
    }

    @Test
    fun `toFiles throws MALFORMED_ITEM when track contentUrl missing`() {
        val item = AbsLibraryItemDto(
            id = "item-1",
            media = AbsItemMediaDto(
                metadata = AbsMediaMetadataDto(title = "Title"),
                tracks = listOf(AbsTrackDto(index = 0, duration = 10.0, contentUrl = null))
            )
        )

        val error = assertThrows(AbsApiError::class.java) {
            mapper.toFiles(root(), serverKey, item)
        }
        assertEquals("MALFORMED_ITEM", error.code)
    }

    @Test
    fun `toChapters uses accumulated prior durations as track start when startOffset absent`() {
        // Two tracks, no startOffset: track0 span [0,10000), track1 span [10000,30000)
        val item = AbsLibraryItemDto(
            id = "item-1",
            media = AbsItemMediaDto(
                metadata = AbsMediaMetadataDto(title = "Title"),
                tracks = listOf(
                    AbsTrackDto(index = 0, duration = 10.0, contentUrl = "/api/a"),
                    AbsTrackDto(index = 1, duration = 20.0, contentUrl = "/api/b")
                ),
                chapters = listOf(
                    AbsChapterDto(id = 0, title = "Ch1", start = 0.0, end = 10.0),
                    AbsChapterDto(id = 1, title = "Ch2", start = 15.0, end = 30.0)
                )
            )
        )
        val files = mapper.toFiles(root(), serverKey, item)

        val chapters = mapper.toChapters(serverKey, item, files)

        assertEquals(2, chapters.size)
        // Ch1 at 0ms -> belongs to track0's file
        assertEquals(files[0].id, chapters[0].bookFileId)
        // Ch2 at 15000ms -> falls in track1 span [10000,30000) -> track1's file
        assertEquals(files[1].id, chapters[1].bookFileId)
        // fileOffsetMs = startMs - spanStart: 15000 - 10000 = 5000
        assertEquals(5_000L, chapters[1].fileOffsetMs)
        assertEquals(15_000L, chapters[1].startPositionMs)
        assertEquals(15_000L, chapters[1].durationMs)
    }

    @Test
    fun `toChapters falls back to last span when chapter start is beyond all tracks`() {
        val item = AbsLibraryItemDto(
            id = "item-1",
            media = AbsItemMediaDto(
                metadata = AbsMediaMetadataDto(title = "Title"),
                tracks = listOf(
                    AbsTrackDto(index = 0, duration = 10.0, contentUrl = "/api/a"),
                    AbsTrackDto(index = 1, duration = 10.0, contentUrl = "/api/b")
                ),
                // chapter start 100s = 100000ms is beyond span end 20000 -> last() fallback
                chapters = listOf(AbsChapterDto(id = 0, title = "Far", start = 100.0, end = 110.0))
            )
        )
        val files = mapper.toFiles(root(), serverKey, item)

        val chapters = mapper.toChapters(serverKey, item, files)

        assertEquals(1, chapters.size)
        assertEquals(files.last().id, chapters[0].bookFileId)
    }

    @Test
    fun `toChapters honors explicit startOffset over accumulated durations`() {
        val item = AbsLibraryItemDto(
            id = "item-1",
            media = AbsItemMediaDto(
                metadata = AbsMediaMetadataDto(title = "Title"),
                tracks = listOf(
                    AbsTrackDto(index = 0, startOffset = 0.0, duration = 10.0, contentUrl = "/api/a"),
                    // explicit startOffset 50s shifts track1 span to [50000, 60000)
                    AbsTrackDto(index = 1, startOffset = 50.0, duration = 10.0, contentUrl = "/api/b")
                ),
                chapters = listOf(AbsChapterDto(id = 0, title = "Ch", start = 55.0, end = 60.0))
            )
        )
        val files = mapper.toFiles(root(), serverKey, item)

        val chapters = mapper.toChapters(serverKey, item, files)

        assertEquals(files[1].id, chapters[0].bookFileId)
        assertEquals(5_000L, chapters[0].fileOffsetMs)
    }

    @Test
    fun `toChapters returns empty when no tracks`() {
        val item = AbsLibraryItemDto(
            id = "item-1",
            media = AbsItemMediaDto(
                metadata = AbsMediaMetadataDto(title = "Title"),
                tracks = emptyList(),
                chapters = listOf(AbsChapterDto(id = 0, title = "Ch", start = 0.0, end = 10.0))
            )
        )
        // files empty -> early return
        val chapters = mapper.toChapters(serverKey, item, files = emptyList())
        assertTrue(chapters.isEmpty())
    }

    @Test
    fun `toChapters throws MALFORMED_ITEM when id missing but tracks and files present`() {
        val withId = AbsLibraryItemDto(
            id = "item-1",
            media = AbsItemMediaDto(
                metadata = AbsMediaMetadataDto(title = "Title"),
                tracks = listOf(AbsTrackDto(index = 0, duration = 10.0, contentUrl = "/api/a"))
            )
        )
        val files = mapper.toFiles(root(), serverKey, withId)
        val noId = withId.copy(id = null)

        val error = assertThrows(AbsApiError::class.java) {
            mapper.toChapters(serverKey, noId, files)
        }
        assertEquals("MALFORMED_ITEM", error.code)
    }
}
