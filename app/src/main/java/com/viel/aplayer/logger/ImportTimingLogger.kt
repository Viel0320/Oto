package com.viel.aplayer.logger

import android.os.SystemClock
import android.util.Log

// Import Performance Logger (Centralized stopwatch logging for media import pipelines under the unified tag "ImportTiming")
internal object ImportTimingLogger {
    private const val TAG = "ImportTiming"
    private const val MAX_VALUE_LENGTH = 180

    // Use SystemClock.elapsedRealtime to prevent system clock adjustments from skewing time measurements, fitting runtime performance stats.
    fun mark(): Long = SystemClock.elapsedRealtime()

    // Calculate elapsed milliseconds from start timestamp, which can be captured consistently on success/failure exit points.
    fun elapsedMs(startMs: Long): Long = SystemClock.elapsedRealtime() - startMs

    // Log Stage Duration (Record duration of a single stage, compacting the scope ID to prevent long SAF URIs from flooding Logcat)
    fun logDuration(
        scopeId: String,
        stage: String,
        elapsedMs: Long,
        detail: String = ""
    ) {
        val detailSuffix = detail.takeIf { it.isNotBlank() }?.let { " ${compact(it)}" }.orEmpty()
        // Downgrade to DEBUG logs to avoid bloating production logs with high-frequency profiling outputs.
        Log.d(TAG, "scope=${compact(scopeId)} stage=$stage elapsedMs=$elapsedMs$detailSuffix")
    }

    // Log general import events that do not carry duration measurements (e.g. scope construction, scan session start/end).
    fun logEvent(
        scopeId: String,
        stage: String,
        detail: String = ""
    ) {
        val detailSuffix = detail.takeIf { it.isNotBlank() }?.let { " ${compact(it)}" }.orEmpty()
        // Downgrade event logs to DEBUG level to reduce excessive logcat updates.
        Log.d(TAG, "scope=${compact(scopeId)} stage=$stage$detailSuffix")
    }

    // Wrap suspended tasks in try-finally blocks to guarantee duration logging even if parsing, database insertion, or coroutine cancellation occurs.
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