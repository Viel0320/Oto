package com.viel.aplayer.logger

import android.util.Log

/**
 * Playback Failure and Self-Healing Logger (Diagnose playback failures and failover transitions)
 *
 * Collects logs from PlaybackFailureHandler regarding broken/missing track flags
 * and successful failover redirects to alternative tracks.
 * Uses a unified tag "PlaybackFailure" to simplify tracing in Logcat.
 */
internal object PlaybackFailureLogger {

    private const val TAG = "PlaybackFailure"

    /**
     * Log Damaged or Missing Track (Record when a track is flagged as unavailable)
     *
     * Marks the specific track as failed to trigger subsequent self-healing.
     *
     * @param skipKey The key identifying the failed track (format: bookId:queueIndex).
     */
    fun logTrackMarkedUnavailable(skipKey: String) {
        Log.d(TAG, "检测到分轨文件损坏或缺失，标记失效：$skipKey")
    }

    /**
     * Log Successful Failover (Record transition to the next playable track)
     *
     * Signals that self-healing succeeded and the player is navigating to a valid fallback track.
     *
     * @param nextIndex The index of the next available track to play.
     */
    fun logSelfHealSuccess(nextIndex: Int) {
        Log.d(TAG, "灾备自愈成功，正在跳转到下一可用分轨：$nextIndex")
    }
}
