package com.viel.aplayer.ui.common

/**
 * 
 * 全局通用的 UI 一次性单次反馈事件接口（遵循 MVI 或单向数据流的一次性事件设计）。
 * 集中放置在 ui.common 包下，供所有业务 ViewModel 继承或直接复用，杜绝样板代码。
 */
sealed interface UiEvent {
    /**
     * 展示 Toast 消息的通用事件。
     * @param message 需要展示的文本消息内容。
     */
    data class ShowToast(val message: String) : UiEvent

    /**
     * 当检测到正在播放的物理音频分轨文件损坏或缺失时，触发弹窗确认跳轨事件。
     * @param bookId 当前音频书籍的唯一标识 ID。
     * @param queueIndex 发生故障的音频分轨在播放队列中的索引。
     */
    data class ShowTrackUnavailableDialog(val bookId: String, val queueIndex: Int) : UiEvent
}
