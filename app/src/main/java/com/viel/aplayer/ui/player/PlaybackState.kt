package com.viel.aplayer.ui.player


/**
 * 实时播放状态数据类。
 * 包含播放器引擎的动态信息，如播放位置、时长、速度等。
 * 此状态更新频率较高。
 */
data class PlaybackState(
    /** 是否正在播放 */
    val isPlaying: Boolean = false,
    /** 是否准备好播放（Play when ready） */
    val playWhenReady: Boolean = false,
    /** 当前播放进度（毫秒） */
    val currentPosition: Long = 0L,
    /** 音频总时长（毫秒），作为进度的唯一真理来源 */
    val duration: Long = 0L,
    /** 播放倍速 */
    val playbackSpeed: Float = 1.0f,
    /** 播放速度是否处于手动调节模式 */
    val isSpeedManualMode: Boolean = false
) {
    /** 当前播放进度的百分比浮点数 (0.0 - 1.0) */
    val progress: Float
        get() = if (duration > 0) currentPosition.toFloat() / duration.toFloat() else 0f
}