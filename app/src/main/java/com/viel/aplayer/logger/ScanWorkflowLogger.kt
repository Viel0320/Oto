package com.viel.aplayer.logger

import android.util.Log

/**
 * Shared Library Maintenance and Scan Workflow Logger (Trace broad repository operations and scan execution)
 *
 * Responsibility boundary:
 * 1. Logs broad anomalies in administrative tasks (such as folder root config, task scheduling, WorkManager sync, and cover recovery).
 * 2. Bypasses file-system protocol details (ABS, SAF, WebDAV), which are routed to their respective dedicated loggers instead.
 * 3. Focuses on diagnosing which phase of the lifecycle failed (e.g., dispatching, directory walk, DB cleanup, or data restoration).
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
        // Release Warning Boundary (Route retained scan diagnostics through SecureLog)
        // Library scans traverse user-controlled storage coordinates, so warnings must not emit raw paths in release builds.
        SecureLog.warn(TAG, message, error)
    }

    fun error(message: String, error: Throwable? = null) {
        // Release Error Boundary (Route retained scan failures through SecureLog)
        // Error logs remain available for triage while the final emitter strips filesystem, VFS, and credential-bearing text.
        SecureLog.error(TAG, message, error)
    }
}
