package com.viel.aplayer.logger

import android.os.SystemClock
import android.util.Log

internal object ImportTimingLogger {
    private const val TAG = "ImportTiming"
    private const val MAX_VALUE_LENGTH = 180

    fun mark(): Long = SystemClock.elapsedRealtime()

    fun elapsedMs(startMs: Long): Long = SystemClock.elapsedRealtime() - startMs

    fun logDuration(
        scopeId: String,
        stage: String,
        elapsedMs: Long,
        detail: String = ""
    ) {
        val detailSuffix = detail.takeIf { it.isNotBlank() }?.let { " ${compact(it)}" }.orEmpty()
        Log.d(TAG, "scope=${compact(scopeId)} stage=$stage elapsedMs=$elapsedMs$detailSuffix")
    }

    fun logEvent(
        scopeId: String,
        stage: String,
        detail: String = ""
    ) {
        val detailSuffix = detail.takeIf { it.isNotBlank() }?.let { " ${compact(it)}" }.orEmpty()
        Log.d(TAG, "scope=${compact(scopeId)} stage=$stage$detailSuffix")
    }

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
