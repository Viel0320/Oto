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
        AbsLogEmitter.debug(
            TAG,
            "orphan cleanup: scanned=$scannedKeys, removed=$removedKeys, bytesBefore=$bytesBefore, bytesAfter=$bytesAfter"
        )
    }

    fun logOrphanCleanupFailure(errorClass: String, message: String?) {
        AbsLogEmitter.warn(
            TAG,
            "orphan cleanup failure: errorClass=$errorClass, message=${AbsLogSanitizer.compact(message)}"
        )
    }
}
