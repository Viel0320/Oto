package com.viel.aplayer.logger

import android.util.Log

/**
 * 音频焦点状态变化 Logger。
 * 统一收纳 PlaybackAudioFocusManager 中与系统音频焦点申请、释放、
 * 临时丢失避让暂停、重获焦点自动续播等相关的所有日志输出。
 * 使用统一 TAG "AudioFocus"，方便在 Logcat 中过滤音频焦点相关的状态跃迁。
 */
internal object AudioFocusLogger {

    private const val TAG = "AudioFocus"

    /**
     * 记录永久失去音频焦点并执行暂停。
     */
    fun logPermanentLoss() {
        Log.d(TAG, "永久失去焦点，执行暂停")
    }

    /**
     * 记录临时失去音频焦点，执行避让暂停并拦截自动回退。
     */
    fun logTransientLoss() {
        Log.d(TAG, "临时失去焦点，执行避让暂停并拦截回退")
    }

    /**
     * 记录重新获得音频焦点，执行自动续播自愈。
     */
    fun logFocusRegained() {
        Log.d(TAG, "重获焦点，执行自动续播自愈")
    }

    /**
     * 记录向系统申请音频焦点的结果。
     *
     * @param success 是否申请成功
     * @param resultCode 系统返回的结果码
     */
    fun logFocusRequested(success: Boolean, resultCode: Int) {
        Log.d(TAG, "向系统申请音频焦点结果: $success (code: $resultCode)")
    }

    /**
     * 记录向系统释放音频焦点的结果。
     *
     * @param resultCode 系统返回的结果码
     */
    fun logFocusAbandoned(resultCode: Int) {
        Log.d(TAG, "向系统释放音频焦点结果: $resultCode")
    }
}
