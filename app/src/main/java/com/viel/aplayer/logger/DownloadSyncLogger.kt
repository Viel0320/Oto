package com.viel.aplayer.logger

internal object DownloadSyncLogger {
    private const val TAG = "DownloadSync"

    fun mark(): Long = AbsLogClock.mark()

    fun elapsedMs(startNs: Long): Long = AbsLogClock.elapsedMs(startNs)

    fun logRecoverySkipped() {
        AbsLogEmitter.debug(TAG, "recovery skipped: recoverableTasks=false")
    }

    fun logRecoveryStarted(recoverableCount: Int) {
        AbsLogEmitter.debug(TAG, "recovery start: recoverableTasks=$recoverableCount")
    }

    fun logRecoveryFailure(errorClass: String, message: String?) {
        AbsLogEmitter.warn(
            TAG,
            "recovery failure: errorClass=$errorClass, message=${AbsLogSanitizer.compact(message)}"
        )
    }

    fun logBookReconciled(bookId: String, status: String, completedFiles: Int, totalFiles: Int, costMs: Long) {
        // Book Reconciliation Log (Records the durable aggregate result without exposing file paths or URLs)
        // The short book id and counts are enough to correlate sync behavior while keeping provider coordinates out of release logs.
        AbsLogEmitter.debug(
            TAG,
            "book reconciled: bookId=${AbsLogSanitizer.shortId(bookId)}, status=$status, completed=$completedFiles/$totalFiles, cost=${costMs}ms"
        )
    }

    fun logBookReconcileFailure(bookId: String, errorClass: String, message: String?) {
        AbsLogEmitter.warn(
            TAG,
            "book reconcile failure: bookId=${AbsLogSanitizer.shortId(bookId)}, errorClass=$errorClass, message=${AbsLogSanitizer.compact(message)}"
        )
    }

    fun logMissingRequestRepair(bookId: String, missingCount: Int) {
        AbsLogEmitter.debug(
            TAG,
            "missing requests repair: bookId=${AbsLogSanitizer.shortId(bookId)}, missing=$missingCount"
        )
    }

    fun logOrphanCleanup(scannedKeys: Int, removedKeys: Int, bytesBefore: Long, bytesAfter: Long) {
        // Manual Cache Orphan Cleanup Log (Record aggregate cleanup results without cache keys or paths)
        // Counts and byte totals are enough to diagnose cleanup effectiveness while protecting provider identifiers.
        AbsLogEmitter.debug(
            TAG,
            "orphan cleanup: scanned=$scannedKeys, removed=$removedKeys, bytesBefore=$bytesBefore, bytesAfter=$bytesAfter"
        )
    }

    fun logOrphanCleanupFailure(errorClass: String, message: String?) {
        // Manual Cache Orphan Cleanup Failure (Keep retry diagnostics sanitized)
        // WorkManager will retry transient failures, so logs only include error class and compact message text.
        AbsLogEmitter.warn(
            TAG,
            "orphan cleanup failure: errorClass=$errorClass, message=${AbsLogSanitizer.compact(message)}"
        )
    }
}
