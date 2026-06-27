package com.viel.oto.abs.sync

import com.viel.oto.abs.net.dto.AbsAudioFileDto
import com.viel.oto.abs.net.dto.AbsItemMediaDto
import com.viel.oto.abs.net.dto.AbsLibraryItemDto
import com.viel.oto.abs.net.dto.AbsMediaMetadataDto
import com.viel.oto.abs.net.dto.AbsTrackDto
import com.viel.oto.abs.net.dto.AbsTrackMetadataDto
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AbsPlayableBookPolicyTest {

    @Test
    fun `abs playable gate must only accept book items with tracks`() {
        val playable = sampleItem(itemId = "item-playable", trackCount = 1)
        val audioFilesOnly = sampleItem(itemId = "item-audiofiles-only", trackCount = 0).copy(
            media = AbsItemMediaDto(
                metadata = AbsMediaMetadataDto(title = "Audio Files Only"),
                tracks = emptyList(),
                audioFiles = listOf(
                    AbsAudioFileDto(
                        ino = "af-1",
                        index = 1,
                        duration = 100.0,
                        size = 1024L,
                        metadata = AbsTrackMetadataDto(
                            filename = "audio-only.mp3",
                            ext = ".mp3",
                            size = 1024L,
                            mtimeMs = 1L
                        )
                    )
                ),
                chapters = emptyList(),
                duration = 100.0,
                size = 1024L
            )
        )
        val podcast = sampleItem(itemId = "item-podcast", trackCount = 1).copy(mediaType = "podcast")

        assertTrue(isAbsPlayableBook(playable))
        assertFalse(isAbsPlayableBook(audioFilesOnly))
        assertFalse(isAbsPlayableBook(podcast))
    }

    private fun sampleItem(itemId: String, trackCount: Int): AbsLibraryItemDto =
        AbsLibraryItemDto(
            id = itemId,
            libraryId = "lib-1",
            mediaType = "book",
            title = "Book",
            updatedAt = 100L,
            media = AbsItemMediaDto(
                metadata = AbsMediaMetadataDto(title = "Book"),
                tracks = (1..trackCount).map { index ->
                    AbsTrackDto(
                        index = index,
                        ino = "track-$index",
                        duration = 100.0,
                        contentUrl = "/api/items/$itemId/file/$index"
                    )
                }
            )
        )
}
