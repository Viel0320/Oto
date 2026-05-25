package com.viel.aplayer.ui.detail

import com.viel.aplayer.data.entity.BookWithProgress
import com.viel.aplayer.media.parser.ImageProcessor

/**
 * 书籍详情页的 UI 状态。
 */
data class DetailUiState(
    /** 当前选中的书籍实体 */
    val book: BookWithProgress? = null,
    /** 是否显示详情页 */
    val isVisible: Boolean = false,
    /** 书籍文件在本地是否可用 */
    // 中文注释：详情页采用乐观可用状态，后台 VFS 检测确认缺失后才降级为不可播放，避免打开详情时误报不可用。
    val isAvailable: Boolean = true,
    /** 播放进度百分比 (0-100)，来自数据库的真实进度 */
    val progressPercent: Int = 0,
    // 详尽中文注释：M-19 修复 — 经过 3 秒保护期过滤后的展示进度。
    // 当用户从"未播放状态"点击播放后的 3 秒内，此值强制为 0，
    // 防止按钮图标/文案在"Start Listening"和"Continue at X%"之间高频闪烁。
    // 由 DetailViewModel.onPlayPressed 控制，配置变更后不丢失。
    val displayProgressPercent: Int = 0,
    /** 背景适配色 (ARGB) */
    val backgroundColorArgb: Int = ImageProcessor.DEFAULT_BACKGROUND_ARGB,
    // 为每一次改动添加详尽的中文注释：新增 fullSourcePath 字段，用于存储在 ViewModel 层已经处理完毕（经过 SAF 解码、primary: 过滤截断并与文件名拼接）的完整物理源文件路径，以保证 UI 渲染层的纯净和高性能
    val fullSourcePath: String = ""
)
