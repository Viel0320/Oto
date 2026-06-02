package com.viel.aplayer.logger

import android.util.Log

/**
 * 公共业务流程 logger。
 *
 * 责任边界：
 * 1. 记录不属于单一来源协议、但又跨越多个领域组件的公共业务动作。
 * 2. 例如删除库根触发停播、后台扫描 worker 重试、公共工作流失败。
 * 3. 不吸收来源协议细节；ABS/SAF/WebDAV 各自的专属链路仍然记到各自 logger。
 */
internal object LibraryWorkflowLogger {
    private const val TAG = "LibraryFlow"

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
