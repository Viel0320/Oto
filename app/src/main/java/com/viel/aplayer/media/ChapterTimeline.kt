package com.viel.aplayer.media

import com.viel.aplayer.data.entity.ChapterEntity

/**
 * Shared chapter boundary logic for UI, notifications, and sleep timer.
 */
object ChapterTimeline {
    fun sorted(chapters: List<ChapterEntity>): List<ChapterEntity> =
        chapters.sortedWith(compareBy<ChapterEntity> { it.startPositionMs }.thenBy { it.index })

    fun currentChapter(chapters: List<ChapterEntity>, globalPositionMs: Long): ChapterEntity? {
        val sorted = sorted(chapters)
        return sorted.findLast { globalPositionMs >= it.startPositionMs } ?: sorted.firstOrNull()
    }

    fun currentIndex(chapters: List<ChapterEntity>, chapter: ChapterEntity?): Int {
        if (chapter == null) return -1
        return sorted(chapters).indexOfFirst { it.id == chapter.id }
    }

    fun start(chapter: ChapterEntity?): Long = chapter?.startPositionMs ?: 0L

    fun end(chapters: List<ChapterEntity>, chapter: ChapterEntity?, totalDurationMs: Long): Long {
        if (chapter == null) return totalDurationMs.coerceAtLeast(0L)
        val sorted = sorted(chapters)
        // Prefer chapter start boundaries; embedded duration metadata is often inconsistent in single-file books.
        val nextStart = sorted.firstOrNull { it.startPositionMs > chapter.startPositionMs }?.startPositionMs
        val metadataEnd = if (chapter.durationMs > 0L) chapter.startPositionMs + chapter.durationMs else null
        val fallbackEnd = nextStart ?: totalDurationMs.takeIf { it > 0L } ?: metadataEnd ?: chapter.startPositionMs
        // When total duration is unavailable, keep the metadata fallback instead of clamping it back to chapter start.
        val upperBound = if (totalDurationMs > 0L) {
            totalDurationMs.coerceAtLeast(chapter.startPositionMs)
        } else {
            (metadataEnd ?: fallbackEnd).coerceAtLeast(chapter.startPositionMs)
        }
        return fallbackEnd.coerceIn(chapter.startPositionMs, upperBound)
    }

    fun duration(chapters: List<ChapterEntity>, chapter: ChapterEntity?, totalDurationMs: Long): Long =
        (end(chapters, chapter, totalDurationMs) - start(chapter)).coerceAtLeast(1L)

    fun positionInChapter(chapters: List<ChapterEntity>, chapter: ChapterEntity?, globalPositionMs: Long, totalDurationMs: Long): Long =
        (globalPositionMs - start(chapter)).coerceIn(0L, duration(chapters, chapter, totalDurationMs))
}