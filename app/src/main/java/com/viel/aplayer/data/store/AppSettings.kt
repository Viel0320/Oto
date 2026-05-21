package com.viel.aplayer.data.store


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
    val isChapterProgressMode: Boolean = false,
    /** 详尽的中文注释：新增是否允许明文 HTTP 流量持久化配置属性，默认值为 false 以满足极高的默认安全边界。 */
    val isCleartextTrafficAllowed: Boolean = false
)