package com.viel.aplayer.logger

/**
 * ABS Sync Logger (Catalog synchronization and background synchronization worker logs)
 *
 * Boundaries of responsibility:
 * 1. Logs root-level sync plans, minified index results, batch details requests, local upserts, stale markers, remote deleted lists, and WorkManager task scheduling.
 * 2. Does not log authentication checks, user settings view actions, playback sessions, or low-level HTTP stream details.
 * 3. Designed to trace library synchronization cycles, batch failure hotspots, and deletions reconciliation.
 */
internal object AbsSyncLogger {
    private const val TAG = "AbsSync"

    fun mark(): Long = AbsLogClock.mark()

    fun elapsedMs(startNs: Long): Long = AbsLogClock.elapsedMs(startNs)

    fun logInspectPlanStart(rootId: String, libraryId: String) {
        AbsLogEmitter.debug(
            TAG,
            "inspectPlan start: rootId=${AbsLogSanitizer.shortId(rootId)}, libraryId=${AbsLogSanitizer.shortId(libraryId)}"
        )
    }

    fun logInspectPlanSuccess(rootId: String, totalItems: Int, batchSize: Int, requiresConfirmation: Boolean, costMs: Long) {
        AbsLogEmitter.debug(
            TAG,
            "inspectPlan success: rootId=${AbsLogSanitizer.shortId(rootId)}, totalItems=$totalItems, batchSize=$batchSize, requiresConfirmation=$requiresConfirmation, cost=${costMs}ms"
        )
    }

    fun logInspectPlanFailure(rootId: String, costMs: Long, errorClass: String, message: String?) {
        AbsLogEmitter.warn(
            TAG,
            "inspectPlan failure: rootId=${AbsLogSanitizer.shortId(rootId)}, cost=${costMs}ms, errorClass=$errorClass, message=${AbsLogSanitizer.compact(message)}"
        )
    }

    fun logSyncRootStart(rootId: String, libraryId: String) {
        AbsLogEmitter.debug(
            TAG,
            "syncRoot start: rootId=${AbsLogSanitizer.shortId(rootId)}, libraryId=${AbsLogSanitizer.shortId(libraryId)}"
        )
    }

    fun logSyncRootSuccess(rootId: String, minifiedCount: Int, detailCount: Int, hadBatchFailure: Boolean, costMs: Long) {
        AbsLogEmitter.debug(
            TAG,
            "syncRoot success: rootId=${AbsLogSanitizer.shortId(rootId)}, minified=$minifiedCount, details=$detailCount, hadBatchFailure=$hadBatchFailure, cost=${costMs}ms"
        )
    }

    fun logSyncRootFailure(rootId: String, costMs: Long, errorClass: String, message: String?) {
        AbsLogEmitter.warn(
            TAG,
            "syncRoot failure: rootId=${AbsLogSanitizer.shortId(rootId)}, cost=${costMs}ms, errorClass=$errorClass, message=${AbsLogSanitizer.compact(message)}"
        )
    }

    fun logBatchRequest(rootId: String, batchIndex: Int, batchSize: Int, itemIds: List<String>) {
        val compactIds = itemIds.joinToString(prefix = "[", postfix = "]") { itemId ->
            AbsLogSanitizer.shortId(itemId)
        }
        AbsLogEmitter.debug(
            TAG,
            "detailBatch start: rootId=${AbsLogSanitizer.shortId(rootId)}, batchIndex=$batchIndex, batchSize=$batchSize, itemIds=$compactIds"
        )
    }

    fun logBatchSuccess(rootId: String, batchIndex: Int, requested: Int, returned: Int, costMs: Long) {
        AbsLogEmitter.debug(
            TAG,
            "detailBatch success: rootId=${AbsLogSanitizer.shortId(rootId)}, batchIndex=$batchIndex, requested=$requested, returned=$returned, cost=${costMs}ms"
        )
    }

    fun logBatchFailure(rootId: String, batchIndex: Int, requested: Int, costMs: Long, errorClass: String, message: String?) {
        AbsLogEmitter.warn(
            TAG,
            "detailBatch failure: rootId=${AbsLogSanitizer.shortId(rootId)}, batchIndex=$batchIndex, requested=$requested, cost=${costMs}ms, errorClass=$errorClass, message=${AbsLogSanitizer.compact(message)}"
        )
    }

    fun logIncrementalSelection(rootId: String, totalItems: Int, detailCandidates: Int, reusedItems: Int, fingerprintUnchanged: Boolean) {
        AbsLogEmitter.debug(
            TAG,
            "incremental selection: rootId=${AbsLogSanitizer.shortId(rootId)}, total=$totalItems, detailCandidates=$detailCandidates, reused=$reusedItems, fingerprintUnchanged=$fingerprintUnchanged"
        )
    }

    fun logItemRetryStart(rootId: String, itemId: String, attempt: Int, maxAttempts: Int) {
        AbsLogEmitter.debug(
            TAG,
            "detail retry start: rootId=${AbsLogSanitizer.shortId(rootId)}, itemId=${AbsLogSanitizer.shortId(itemId)}, attempt=$attempt/$maxAttempts"
        )
    }

