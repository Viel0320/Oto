package com.viel.aplayer.logger

import android.util.Log

/**
 * Shared Playback Workflow Logger (Record global player engine actions and lifecycle transitions)
 *
 * Responsibility boundary:
 * 1. Logs common exceptions, state changes, and branch transitions within the core playback pipeline.
 * 2. Does not duplicate events managed by dedicated loggers (e.g., audio focus, VFS operations, or ABS sessions).
 * 3. Focuses on diagnosing why the core playback failed, controller connection errors, and when fallback routes were triggered.
 */
internal object PlaybackWorkflowLogger {
    private const val TAG = "PlaybackFlow"

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
