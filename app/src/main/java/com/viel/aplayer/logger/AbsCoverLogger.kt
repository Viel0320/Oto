package com.viel.aplayer.logger

/**
 * ABS Cover Cache Logger (Cover cache operations logging)
 *
 * Boundaries of responsibility:
 * 1. Only logs cover download, cover processing, missing covers, and cover cache failures.
 * 2. Does not log catalog upsert or common cover self-healing processes, which belong to the public workflow.
 * 3. Designed to diagnose cover loading issues, slow sync, or physical file write failures.
 */
internal object AbsCoverLogger {
    private const val TAG = "AbsCover"

    fun mark(): Long = AbsLogClock.mark()

    fun elapsedMs(startNs: Long): Long = AbsLogClock.elapsedMs(startNs)

    fun logDownloadStart(rootId: String, remoteItemId: String) {
        AbsLogEmitter.debug(
            TAG,
            "download start: rootId=${AbsLogSanitizer.shortId(rootId)}, remoteItemId=${AbsLogSanitizer.shortId(remoteItemId)}"
        )
    }

    fun logDownloadSuccess(rootId: String, remoteItemId: String, contentType: String?, byteCount: Int, costMs: Long) {
        AbsLogEmitter.debug(
            TAG,
            "download success: rootId=${AbsLogSanitizer.shortId(rootId)}, remoteItemId=${AbsLogSanitizer.shortId(remoteItemId)}, contentType=${AbsLogSanitizer.compact(contentType, 40)}, bytes=$byteCount, cost=${costMs}ms"
        )
    }

    fun logDownloadFailure(rootId: String, remoteItemId: String, costMs: Long, errorClass: String, message: String?) {
        AbsLogEmitter.warn(
            TAG,
            "download failure: rootId=${AbsLogSanitizer.shortId(rootId)}, remoteItemId=${AbsLogSanitizer.shortId(remoteItemId)}, cost=${costMs}ms, errorClass=$errorClass, message=${AbsLogSanitizer.compact(message)}"
        )
    }

    fun logProcessSuccess(rootId: String, remoteItemId: String, originalPath: String?, thumbnailPath: String?, backgroundColor: Int?) {
        AbsLogEmitter.debug(
            TAG,
            "process success: rootId=${AbsLogSanitizer.shortId(rootId)}, remoteItemId=${AbsLogSanitizer.shortId(remoteItemId)}, original=${AbsLogSanitizer.compact(originalPath)}, thumbnail=${AbsLogSanitizer.compact(thumbnailPath)}, backgroundColor=$backgroundColor"
        )
    }

    fun logProcessFailure(rootId: String, remoteItemId: String, errorClass: String, message: String?) {
        AbsLogEmitter.warn(
            TAG,
            "process failure: rootId=${AbsLogSanitizer.shortId(rootId)}, remoteItemId=${AbsLogSanitizer.shortId(remoteItemId)}, errorClass=$errorClass, message=${AbsLogSanitizer.compact(message)}"
        )
    }
}
