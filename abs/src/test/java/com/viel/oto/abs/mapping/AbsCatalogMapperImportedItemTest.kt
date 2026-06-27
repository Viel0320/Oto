package com.viel.oto.abs.mapping

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
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
import org.junit.Assert.assertTrue
import org.junit.Test

class AbsCatalogMapperImportedItemTest {

    private val idMapper = AbsRemoteIdMapper()
    private val mapper = AbsCatalogMapper(idMapper)

    @Test
    fun `catalog mapper should map book tracks and chapters to local entities`() {
        val serverKey = idMapper.serverKey("https://example.com/AudiobookShelf", "user-1")
        val root = absRoot(serverKey)
        val item = sampleItem(
            itemId = "item-1",
            libraryId = "lib-1",
            trackCount = 1,
            chapters = listOf(
                AbsChapterDto(id = 0, title = "Previously on 24", start = 0.0, end = 42.008)
            )
        )

        val book = mapper.toBook(root, serverKey, item, existing = null, syncedAt = 1000L)
        val files = mapper.toFiles(root, serverKey, item)
        val chapters = mapper.toChapters(serverKey, item, files)

        assertEquals(AudiobookSchema.SourceType.ABS_REMOTE, book.sourceType)
        assertEquals("First Fifty Digits of Pi", book.title)
        assertEquals(1, files.size)
        assertEquals("/api/items/item-1/file/856465", files.first().sourcePath)
        assertEquals(0, files.first().index)
        assertEquals(1, chapters.size)
        assertEquals(files.first().id, chapters.first().bookFileId)
        assertEquals(0L, chapters.first().fileOffsetMs)
    }

    @Test
    fun `catalog mapper should preserve remote addedAt for new ABS books and keep local value on resync`() {
        val serverKey = idMapper.serverKey("https://example.com/AudiobookShelf", "user-1")
        val root = absRoot(serverKey)
        val remoteAddedAt = 4_000L
        val existingAddedAt = 2_000L
        val item = sampleItem(
            itemId = "item-recent",
            libraryId = "lib-1",
            addedAt = remoteAddedAt
        )

        val firstSyncBook = mapper.toBook(root, serverKey, item, existing = null, syncedAt = 9_000L)
        val resyncedBook = mapper.toBook(
            root = root,
            serverKey = serverKey,
            item = item.copy(addedAt = 10_000L),
            existing = firstSyncBook.copy(addedAt = existingAddedAt),
            syncedAt = 11_000L
        )

        assertEquals(remoteAddedAt, firstSyncBook.addedAt)
        assertEquals(existingAddedAt, resyncedBook.addedAt)
    }

    @Test
    fun `dto compatibility should tolerate missing tracks size and progress timestamp`() {
        val adapter = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
            .adapter(AbsLibraryItemDto::class.java)
        val root = absRoot(idMapper.serverKey("https://example.com/AudiobookShelf", "user-1"))
        val serverKey = idMapper.serverKey("https://example.com/AudiobookShelf", "user-1")
        val progressMapper = AbsProgressMapper()

        val missingTracksItem = adapter.fromJson(
            """
            {
              "id": "item-no-tracks",
              "libraryId": "lib-1",
              "mediaType": "book",
              "title": "No Tracks",
              "updatedAt": 1,
              "media": {
                "duration": 10.0
              }
            }
            """.trimIndent()
        )!!

        val bookWithoutTracks = mapper.toBook(root, serverKey, missingTracksItem, existing = null, syncedAt = 1234L)
        val filesWithoutTracks = mapper.toFiles(root, serverKey, missingTracksItem)

        assertEquals("No Tracks", bookWithoutTracks.title)
        assertTrue(filesWithoutTracks.isEmpty())

        val missingSizeAndProgressTimeItem = adapter.fromJson(
            """
            {
              "id": "item-partial",
              "libraryId": "lib-1",
              "mediaType": "book",
              "title": "Partial",
              "updatedAt": 2,
              "progress": {
                "currentTime": 2.5,
                "isFinished": false
              },
              "media": {
                "duration": 10.0,
                "tracks": [
                  {
                    "index": 1,
                    "duration": 10.0,
                    "contentUrl": "/api/items/item-partial/file/1",
                    "metadata": {
                      "filename": "partial.mp3"
                    }
                  }
                ]
              }
            }
            """.trimIndent()
        )!!

        val book = mapper.toBook(root, serverKey, missingSizeAndProgressTimeItem, existing = null, syncedAt = 1234L)
        val files = mapper.toFiles(root, serverKey, missingSizeAndProgressTimeItem)
        val progress = progressMapper.toProgressOrNull(missingSizeAndProgressTimeItem, book, files, 1234L)

        assertEquals(1, files.size)
        assertEquals(0L, files.first().fileSize)
        assertEquals(1234L, progress?.lastPlayedAt)
    }

    private fun absRoot(serverKey: String) = LibraryRootEntity(
        id = idMapper.rootId(serverKey, "lib-1"),
        sourceType = AudiobookSchema.LibrarySourceType.ABS,
        sourceUri = "https://example.com/AudiobookShelf",
        basePath = "lib-1",
        credentialId = "cred-1",
        displayName = "Audiobooks"
    )

    private fun sampleItem(
        itemId: String,
        libraryId: String,
        trackCount: Int = 1,
        chapters: List<AbsChapterDto> = listOf(
            AbsChapterDto(id = 0, title = "Chapter 1", start = 0.0, end = 10.0)
        ),
        addedAt: Long = 50L
    ): AbsLibraryItemDto {
        val tracks = (1..trackCount).map { index ->
            val ino = if (index == 1) "856465" else "85646$index"
            AbsTrackDto(
                index = index,
                ino = ino,
                startOffset = (index - 1) * 100.0,
                duration = 100.0,
                contentUrl = "/api/items/$itemId/file/$ino",
                mimeType = "audio/mpeg",
                title = if (index == 1) "First Fifty Digits of Pi" else "Track $index",
                metadata = AbsTrackMetadataDto(
                    filename = if (index == 1) "FirstFiftyDigitsOfPi_librivox.mp3" else "track-$index.mp3",
                    ext = ".mp3",
                    size = 77458087L,
                    mtimeMs = 1732560692887L
                )
            )
        }
        return AbsLibraryItemDto(
            id = itemId,
            libraryId = libraryId,
            mediaType = "book",
            title = "First Fifty Digits of Pi",
            updatedAt = 100L,
            addedAt = addedAt,
            media = AbsItemMediaDto(
                metadata = AbsMediaMetadataDto(
                    title = "First Fifty Digits of Pi",
                    authorName = "Scott Hemphill"
                ),
                tracks = tracks,
                audioFiles = tracks.map { track ->
                    AbsAudioFileDto(
                        ino = track.ino,
                        index = track.index,
                        duration = track.duration,
                        size = 77458087L,
                        metadata = track.metadata
                    )
                },
                chapters = chapters,
                duration = tracks.sumOf { it.duration ?: 0.0 }
            )
        )
    }
}
