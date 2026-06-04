package com.viel.aplayer.media

import com.viel.aplayer.data.entity.BookFileEntity

/**
 * Position Translation Engine (Responsible for mapping between global audiobook positions and file-specific positions)
 * Decouples playback interfaces from multi-track storage offsets by managing coordinate mapping.
 */
object PositionMapper {
    /**
     * Global Position Mapper (Translates global playback offsets to specific file index and internal offsets)
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
        // Boundary Overflow Handling (Returns the end of the last file if the position exceeds the total book duration)
        // Prevents out-of-bounds playback index errors when seeking past the end of the media sequence.
        return if (files.isNotEmpty()) {
            Pair(files.size - 1, files.last().durationMs)
        } else {
            Pair(0, 0L)
        }
    }

    /**
     * File Offset Accumulator (Converts file index and inner offset into a global playback position)
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