    fun logItemRetrySuccess(rootId: String, itemId: String, attempt: Int) {
        AbsLogEmitter.debug(
            TAG,
            "detail retry success: rootId=${AbsLogSanitizer.shortId(rootId)}, itemId=${AbsLogSanitizer.shortId(itemId)}, attempt=$attempt"
        )
    }

    fun logItemRetryFailure(rootId: String, itemId: String, attempt: Int, maxAttempts: Int, errorClass: String, message: String?) {
        AbsLogEmitter.warn(
            TAG,
            "detail retry failure: rootId=${AbsLogSanitizer.shortId(rootId)}, itemId=${AbsLogSanitizer.shortId(itemId)}, attempt=$attempt/$maxAttempts, errorClass=$errorClass, message=${AbsLogSanitizer.compact(message)}"
        )
    }

    fun logItemRetryGiveUp(rootId: String, itemId: String, maxAttempts: Int, reason: String?) {
        AbsLogEmitter.warn(
            TAG,
            "detail retry giveUp: rootId=${AbsLogSanitizer.shortId(rootId)}, itemId=${AbsLogSanitizer.shortId(itemId)}, attempts=$maxAttempts, reason=${AbsLogSanitizer.compact(reason)}"
        )
    }

    fun logSkipUnplayableItem(rootId: String, itemId: String?, mediaType: String?) {
        AbsLogEmitter.debug(
            TAG,
            "skip item: rootId=${AbsLogSanitizer.shortId(rootId)}, itemId=${AbsLogSanitizer.shortId(itemId)}, mediaType=${AbsLogSanitizer.compact(mediaType, 24)}"
        )
    }

    fun logUpsertItem(rootId: String, itemId: String?, bookId: String, fileCount: Int, chapterCount: Int, hasProgress: Boolean) {
        AbsLogEmitter.debug(
            TAG,
            "upsert item: rootId=${AbsLogSanitizer.shortId(rootId)}, itemId=${AbsLogSanitizer.shortId(itemId)}, bookId=${AbsLogSanitizer.shortId(bookId)}, files=$fileCount, chapters=$chapterCount, hasProgress=$hasProgress"
        )
    }

    fun logMarkStale(rootId: String, count: Int) {
        AbsLogEmitter.debug(
            TAG,
            "mark stale: rootId=${AbsLogSanitizer.shortId(rootId)}, count=$count"
        )
    }

    fun logMarkRemoteDeleted(rootId: String, count: Int) {
        AbsLogEmitter.debug(
            TAG,
            "mark remoteDeleted: rootId=${AbsLogSanitizer.shortId(rootId)}, count=$count"
        )
    }

    fun logSchedulerEnqueue(rootId: String, uniqueWorkName: String) {
        AbsLogEmitter.debug(
            TAG,
            "worker enqueue: rootId=${AbsLogSanitizer.shortId(rootId)}, uniqueWork=${AbsLogSanitizer.compact(uniqueWorkName, 96)}"
        )
    }

    fun logWorkerStart(rootId: String) {
        AbsLogEmitter.debug(
            TAG,
            "worker start: rootId=${AbsLogSanitizer.shortId(rootId)}"
        )
    }

    fun logWorkerSuccess(rootId: String) {
        AbsLogEmitter.debug(
            TAG,
            "worker success: rootId=${AbsLogSanitizer.shortId(rootId)}"
        )
    }

    fun logWorkerRetry(rootId: String, errorClass: String, message: String?) {
        AbsLogEmitter.warn(
            TAG,
            "worker retry: rootId=${AbsLogSanitizer.shortId(rootId)}, errorClass=$errorClass, message=${AbsLogSanitizer.compact(message)}"
        )
    }

    fun logWorkerFailure(rootId: String, errorClass: String, message: String?) {
        AbsLogEmitter.warn(
            TAG,
            "worker failure: rootId=${AbsLogSanitizer.shortId(rootId)}, errorClass=$errorClass, message=${AbsLogSanitizer.compact(message)}"
        )
    }

    fun logAuthorizedProgressSyncSuccess(remoteProgressCount: Int, appliedCount: Int, skippedByResolverCount: Int, skippedMissingBookCount: Int, failedRootCount: Int) {
        AbsLogEmitter.debug(
            TAG,
            "authorizedProgress success: remoteProgress=$remoteProgressCount, applied=$appliedCount, skippedByResolver=$skippedByResolverCount, skippedMissingBook=$skippedMissingBookCount, failedRoots=$failedRootCount"
        )
    }

    fun logAuthorizedProgressSyncFailure(errorClass: String, message: String?) {
        AbsLogEmitter.warn(
            TAG,
            "authorizedProgress failure: errorClass=$errorClass, message=${AbsLogSanitizer.compact(message)}"
        )
    }

    fun logAuthorizedProgressRootMerge(rootId: String, remoteProgressCount: Int, appliedCount: Int, skippedByResolverCount: Int, skippedMissingBookCount: Int, failedRootCount: Int) {
        AbsLogEmitter.debug(
            TAG,
            "authorizedProgress rootMerge: rootId=${AbsLogSanitizer.shortId(rootId)}, remoteProgress=$remoteProgressCount, applied=$appliedCount, skippedByResolver=$skippedByResolverCount, skippedMissingBook=$skippedMissingBookCount, failedRoots=$failedRootCount"
        )
    }
}
