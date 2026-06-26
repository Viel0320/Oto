package com.viel.oto.media

import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.ChapterEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Branch/boundary coverage for [ChapterTimeline].
 *
 * Real API under test (operates on List<ChapterEntity>):
 *   sorted, currentChapter, currentIndex, start, end, duration, positionInChapter.
 *
 * Expected values were derived by stepping through end()'s nextStart / metadataEnd /
 * fallbackEnd / upperBound branches and the coerce clamps.
 */
class ChapterTimelineTest {

    private fun chapter(
        id: String,
        index: Int,
        startPositionMs: Long,
        durationMs: Long = 0L
    ): ChapterEntity = ChapterEntity(
        id = id,
        bookId = "book-1",
        bookFileId = "file-1",
        index = index,
        title = id,
        startPositionMs = startPositionMs,
        durationMs = durationMs,
        fileOffsetMs = 0L,
        source = AudiobookSchema.ChapterSource.GENERATED
    )

    // Three contiguous chapters: starts 0/1000/3000, total 6000.
    private val ch0 = chapter("c0", index = 0, startPositionMs = 0L, durationMs = 1000L)
    private val ch1 = chapter("c1", index = 1, startPositionMs = 1000L, durationMs = 2000L)
    private val ch2 = chapter("c2", index = 2, startPositionMs = 3000L, durationMs = 3000L)
    private val all = listOf(ch0, ch1, ch2)
    private val total = 6000L

    // ---- sorted --------------------------------------------------------------

    @Test
    fun `sorted orders by start then index`() {
        val a = chapter("a", index = 5, startPositionMs = 1000L)
        val b = chapter("b", index = 2, startPositionMs = 1000L)
        val c = chapter("c", index = 0, startPositionMs = 0L)
        val sorted = ChapterTimeline.sorted(listOf(a, b, c))
        assertEquals(listOf("c", "b", "a"), sorted.map { it.id })
    }

    // ---- currentChapter ------------------------------------------------------

    @Test
    fun `currentChapter on empty list is null`() {
        assertNull(ChapterTimeline.currentChapter(emptyList(), 5_000L))
    }

    @Test
    fun `currentChapter at exact boundary picks the chapter starting there`() {
        // pos 1000 matches ch0(0) and ch1(1000); findLast -> ch1.
        assertEquals("c1", ChapterTimeline.currentChapter(all, 1000L)?.id)
    }

    @Test
    fun `currentChapter inside an interval picks the enclosing chapter`() {
        assertEquals("c1", ChapterTimeline.currentChapter(all, 1500L)?.id)
    }

    @Test
    fun `currentChapter before first start falls back to first chapter`() {
        // -100 >= 0 is false for every chapter, so fall back to firstOrNull.
        assertEquals("c0", ChapterTimeline.currentChapter(all, -100L)?.id)
    }

    @Test
    fun `currentChapter past the end stays on the last chapter`() {
        assertEquals("c2", ChapterTimeline.currentChapter(all, 1_000_000L)?.id)
    }

    // ---- currentIndex --------------------------------------------------------

    @Test
    fun `currentIndex of null chapter is minus one`() {
        assertEquals(-1, ChapterTimeline.currentIndex(all, null))
    }

    @Test
    fun `currentIndex finds chapter by id`() {
        assertEquals(1, ChapterTimeline.currentIndex(all, ch1))
    }

    @Test
    fun `currentIndex of unknown chapter is minus one`() {
        val unknown = chapter("nope", index = 9, startPositionMs = 9000L)
        assertEquals(-1, ChapterTimeline.currentIndex(all, unknown))
    }

    // ---- start ---------------------------------------------------------------

    @Test
    fun `start of null chapter is zero`() {
        assertEquals(0L, ChapterTimeline.start(null))
    }

    @Test
    fun `start returns chapter start`() {
        assertEquals(1000L, ChapterTimeline.start(ch1))
    }

    // ---- end -----------------------------------------------------------------

    @Test
    fun `end of null chapter clamps total to zero floor`() {
        assertEquals(6000L, ChapterTimeline.end(all, null, total))
        assertEquals(0L, ChapterTimeline.end(all, null, -100L))
    }

    @Test
    fun `end uses next chapter start when one follows`() {
        // ch0: nextStart = 1000 -> end = 1000.
        assertEquals(1000L, ChapterTimeline.end(all, ch0, total))
    }

    @Test
    fun `end of last chapter falls back to total duration`() {
        // ch2: no next start, total>0 -> fallbackEnd = total = 6000.
        assertEquals(6000L, ChapterTimeline.end(all, ch2, total))
    }

    @Test
    fun `end of single chapter without total uses metadata duration`() {
        // nextStart null, total<=0, metadataEnd present (start0 + dur5000).
        val solo = chapter("solo", index = 0, startPositionMs = 0L, durationMs = 5000L)
        assertEquals(5000L, ChapterTimeline.end(listOf(solo), solo, 0L))
    }

    @Test
    fun `end of single chapter without total and without metadata uses chapter start`() {
        // nextStart null, total<=0, metadataEnd null (dur 0) -> fallbackEnd = start = 2000.
        val solo = chapter("solo", index = 0, startPositionMs = 2000L, durationMs = 0L)
        assertEquals(2000L, ChapterTimeline.end(listOf(solo), solo, 0L))
    }

    // ---- duration ------------------------------------------------------------

    @Test
    fun `duration is end minus start`() {
        assertEquals(1000L, ChapterTimeline.duration(all, ch0, total))
        assertEquals(2000L, ChapterTimeline.duration(all, ch1, total))
        assertEquals(3000L, ChapterTimeline.duration(all, ch2, total))
    }

    @Test
    fun `duration is clamped to at least one millisecond`() {
        // Zero-width chapter (start == end) -> (0).coerceAtLeast(1) = 1.
        val solo = chapter("solo", index = 0, startPositionMs = 2000L, durationMs = 0L)
        assertEquals(1L, ChapterTimeline.duration(listOf(solo), solo, 0L))
    }

    @Test
    fun `duration of null chapter spans whole total`() {
        assertEquals(6000L, ChapterTimeline.duration(all, null, total))
    }

    // ---- positionInChapter ---------------------------------------------------

    @Test
    fun `positionInChapter returns offset within the chapter`() {
        // ch1 start 1000, pos 1500 -> 500, clamped to [0, 2000].
        assertEquals(500L, ChapterTimeline.positionInChapter(all, ch1, 1500L, total))
    }

    @Test
    fun `positionInChapter clamps positions before the chapter to zero`() {
        assertEquals(0L, ChapterTimeline.positionInChapter(all, ch1, 0L, total))
    }

    @Test
    fun `positionInChapter clamps positions past the chapter to its duration`() {
        // ch1 duration 2000 -> upper clamp.
        assertEquals(2000L, ChapterTimeline.positionInChapter(all, ch1, 1_000_000L, total))
    }

    @Test
    fun `positionInChapter at the exact start is zero`() {
        assertEquals(0L, ChapterTimeline.positionInChapter(all, ch1, 1000L, total))
    }

    @Test
    fun `positionInChapter for null chapter measures from zero`() {
        assertEquals(1500L, ChapterTimeline.positionInChapter(all, null, 1500L, total))
    }
}
