package com.viel.oto.timeline

import com.viel.oto.data.entity.BookFileEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Boundary and branch coverage for [PositionMapper].
 *
 * The mapper performs deterministic coordinate math between a global audiobook timeline and the
 * per-file offsets backing it. These tests pin down the segment boundary ownership (the comparison
 * is a strict `<`, so an exact boundary belongs to the following file), the empty-list fallbacks,
 * the past-end clamp and the [fileIndex] coerceAtMost truncation.
 */
class PositionMapperTest {

    // --- globalToFilePosition -------------------------------------------------------------------

    @Test
    fun `globalToFilePosition on empty list returns zero pair`() {
        assertEquals(Pair(0, 0L), PositionMapper.globalToFilePosition(0L, emptyList()))
        assertEquals(Pair(0, 0L), PositionMapper.globalToFilePosition(5_000L, emptyList()))
    }

    @Test
    fun `globalToFilePosition resolves a position inside the first file`() {
        val files = files(1_000L, 2_000L, 3_000L)
        assertEquals(Pair(0, 0L), PositionMapper.globalToFilePosition(0L, files))
        assertEquals(Pair(0, 500L), PositionMapper.globalToFilePosition(500L, files))
        assertEquals(Pair(0, 999L), PositionMapper.globalToFilePosition(999L, files))
    }

    @Test
    fun `globalToFilePosition resolves a position inside a middle file`() {
        val files = files(1_000L, 2_000L, 3_000L)
        // accumulated for file 1 is 1000; range [1000, 3000)
        assertEquals(Pair(1, 0L), PositionMapper.globalToFilePosition(1_000L, files))
        assertEquals(Pair(1, 1_000L), PositionMapper.globalToFilePosition(2_000L, files))
        assertEquals(Pair(1, 1_999L), PositionMapper.globalToFilePosition(2_999L, files))
    }

    @Test
    fun `globalToFilePosition resolves a position inside the last file`() {
        val files = files(1_000L, 2_000L, 3_000L)
        // accumulated for file 2 is 3000; range [3000, 6000)
        assertEquals(Pair(2, 0L), PositionMapper.globalToFilePosition(3_000L, files))
        assertEquals(Pair(2, 2_999L), PositionMapper.globalToFilePosition(5_999L, files))
    }

    @Test
    fun `globalToFilePosition treats an exact boundary as belonging to the next file`() {
        val files = files(1_000L, 2_000L, 3_000L)
        // 1000 is exactly the end of file 0; strict < means it belongs to file 1 at offset 0.
        assertEquals(Pair(1, 0L), PositionMapper.globalToFilePosition(1_000L, files))
        // 3000 is exactly the end of file 1; belongs to file 2 at offset 0.
        assertEquals(Pair(2, 0L), PositionMapper.globalToFilePosition(3_000L, files))
    }

    @Test
    fun `globalToFilePosition past the total duration clamps to the last file end`() {
        val files = files(1_000L, 2_000L, 3_000L)
        // total = 6000; exactly the total and beyond both clamp to (lastIndex, last.durationMs).
        assertEquals(Pair(2, 3_000L), PositionMapper.globalToFilePosition(6_000L, files))
        assertEquals(Pair(2, 3_000L), PositionMapper.globalToFilePosition(10_000L, files))
    }

    @Test
    fun `globalToFilePosition with a single file`() {
        val files = files(1_000L)
        assertEquals(Pair(0, 0L), PositionMapper.globalToFilePosition(0L, files))
        assertEquals(Pair(0, 999L), PositionMapper.globalToFilePosition(999L, files))
        // boundary/past-end clamp to (0, durationMs)
        assertEquals(Pair(0, 1_000L), PositionMapper.globalToFilePosition(1_000L, files))
        assertEquals(Pair(0, 1_000L), PositionMapper.globalToFilePosition(5_000L, files))
    }

    // --- fileToGlobalPosition -------------------------------------------------------------------

    @Test
    fun `fileToGlobalPosition on empty list returns the in-file position`() {
        // fileIndex coerced to 0, no accumulation -> just the offset.
        assertEquals(0L, PositionMapper.fileToGlobalPosition(0, 0L, emptyList()))
        assertEquals(0L, PositionMapper.fileToGlobalPosition(3, 0L, emptyList()))
        assertEquals(750L, PositionMapper.fileToGlobalPosition(0, 750L, emptyList()))
    }

    @Test
    fun `fileToGlobalPosition for the first file adds no preceding duration`() {
        val files = files(1_000L, 2_000L, 3_000L)
        assertEquals(0L, PositionMapper.fileToGlobalPosition(0, 0L, files))
        assertEquals(500L, PositionMapper.fileToGlobalPosition(0, 500L, files))
    }

    @Test
    fun `fileToGlobalPosition for a middle file sums preceding durations`() {
        val files = files(1_000L, 2_000L, 3_000L)
        // preceding file 0 = 1000
        assertEquals(1_000L, PositionMapper.fileToGlobalPosition(1, 0L, files))
        assertEquals(1_500L, PositionMapper.fileToGlobalPosition(1, 500L, files))
        // preceding files 0 + 1 = 3000
        assertEquals(3_000L, PositionMapper.fileToGlobalPosition(2, 0L, files))
        assertEquals(3_250L, PositionMapper.fileToGlobalPosition(2, 250L, files))
    }

    @Test
    fun `fileToGlobalPosition coerces an over-large index to the full sum`() {
        val files = files(1_000L, 2_000L, 3_000L)
        // fileIndex == files.size sums every duration (1000+2000+3000 = 6000)
        assertEquals(6_000L, PositionMapper.fileToGlobalPosition(3, 0L, files))
        // anything larger is coerced down to files.size, yielding the same full sum
        assertEquals(6_500L, PositionMapper.fileToGlobalPosition(99, 500L, files))
    }

    @Test
    fun `fileToGlobalPosition round trips with globalToFilePosition`() {
        val files = files(1_000L, 2_000L, 3_000L)
        val global = 4_200L
        val (index, offset) = PositionMapper.globalToFilePosition(global, files)
        assertEquals(global, PositionMapper.fileToGlobalPosition(index, offset, files))
    }

    private fun files(vararg durationsMs: Long): List<BookFileEntity> =
        durationsMs.mapIndexed { index, durationMs ->
            BookFileEntity(
                id = "file-$index",
                bookId = "book-1",
                rootId = "root-1",
                index = index,
                sourcePath = "/audio/file-$index.mp3",
                sourceIdentity = "identity-$index",
                displayName = "file-$index.mp3",
                durationMs = durationMs,
                fileSize = 1_024L,
                lastModified = 0L
            )
        }
}
