package com.viel.aplayer.ui.player

import com.viel.aplayer.data.entity.ChapterEntity
import com.viel.aplayer.media.ChapterTimeline
import kotlin.math.ceil

/**
 * 专门用于播放状态与进度计算的状态映射器（PlaybackStateMapper）。
 * 本组件旨在将原本高度集中在 PlayerViewModel 中的高频进度映射、章节折算及百分比数学逻辑进行物理剥离，
 * 实现 ViewModel 的逻辑“脱水”与单一职责化，使代码结构更加解耦且便于单独进行单元测试。
 */
object PlaybackStateMapper {

    /**
     * 根据当前播放的绝对位置与总时长，计算全局进度的整型百分比比例（范围：0 - 100）。
     *
     * @param currentPosition 当前已播放时长（毫秒）
     * @param duration 书籍总时长（毫秒）
     * @return 返回计算并向上取整后的百分比进度
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
     * 计算供迷你播放器渲染的进度比例（范围：0.0f - 1.0f）。
     * 内部能自主根据是否开启章节进度模式（isChapterMode）自动决定返回章节内相对进度或全局物理进度。
     *
     * @param currentPosition 当前播放器的绝对位置（毫秒）
     * @param duration 书籍的总时长（毫秒）
     * @param chapters 书籍包含的物理章节列表
     * @param isChapterMode 是否处于“按章节进度显示”的模式
     * @param fallbackProgress 兜底的全局相对进度
     * @return 返回折算后的高精度浮点进度比例
     */
    fun calculateMiniPlayerProgress(
        currentPosition: Long,
        duration: Long,
        chapters: List<ChapterEntity>,
        isChapterMode: Boolean,
        fallbackProgress: Float
    ): Float {
        return if (isChapterMode && chapters.isNotEmpty()) {
            val currentChapter = ChapterTimeline.currentChapter(chapters, currentPosition)
            val posInChapter = ChapterTimeline.positionInChapter(
                chapters, currentChapter, currentPosition, duration
            )
            val chapterDuration = ChapterTimeline.duration(
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
     * 根据当前全局绝对播放位置，计算并检索当前正在播放的章节实体。
     *
     * @param chapters 章节信息列表
     * @param position 当前播放器的绝对位置（毫秒）
     * @return 返回匹配的章节实体，若无匹配则返回 null
     */
    fun currentChapter(chapters: List<ChapterEntity>, position: Long): ChapterEntity? {
        return ChapterTimeline.currentChapter(chapters, position)
    }
}
