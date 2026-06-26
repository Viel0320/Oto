package com.viel.oto.timeline

import com.viel.oto.data.entity.BookFileEntity

/**
 * Responsible for mapping between global audiobook positions and file-specific positions.
 * Decouples playback interfaces from multi-track storage offsets by managing coordinate mapping.
 *
 * Keeps coordinate mapping outside the media runtime package.
 * Data, library, ABS, and playback callers all need this deterministic math, so the mapper lives in a neutral package instead of forcing data code to import media.
 */
object PositionMapper {
    /**
     * Translates global playback offsets to specific file index and internal offsets.
     * Walks through the book file segments to locate the target track and calculates the relative position.
     */
    fun globalToFilePosition(
        globalPositionMs: Long,
        files: List<BookFileEntity>
    ): Pair<Int, Long> {
        var accumulatedMs = 0L
        for ((index, file) in files.withIndex()) {
            if (globalPositionMs < accumulatedMs + file.durationMs) {
                return Pair(index, globalPositionMs - accumulatedMs)
            }
            accumulatedMs += file.durationMs
        }
        return if (files.isNotEmpty()) {
            Pair(files.size - 1, files.last().durationMs)
        } else {
            Pair(0, 0L)
        }
    }

    /**
     * Converts file index and inner offset into a global playback position.
     * Sums the duration of all preceding file tracks and appends the current file's play position.
     */
    fun fileToGlobalPosition(
        fileIndex: Int,
        positionInFileMs: Long,
        files: List<BookFileEntity>
    ): Long {
        var accumulatedMs = 0L
        for (i in 0 until fileIndex.coerceAtMost(files.size)) {
            accumulatedMs += files[i].durationMs
        }
        return accumulatedMs + positionInFileMs
    }
}
