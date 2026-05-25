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
}
