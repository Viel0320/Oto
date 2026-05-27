package com.viel.aplayer.logger

import android.util.Log

/**
 * 自动回退与冷启动进度自愈 Logger。
 * 统一收纳 AutoRewindManager 中与冷启动异常中断检测、
 * 自动回退进度自愈等相关的所有日志输出。
 * 使用统一 TAG "AutoRewind"，方便在 Logcat 中追踪自动回退机制的执行链路。
 */
internal object AutoRewindLogger {

    private const val TAG = "AutoRewind"

    /**
     * 记录冷启动检测到异常中断后自动回退进度自愈的结果。
     *
     * @param bookId 发生自愈的有声书 ID
     * @param rewindMs 回退的毫秒数
     * @param targetPositionMs 自愈后的最终起始进度（毫秒）
     */
    fun logColdStartSelfHeal(bookId: String, rewindMs: Long, targetPositionMs: Long) {
        Log.d(
            TAG,
            "冷启动检测到异常中断，已自动回退 $rewindMs ms 进行自愈，" +
                "书籍ID: $bookId, 最终起始进度: $targetPositionMs ms"
        )
    }
}
