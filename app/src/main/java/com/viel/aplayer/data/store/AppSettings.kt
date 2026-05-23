package com.viel.aplayer.data.store


/**
 * 应用全局持久化设置数据类。
 */
// 为每一次改动添加详尽的中文注释：定义悬浮层视觉效果模式，Material 表示停用模糊采样并回到 Material 3 原生容器层次，MiuixBlur 表示启用 miuix-blur 硬件磨砂玻璃效果。
enum class GlassEffectMode {
    Material,
    MiuixBlur
}

// 为每一次改动添加详尽的中文注释：
// 定义睡眠模式枚举。
// Regular：常规模式，设定睡眠时间后开始倒计时。
// MotionTracking：运动跟踪模式，检测到设备静止才计时，检测到运动则暂停/停止计时。
// SleepTracking：睡眠跟踪模式，请求活动识别权限，检测到用户进入睡眠状态才开始计时。
enum class SleepMode {
    Regular,
    MotionTracking,
    SleepTracking
}

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
    val isShakeToResetEnabled: Boolean = true,
    // 为每一次改动添加详尽的中文注释：新增睡眠模式属性，支持常规模式、运动跟踪模式和睡眠跟踪模式，默认为常规模式。
    val sleepMode: SleepMode = SleepMode.Regular,
    // 为每一次改动添加详尽的中文注释：新增悬浮层玻璃效果模式持久化属性，默认值只由设置模型统一声明，UI 页面和组件不再各自声明默认值。
    val glassEffectMode: GlassEffectMode = DEFAULT_GLASS_EFFECT_MODE,
    // 为每一次改动添加详尽的中文注释：新增自动回退播放进度时长属性（秒），默认值为 0 秒，表示处于关闭状态。
    val autoRewindSeconds: Int = 0,
    // 为每一次改动添加详尽的中文注释：新增上一次播放是否为异常非正常中断的标志，默认为 false。当播放器正在播放时为 true，正常暂停时重置为 false。
    val isLastPlaybackInterrupted: Boolean = false,
    // 为每一次改动添加详尽的中文注释：新增通知避让的全局控制开关，默认值为 false。开启时在被迫失去音频焦点时自动暂停，重获焦点时恢复，且不应用任何自动回放（回退）。
    val isNotificationAvoidanceEnabled: Boolean = false
) {
    companion object {
        // 为每一次改动添加详尽的中文注释：集中定义玻璃效果设置默认值，所有设置流缺省值与预览示例都应引用这里，避免默认值散落到页面组件。
        val DEFAULT_GLASS_EFFECT_MODE: GlassEffectMode = GlassEffectMode.Material
    }
}
