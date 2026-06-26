package com.viel.oto.media.manifest

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestParserBudgetTest {

    @Test
    fun `m3u8 parser should cap oversized playlist entries`() = runBlocking {
        val result = M3u8ManifestParser.parse(
            displayName = "oversized.m3u8",
            openStream = { buildM3u8EntryPayload(itemCount = 50_000).byteInputStream(Charsets.UTF_8) }
        )

        assertEquals(10_000, result.items.size)
        assertEquals("track-10000.mp3", result.items.last().uri)
        assertEquals("Track 10000", result.items.last().title)
    }

    @Test
    fun `m3u8 parser should cap oversized metadata and item text`() = runBlocking {
        val oversizedText = "A".repeat(50_000)
        val result = M3u8ManifestParser.parse(
            displayName = "oversized-text.m3u8",
            openStream = {
                buildSingleM3u8TextPayload(oversizedText).byteInputStream(Charsets.UTF_8)
            }
        )

        assertEquals(8_192, result.metadata.title?.length)
        assertEquals(8_192, result.items.single().title?.length)
        assertEquals(8_192, result.items.single().uri.length)
    }

    @Test
    fun `cue parser should cap oversized referenced files and chapters`() = runBlocking {
        val result = CueManifestParser.parse(
            displayName = "oversized.cue",
            openStream = { buildCueEntryPayload(itemCount = 50_000).byteInputStream(Charsets.UTF_8) }
        )

        assertEquals(10_000, result?.referencedFiles?.size)
        assertEquals(10_000, result?.chapters?.size)
        assertEquals("track-10000.mp3", result?.referencedFiles?.last())
        assertEquals("Track 10000", result?.chapters?.last()?.title)
    }

    @Test
    fun `cue parser should cap oversized metadata chapter and path text`() = runBlocking {
        val oversizedText = "B".repeat(50_000)
        val result = CueManifestParser.parse(
            displayName = "oversized-text.cue",
            openStream = { buildSingleCueTextPayload(oversizedText).byteInputStream(Charsets.UTF_8) }
        )

        assertTrue(result?.metadata?.title?.length == 8_192)
        assertTrue(result?.metadata?.author?.length == 8_192)
        assertTrue(result?.metadata?.narrator?.length == 8_192)
        assertTrue(result?.metadata?.year?.length == 8_192)
        assertTrue(result?.metadata?.description?.length == 8_192)
        assertEquals(8_192, result?.referencedFiles?.single()?.length)
        assertEquals(8_192, result?.chapters?.single()?.title?.length)
        assertEquals(8_192, result?.chapters?.single()?.fileKey?.length)
    }

    /**
     * Creates a deterministic high-item playlist input.
     *
     * Builds the same user-controlled sidecar shape used by playlist imports while avoiding filesystem fixtures.
     */
    private fun buildM3u8EntryPayload(itemCount: Int): String =
        buildString {
            append("#EXTM3U\n")
            for (index in 1..itemCount) {
                append("#EXTINF:1,Track ").append(index).append('\n')
                append("track-").append(index).append(".mp3\n")
            }
        }

    /**
     * Creates one valid playlist item with pathological strings.
     *
     * Exercises metadata, EXTINF title, and local path clipping separately from the item-count budget.
     */
    private fun buildSingleM3u8TextPayload(text: String): String =
        buildString {
            append("#EXTM3U\n")
            append("#PLAYLIST:").append(text).append('\n')
            append("#EXTINF:1,").append(text).append('\n')
            append(text).append(".mp3\n")
        }

    /**
     * Creates deterministic file and chapter growth.
     *
     * Repeats FILE/TRACK/INDEX groups so the parser exercises both referenced-file and chapter collection budgets.
     */
    private fun buildCueEntryPayload(itemCount: Int): String =
        buildString {
            for (index in 1..itemCount) {
                append("FILE \"track-").append(index).append(".mp3\" MP3\n")
                append("  TRACK 01 AUDIO\n")
                append("    TITLE \"Track ").append(index).append("\"\n")
                append("    INDEX 01 00:00:00\n")
            }
        }

    /**
     * Creates one valid CUE entry with pathological strings.
     *
     * Exercises global metadata, file path, and chapter-title clipping independently from collection-size limits.
     */
    private fun buildSingleCueTextPayload(text: String): String =
        buildString {
            append("TITLE \"").append(text).append("\"\n")
            append("PERFORMER \"").append(text).append("\"\n")
            append("COMPOSER \"").append(text).append("\"\n")
            append("REM DATE \"").append(text).append("\"\n")
            append("REM COMMENT \"").append(text).append("\"\n")
            append("FILE \"").append(text).append(".mp3\" MP3\n")
            append("  TRACK 01 AUDIO\n")
            append("    TITLE \"").append(text).append("\"\n")
            append("    INDEX 01 00:00:00\n")
        }
}
