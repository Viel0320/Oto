package com.viel.oto.logger

import android.util.Log

/**
 * Record global player engine actions and lifecycle transitions.
 *
 * Responsibility boundary:
 * 1. Logs common exceptions, state changes, and branch transitions within the core playback pipeline.
 * 2. Does not duplicate events managed by dedicated loggers (e.g., audio focus, VFS operations, or ABS sessions).
 * 3. Focuses on diagnosing why the core playback failed, controller connection errors, and when fallback routes were triggered.
 */
object PlaybackWorkflowLogger {
    private const val TAG = "PlaybackFlow"

    fun info(message: String) {
        runCatching { Log.i(TAG, message) }
    }

    fun debug(message: String) {
        runCatching { Log.d(TAG, message) }
    }

    fun warn(message: String, error: Throwable? = null) {
        SecureLog.warn(TAG, message, error)
    }

    fun error(message: String, error: Throwable? = null) {
        SecureLog.error(TAG, message, error)
    }
}
