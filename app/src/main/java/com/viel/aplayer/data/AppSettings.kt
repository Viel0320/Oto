package com.viel.aplayer.data

/**
 * 应用全局持久化设置数据类。
 */
data class AppSettings(
    /** 首页过滤状态 */
    val homeFilter: String = "NotStarted",
    /** 播放倍速是否为全局记忆 */
    val isGlobalSpeedEnabled: Boolean = false,
    /** 全局播放倍速值 */
    val globalPlaybackSpeed: Float = 1.0f,
    /** 是否开启章节进度模式（进度条仅显示当前章节） */
    val isChapterProgressMode: Boolean = false
)
