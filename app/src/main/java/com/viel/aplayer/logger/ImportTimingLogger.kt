package com.viel.aplayer.logger

import android.os.SystemClock
import android.util.Log

// 集中管理导入链路耗时日志，统一使用 ImportTiming tag，方便在 Logcat 里单独过滤性能瓶颈。
internal object ImportTimingLogger {
    private const val TAG = "ImportTiming"
    private const val MAX_VALUE_LENGTH = 180

    // 使用 elapsedRealtime 避免系统时间调整影响耗时计算，适合扫描、解析、入库这类运行期性能统计。
    fun mark(): Long = SystemClock.elapsedRealtime()

    // 把开始时间转换为毫秒耗时，调用点可以在成功或失败路径统一记录。
    fun elapsedMs(startMs: Long): Long = SystemClock.elapsedRealtime() - startMs

    // 记录一个阶段耗时，scopeId 会被压缩，避免 SAF content Uri 过长导致 Logcat 难读。
    fun logDuration(
        scopeId: String,
        stage: String,
        elapsedMs: Long,
        detail: String = ""
    ) {
        val detailSuffix = detail.takeIf { it.isNotBlank() }?.let { " ${compact(it)}" }.orEmpty()
        // 将日志级别从原有的 INFO 降级为 DEBUG (Log.d)，以避免高频耗时统计在生产环境中输出过多冗余日志
        Log.d(TAG, "scope=${compact(scopeId)} stage=$stage elapsedMs=$elapsedMs$detailSuffix")
    }

    // 记录没有耗时语义的导入事件，例如 scope 构建数量或扫描会话开始结束。
    fun logEvent(
        scopeId: String,
        stage: String,
        detail: String = ""
    ) {
        val detailSuffix = detail.takeIf { it.isNotBlank() }?.let { " ${compact(it)}" }.orEmpty()
        // 将事件日志级别从原有的 INFO 降级为 DEBUG (Log.d)，减少不必要的控制台日志刷新
        Log.d(TAG, "scope=${compact(scopeId)} stage=$stage$detailSuffix")
    }

    // 用 try/finally 包住挂起任务，确保解析失败、入库失败或取消前也能看到该阶段已经耗费的时间。
    suspend fun <T> measure(
        scopeId: String,
        stage: String,
        detail: String = "",
        block: suspend () -> T
    ): T {
        val startedAt = mark()
        return try {
            block()
        } finally {
            logDuration(scopeId, stage, elapsedMs(startedAt), detail)
        }
    }

    private fun compact(value: String): String {
        val singleLine = value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')
        return if (singleLine.length <= MAX_VALUE_LENGTH) {
            singleLine
        } else {
            "${singleLine.take(MAX_VALUE_LENGTH)}..."
        }
    }
}