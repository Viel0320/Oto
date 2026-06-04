package com.viel.aplayer.logger

import android.util.Log

/**
 * Audio Focus Logger (Logging helper for audio focus state transitions)
 * Consolidates all logs from PlaybackAudioFocusManager regarding focus requests, focus releases,
 * transient focus losses requiring pause avoidance, and automatic playback resumption when focus is regained.
 * Uses the tag "AudioFocus" to easily filter focus-related states in Logcat.
 */
internal object AudioFocusLogger {

    private const val TAG = "AudioFocus"

    /**
     * Log Permanent Loss (Record permanent audio focus loss requiring playback pause)
     */
    fun logPermanentLoss() {
        Log.d(TAG, "永久失去焦点，执行暂停")
    }

    /**
     * Log Transient Loss (Record transient audio focus loss, triggering pause avoidance and blocking auto-rewind)
     */
    fun logTransientLoss() {
        Log.d(TAG, "临时失去焦点，执行避让暂停并拦截回退")
    }

    /**
     * Log Focus Regained (Record audio focus recovery, attempting automatic playback recovery)
     */
    fun logFocusRegained() {
        Log.d(TAG, "重获焦点，执行自动续播自愈")
    }

    /**
     * Log Focus Request (Record audio focus request results from system audio manager)
     *
     * @param success Whether the focus request succeeded
     * @param resultCode The raw result code returned by the system
     */
    fun logFocusRequested(success: Boolean, resultCode: Int) {
        Log.d(TAG, "向系统申请音频焦点结果: $success (code: $resultCode)")
    }

    /**
     * Log Focus Abandon (Record audio focus abandon results from system audio manager)
     *
     * @param resultCode The raw result code returned by the system
     */
    fun logFocusAbandoned(resultCode: Int) {
        Log.d(TAG, "向系统释放音频焦点结果: $resultCode")
    }
}
