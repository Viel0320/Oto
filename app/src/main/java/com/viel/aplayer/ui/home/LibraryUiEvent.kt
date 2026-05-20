package com.viel.aplayer.ui.home

/**
 * 详尽的中文注释：LibraryViewModel 发射的一次性 UI 事件密封接口。
 * 遵循 ViewModel 不直接操作 Android UI 组件（如 Toast）的架构原则，
 * 所有需要 UI 层呈现的一次性反馈均通过此事件流发射，由 Composable 层订阅消费。
 */
sealed interface LibraryUiEvent {
    /**
     * 详尽的中文注释：展示 Toast 消息的一次性事件。
     * @param message 需要展示的文本内容
     */
    data class ShowToast(val message: String) : LibraryUiEvent
}
