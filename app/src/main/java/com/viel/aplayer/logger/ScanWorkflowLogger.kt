package com.viel.aplayer.logger

import android.util.Log

/**
 * 公共扫描与书库维护工作流 logger。
 *
 * 责任边界：
 * 1. 记录库根管理、扫描调度、后台 worker、封面恢复这类公共维护链路的直接异常。
 * 2. 不吸收 ABS / SAF / WebDAV 协议细节；来源细节仍然由各自专用 logger 负责。
 * 3. 专注回答“哪条维护流程失败了、是在调度、扫描、清理还是恢复阶段失败”。
 */
internal object ScanWorkflowLogger {
    private const val TAG = "ScanFlow"

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
