package com.viel.aplayer.logger

import android.util.Log

/**
 * 播放故障与灾备自愈 Logger。
 * 统一收纳 PlaybackFailureHandler 中与分轨文件损坏/缺失标记、
 * 灾备跳轨自愈成功等相关的所有日志输出。
 * 使用统一 TAG "PlaybackFailure"，方便在 Logcat 中追踪播放异常容灾链路。
 */
internal object PlaybackFailureLogger {

    private const val TAG = "PlaybackFailure"

    /**
     * 记录检测到分轨文件损坏或缺失，已标记失效。
     *
     * @param skipKey 被标记失效的分轨键值 (格式: bookId:queueIndex)
     */
    fun logTrackMarkedUnavailable(skipKey: String) {
        Log.d(TAG, "检测到分轨文件损坏或缺失，标记失效：$skipKey")
    }

    /**
     * 记录灾备自愈成功，正在跳转到下一可用分轨。
     *
     * @param nextIndex 跳转到的下一可用分轨索引
     */
    fun logSelfHealSuccess(nextIndex: Int) {
        Log.d(TAG, "灾备自愈成功，正在跳转到下一可用分轨：$nextIndex")
    }
}
