package com.viel.aplayer.ui.state

/**
 * UI 交互设置与临时状态数据类。
 * 存储仅与界面表现相关的状态，不涉及播放逻辑核心。
 */
data class PlayerSettingsState(
    /** 选中的休眠时间选项（分钟），0 表示关闭 */
    val selectedSleepTimer: Int = 0,
    /** 是否显示“撤销进度跳转”提示 */
    val showUndoSeek: Boolean = false,
    /** 章节列表弹窗是否可见 */
    val isChapterListVisible: Boolean = false,
    /** 书签添加/编辑对话框是否可见 */
    val isBookmarkDialogVisible: Boolean = false,
    /** 当前正在编辑的书签标题 */
    val bookmarkTitle: String = "",
    /** 详情页底部选中的内容 Tab 索引（如 0:书签, 1:字幕, 2:推荐） */
    val selectedContentTab: Int = -1,
    /** 迷你播放器是否被用户手动隐藏 */
    val isMiniPlayerHidden: Boolean = false,
    /** 是否开启章节进度模式（显示当前章节内的进度） */
    val isChapterProgressMode: Boolean = false,
    /** 全屏播放器页面是否可见 */
    val isFullPlayerVisible: Boolean = false
)
