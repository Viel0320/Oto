package com.viel.aplayer.ui.state

import com.viel.aplayer.data.BookWithProgress
import com.viel.aplayer.util.image.ImageProcessor

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
    val backgroundColorArgb: Int = ImageProcessor.DEFAULT_BACKGROUND_ARGB
)
