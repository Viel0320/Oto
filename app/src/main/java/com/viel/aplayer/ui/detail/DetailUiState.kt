package com.viel.aplayer.ui.detail

import com.viel.aplayer.data.entity.BookWithProgress
import com.viel.aplayer.media.parse.ImageProcessor

/**
 * 书籍详情页的 UI 状态。
 */
data class DetailUiState(
    /** 当前选中的书籍实体 */
    val book: BookWithProgress? = null,
    /** 是否显示详情页 */
    val isVisible: Boolean = false,
    /** 书籍文件在本地是否可用 */
    val isAvailable: Boolean = false,
    /** 播放进度百分比 (0-100) */
    val progressPercent: Int = 0,
    /** 背景适配色 (ARGB) */
    val backgroundColorArgb: Int = ImageProcessor.DEFAULT_BACKGROUND_ARGB,
    // 为每一次改动添加详尽的中文注释：新增 fullSourcePath 字段，用于存储在 ViewModel 层已经处理完毕（经过 SAF 解码、primary: 过滤截断并与文件名拼接）的完整物理源文件路径，以保证 UI 渲染层的纯净和高性能
    val fullSourcePath: String = ""
)