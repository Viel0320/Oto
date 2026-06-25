package com.viel.oto.logger

import android.util.Log

/**
 * Logging helper for audio focus state transitions.
 * Consolidates all logs from PlaybackAudioFocusManager regarding focus requests, focus releases,
 * transient focus losses requiring pause avoidance, and automatic playback resumption when focus is regained.
 * Uses the tag "AudioFocus" to easily filter focus-related states in Logcat.
 */
object AudioFocusLogger {

    private const val TAG = "AudioFocus"

    /**
     * Record permanent audio focus loss requiring playback pause.
     */
    fun logPermanentLoss() {
        Log.d(TAG, "永久失去焦点，执行暂停")
    }

    /**
     * Record transient audio focus loss, triggering pause avoidance and blocking auto-rewind.
     */
    fun logTransientLoss() {
        Log.d(TAG, "临时失去焦点，执行避让暂停并拦截回退")
    }

    /**
     * Record audio focus recovery, attempting automatic playback recovery.
     */
    fun logFocusRegained() {
        Log.d(TAG, "重获焦点，执行自动续播自愈")
    }

    /**
     * Record audio focus request results from system audio manager.
     *
     * @param success Whether the focus request succeeded
     * @param resultCode The raw result code returned by the system
     */
    fun logFocusRequested(success: Boolean, resultCode: Int) {
        Log.d(TAG, "向系统申请音频焦点结果: $success (code: $resultCode)")
    }

    /**
     * Record audio focus abandon results from system audio manager.
     *
     * @param resultCode The raw result code returned by the system
     */
    fun logFocusAbandoned(resultCode: Int) {
        Log.d(TAG, "向系统释放音频焦点结果: $resultCode")
    }
}
