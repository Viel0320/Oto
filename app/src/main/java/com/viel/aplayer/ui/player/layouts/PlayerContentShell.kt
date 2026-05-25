package com.viel.aplayer.ui.player.layouts

/**
 * 自适应播放器布局共享过渡外壳 (PlayerContentShell)。
 * 专门用于在 layouts 包内共享三大子布局（书签、播放控制、关联推荐）的动画切换媒介状态，
 * 避免不同文件内的 private 声明发生重复声明冲突（Redeclaration）或非必要的作用域隔离。
 */
enum class PlayerContentShell(val index: Int) {
    Bookmarks(0),
    PlaybackShell(1),
    Related(2)
}
