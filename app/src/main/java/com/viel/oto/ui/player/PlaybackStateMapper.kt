package com.viel.oto.ui.player

import com.viel.oto.application.library.player.PlayerChapterItem
import com.viel.oto.application.library.player.PlayerChapterTimeline
import kotlin.math.ceil

/**
 * PlaybackStateMapper - Dedicated state mapper for playback status and progress calculations.
 *
 * This component is designed to physically decouple high-frequency progress mapping,
 * chapter conversions, and percentage mathematical logic that were originally concentrated in PlayerViewModel,
 * achieving logical "dehydration" and single-responsibility of the ViewModel,
 * making the code structure more decoupled and easier to unit test independently.
 */
object PlaybackStateMapper {

    /**
     * range: 0 - 100. based on the current absolute playback position and total duration.
     *
     * @param currentPosition The currently played duration (in milliseconds).
     * @param duration The total duration of the book (in milliseconds).
     * @return The calculated and rounded-up percentage progress.
     */
    fun calculateProgressPercent(currentPosition: Long, duration: Long): Int {
        return if (duration > 0) {
            ceil(currentPosition.toDouble() / duration.toDouble() * 100)
                .toInt()
                .coerceIn(0, 100)
        } else {
            0
        }
    }

    /**
     * range: 0.0f - 1.0f. for mini-player rendering.
     * isChapterMode. is active.
     *
     * @param currentPosition The absolute position of the current player (in milliseconds).
     * @param duration The total duration of the book (in milliseconds).
     * @param chapters The list of physical chapters contained in the book.
     * @param isChapterMode Whether the display is in "chapter progress" mode.
     * @param fallbackProgress The fallback global relative progress.
     * @return The converted high-precision floating-point progress ratio.
     */
    fun calculateMiniPlayerProgress(
        currentPosition: Long,
        duration: Long,
        chapters: List<PlayerChapterItem>,
        isChapterMode: Boolean,
        fallbackProgress: Float
    ): Float {
        return if (isChapterMode && chapters.isNotEmpty()) {
            val currentChapter = PlayerChapterTimeline.currentChapter(chapters, currentPosition)
            val posInChapter = PlayerChapterTimeline.positionInChapter(
                chapters, currentChapter, currentPosition, duration
            )
            val chapterDuration = PlayerChapterTimeline.duration(
                chapters, currentChapter, duration
            )
            if (chapterDuration > 0) {
                posInChapter.toFloat() / chapterDuration.toFloat()
            } else {
                0f
            }
        } else {
            fallbackProgress
        }
    }

    /**
     * Calculate and retrieve the chapter entity currently playing based on the global absolute playback position.
     *
     * @param chapters The list of chapter information.
     * @param position The absolute position of the current player (in milliseconds).
     * @return The matched chapter entity, or null if no match is found.
     */
    fun currentChapter(chapters: List<PlayerChapterItem>, position: Long): PlayerChapterItem? {
        return PlayerChapterTimeline.currentChapter(chapters, position)
    }
}
