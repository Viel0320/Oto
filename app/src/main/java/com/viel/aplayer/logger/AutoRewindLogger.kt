package com.viel.aplayer.logger

import android.util.Log

/**
 * Auto-Rewind and Cold-Start Recovery Logger
 * Consolidates all logs from AutoRewindManager regarding cold-start abnormal shutdown detection
 * and auto-rewind progress healing operations.
 * Uses the tag "AutoRewind" to trace the auto-rewind execution flow in Logcat.
 */
internal object AutoRewindLogger {

    private const val TAG = "AutoRewind"

    /**
     * Record the self-healing progress auto-rewind result after a cold-start abnormal shutdown check.
     *
     * @param bookId The ID of the audiobook being healed
     * @param rewindMs The amount of rewind applied in milliseconds
     * @param targetPositionMs The target playback position after self-healing in milliseconds
     */
    fun logColdStartSelfHeal(bookId: String, rewindMs: Long, targetPositionMs: Long) {
        Log.d(
            TAG,
            "冷启动检测到异常中断，已自动回退 $rewindMs ms 进行自愈，" +
                "书籍ID: $bookId, 最终起始进度: $targetPositionMs ms"
        )
    }
}
