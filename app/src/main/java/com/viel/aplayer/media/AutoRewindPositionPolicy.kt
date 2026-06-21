package com.viel.aplayer.media

import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.BookProgressEntity
import com.viel.aplayer.timeline.PositionMapper

/**
 * Centralizes rewind target calculations without owning Android playback state.
 * Converts current playback or persisted progress positions into deterministic rewind targets while keeping settings, controllers, and database writes in the manager layer.
 */
object AutoRewindPositionPolicy {
    /**
     * Describes the controller seek operation produced by auto rewind.
     * Multi-file plans carry a media item index, while single-track fallback plans seek only within the current item.
     */
    data class PlaybackSeekTarget(
        val mediaItemIndex: Int?,
        val positionMs: Long,
        val globalPositionMs: Long
    )

    /**
     * Maps a live controller position to the target seek coordinate.
     * Multi-file books rewind on the global audiobook timeline so a pause near a track boundary can cross into the previous file.
     */
    fun playbackSeekTarget(
        currentMediaItemIndex: Int,
        currentPositionMs: Long,
        rewindMs: Long,
        files: List<BookFileEntity>
    ): PlaybackSeekTarget {
        val safeRewindMs = rewindMs.coerceAtLeast(0L)
        if (files.isEmpty()) {
            val targetPositionMs = (currentPositionMs - safeRewindMs).coerceAtLeast(0L)
            return PlaybackSeekTarget(
                mediaItemIndex = null,
                positionMs = targetPositionMs,
                globalPositionMs = targetPositionMs
            )
        }

        val fileIndex = currentMediaItemIndex.coerceIn(0, files.lastIndex)
        val positionInFileMs = currentPositionMs.coerceAtLeast(0L)
        val currentGlobalPositionMs = PositionMapper.fileToGlobalPosition(fileIndex, positionInFileMs, files)
        val targetGlobalPositionMs = (currentGlobalPositionMs - safeRewindMs).coerceAtLeast(0L)
        val (targetFileIndex, targetPositionInFileMs) = PositionMapper.globalToFilePosition(targetGlobalPositionMs, files)
        return PlaybackSeekTarget(
            mediaItemIndex = targetFileIndex,
            positionMs = targetPositionInFileMs,
            globalPositionMs = targetGlobalPositionMs
        )
    }

    /**
     * Applies cold-start rewind to stored progress anchors.
     * When file rows are available, the global target is remapped to a stable file anchor; otherwise only the global position is adjusted to preserve existing fallback behavior.
     */
    fun rewoundProgress(
        progress: BookProgressEntity,
        rewindMs: Long,
        files: List<BookFileEntity>,
        now: Long
    ): BookProgressEntity {
        val targetGlobalPositionMs = (progress.globalPositionMs - rewindMs.coerceAtLeast(0L)).coerceAtLeast(0L)
        if (files.isEmpty()) {
            return progress.copy(
                globalPositionMs = targetGlobalPositionMs,
                lastPlayedAt = now
            )
        }

        val (targetFileIndex, targetPositionInFileMs) = PositionMapper.globalToFilePosition(targetGlobalPositionMs, files)
        return progress.copy(
            globalPositionMs = targetGlobalPositionMs,
            bookFileId = files.getOrNull(targetFileIndex)?.id,
            currentFileIndex = targetFileIndex,
            positionInFileMs = targetPositionInFileMs,
            lastPlayedAt = now
        )
    }
}
