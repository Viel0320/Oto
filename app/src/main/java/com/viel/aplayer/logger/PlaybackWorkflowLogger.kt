package com.viel.aplayer.logger

import android.util.Log

/**
 * 公共播放工作流 logger。
 *
 * 责任边界：
 * 1. 记录播放器主链路中的公共异常与状态分支。
 * 2. 不吸收音频焦点、VFS 读流、ABS 会话等已有专用 logger 的职责。
 * 3. 专注回答“播放主链为什么失败、为什么没拿到 controller、为什么触发了兜底路径”。
 */
internal object PlaybackWorkflowLogger {
    private const val TAG = "PlaybackFlow"

    fun info(message: String) {
        runCatching { Log.i(TAG, message) }
    }

    fun debug(message: String) {
        runCatching { Log.d(TAG, message) }
    }

    fun warn(message: String, error: Throwable? = null) {
        runCatching { Log.w(TAG, message, error) }
    }

    fun error(message: String, error: Throwable? = null) {
        runCatching { Log.e(TAG, message, error) }
    }
}
