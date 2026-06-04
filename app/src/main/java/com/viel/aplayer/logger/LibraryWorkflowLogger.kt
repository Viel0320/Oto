package com.viel.aplayer.logger

import android.util.Log

/**
 * Shared Library Workflow Logger (Record domain-agnostic library operations)
 *
 * Responsibility boundary:
 * 1. Logs actions that span multiple domain components and are not specific to a single protocol.
 * 2. Examples include emergency playback suspension when deleting a library root, WorkManager retries, and overall flow failures.
 * 3. Does not capture details specific to protocol providers (such as ABS, SAF, or WebDAV), which are handled by their own dedicated loggers.
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
