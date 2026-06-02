package com.viel.aplayer.data.service

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

        // 详尽的中文注释：单 track 无真实章节时，查询投影必须补出唯一一条 track 章节，
        // 并继续绑定真实文件锚点，这样播放器和通知层都能在不写库的前提下共享章节语义。
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

        // 详尽的中文注释：查询投影只负责在“无真实章节”时补视图，绝不能覆盖真实解析得到的章节事实。
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

        // 详尽的中文注释：ABS 即使没有服务端章节，也必须按 track 投影出两条章节，
        // 这样多 track 书的章节列表、章节跳转和章节模式时间轴都能与真实音频分轨保持一致。
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

        // 详尽的中文注释：查询投影必须严格依赖真实音频文件锚点；若没有任何文件，就不能凭空生成章节。
        assertTrue(projected.isEmpty())
    }

    private fun sampleBook(
        sourceType: String,
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
