package com.viel.aplayer.data.book

import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.ChapterEntity
import com.viel.aplayer.data.entity.ChapterWithBookFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackProjectionChapterTest {

    @Test
    fun `single track without persisted chapters must project one synthetic track chapter`() {
        val book = sampleBook(sourceType = AudiobookSchema.SourceType.SINGLE_AUDIO, totalDurationMs = 120_000L)
        val file = sampleAudioFile(id = "file-1", index = 0, durationMs = 120_000L, displayName = "example.m4b")

        val projected = projectChaptersWithTrackFallback(
            book = book,
            files = listOf(file),
            chapters = emptyList()
        )

        // Single-track synthetic chapter projection. When there are no real chapters for a single track,
        // we must project a single synthetic chapter bound to the physical file anchor so that the player and notifications can share chapter semantics without database writes.
        assertEquals(1, projected.size)
        assertEquals(syntheticTrackProjectionChapterId(book.id, file.id), projected.single().chapter.id)
        assertEquals(book.id, projected.single().chapter.bookId)
        assertEquals(file.id, projected.single().chapter.bookFileId)
        assertEquals(book.title, projected.single().chapter.title)
        assertEquals(0L, projected.single().chapter.startPositionMs)
        assertEquals(120_000L, projected.single().chapter.durationMs)
        assertEquals(AudiobookSchema.ChapterSource.GENERATED, projected.single().chapter.source)
        assertSame(file, projected.single().bookFile)
    }

    @Test
    fun `persisted real chapters must win over synthetic track projection`() {
        val book = sampleBook(sourceType = AudiobookSchema.SourceType.SINGLE_AUDIO)
        val file = sampleAudioFile(id = "file-1", index = 0, durationMs = 60_000L)
        val realChapter = ChapterWithBookFile(
            chapter = ChapterEntity(
                id = "real-chapter",
                bookId = book.id,
                bookFileId = file.id,
                index = 0,
                title = "真实章节",
                startPositionMs = 0L,
                durationMs = 60_000L,
                fileOffsetMs = 0L,
                source = AudiobookSchema.ChapterSource.EMBEDDED
            ),
            bookFile = file
        )

        val projected = projectChaptersWithTrackFallback(
            book = book,
            files = listOf(file),
            chapters = listOf(realChapter)
        )

        // Projection precedence rules. Query projection only generates fallback views when no real chapters exist and must never overwrite parsed chapter facts.
        assertEquals(listOf(realChapter), projected)
    }

    @Test
    fun `abs multi track without persisted chapters must project one chapter per track`() {
        val book = sampleBook(sourceType = AudiobookSchema.SourceType.ABS_REMOTE, title = "ABS 远端书")
        val firstFile = sampleAudioFile(id = "file-1", index = 0, durationMs = 60_000L, displayName = "Part 1.mp3")
        val secondFile = sampleAudioFile(id = "file-2", index = 1, durationMs = 90_000L, displayName = "Part 2.mp3")

        val projected = projectChaptersWithTrackFallback(
            book = book,
            files = listOf(firstFile, secondFile),
            chapters = emptyList()
        )

        // Multi-track synthetic chapter projection. For ABS, if there are no server-side chapters, we must project one chapter per track
        // so that the chapter list, jumping behaviors, and timeline align perfectly with the physical audio tracks.
        assertEquals(2, projected.size)
        assertEquals("Part 1", projected[0].chapter.title)
        assertEquals(0L, projected[0].chapter.startPositionMs)
        assertEquals(60_000L, projected[0].chapter.durationMs)
        assertEquals(firstFile.id, projected[0].chapter.bookFileId)
        assertSame(firstFile, projected[0].bookFile)

        assertEquals("Part 2", projected[1].chapter.title)
        assertEquals(60_000L, projected[1].chapter.startPositionMs)
        assertEquals(90_000L, projected[1].chapter.durationMs)
        assertEquals(secondFile.id, projected[1].chapter.bookFileId)
        assertSame(secondFile, projected[1].bookFile)
    }

    @Test
    fun `projection must stay empty when no audio files exist`() {
        val book = sampleBook(sourceType = AudiobookSchema.SourceType.ABS_REMOTE)

        val projected = projectChaptersWithTrackFallback(
            book = book,
            files = emptyList(),
            chapters = emptyList()
        )

        // Audio file anchor requirement. Synthetic chapter projection strictly depends on valid physical audio files;
        // no chapters should be projected if there are no underlying audio files.
        assertTrue(projected.isEmpty())
    }

    // Update TrackProjectionChapterTest: Change sampleBook helper signature to use type-safe AudiobookSchema.SourceType enum.
    private fun sampleBook(
        sourceType: AudiobookSchema.SourceType,
        totalDurationMs: Long = 0L,
        title: String = "示例有声书"
    ): BookEntity = BookEntity(
        id = "book-1",
        rootId = "root-1",
        sourceType = sourceType,
        title = title,
        totalDurationMs = totalDurationMs
    )

    private fun sampleAudioFile(
        id: String,
        index: Int,
        durationMs: Long,
        displayName: String = "example.m4b"
    ): BookFileEntity = BookFileEntity(
        id = id,
        bookId = "book-1",
        rootId = "root-1",
        index = index,
        sourcePath = "/audio/$displayName",
        sourceIdentity = "identity-$id",
        displayName = displayName,
        durationMs = durationMs,
        fileSize = 1024L,
        lastModified = 0L
    )
}
