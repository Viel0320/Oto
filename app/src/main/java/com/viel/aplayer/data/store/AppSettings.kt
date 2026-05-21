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
    val isCleartextTrafficAllowed: Boolean = false,
    // 为每一次改动添加详尽的中文注释：新增自动跳过静音期 (Skip Silence) 全局控制开关，默认关闭以提供安全非打扰的默认播放体验。
    val isSkipSilenceEnabled: Boolean = false,
    // 为每一次改动添加详尽的中文注释：新增自动跳过静音的判定最小时长阈值属性（秒），默认 2.0 秒，保障不同有声书均能完美匹配。
    val skipSilenceDurationThreshold: Float = 2.0f,
    // 为每一次改动添加详尽的中文注释：新增自动跳过静音时的 Toast 通知总开关，默认开启以在跳过时温馨告知用户。
    val isSkipSilenceNotificationEnabled: Boolean = true,
    // 为每一次改动添加详尽的中文注释：新增睡眠定时器音量渐隐机制的全局控制开关，默认开启以提供无缝且温和的睡眠暂停体验。
    val isSleepFadeOutEnabled: Boolean = true,
    // 为每一次改动添加详尽的中文注释：新增摇晃手机重置睡眠定时器的全局控制开关，默认开启以提供夜间无需亮屏的极致贴心盲操交互。
    val isShakeToResetEnabled: Boolean = true
)