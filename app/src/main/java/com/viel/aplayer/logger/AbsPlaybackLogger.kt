package com.viel.aplayer.logger

/**
 * ABS 播放会话与远端进度同步日志。
 *
 * 责任边界：
 * 1. 只记录 open/sync/close/pending flush/credential resolve 这些“远端会话”相关事件。
 * 2. 不记录本地播放计划构建、不记录底层字节流读取。
 * 3. 专注回答“本地进度有没有尝试同步到 ABS，失败时是否进入 pending”。
 */
internal object AbsPlaybackLogger {
    private const val TAG = "AbsPlayback"

    fun mark(): Long = AbsLogClock.mark()

    fun elapsedMs(startNs: Long): Long = AbsLogClock.elapsedMs(startNs)

    fun logResolveCredentialStart(bookId: String, rootId: String) {
        AbsLogEmitter.debug(
            TAG,
            "resolveCredential start: bookId=${AbsLogSanitizer.shortId(bookId)}, rootId=${AbsLogSanitizer.shortId(rootId)}"
        )
    }

    fun logResolveCredentialResult(bookId: String, rootId: String, foundRoot: Boolean, foundCredential: Boolean) {
        AbsLogEmitter.debug(
            TAG,
            "resolveCredential result: bookId=${AbsLogSanitizer.shortId(bookId)}, rootId=${AbsLogSanitizer.shortId(rootId)}, foundRoot=$foundRoot, foundCredential=$foundCredential"
        )
    }

    fun logOpenSessionStart(bookId: String, remoteItemId: String) {
        AbsLogEmitter.debug(
            TAG,
            "openSession start: bookId=${AbsLogSanitizer.shortId(bookId)}, remoteItemId=${AbsLogSanitizer.shortId(remoteItemId)}"
        )
    }

    fun logOpenSessionSkipped(bookId: String, reason: String) {
        AbsLogEmitter.debug(
            TAG,
            "openSession skipped: bookId=${AbsLogSanitizer.shortId(bookId)}, reason=${AbsLogSanitizer.compact(reason, 64)}"
        )
    }

    fun logOpenSessionSuccess(bookId: String, remoteItemId: String, sessionId: String, costMs: Long) {
        AbsLogEmitter.debug(
            TAG,
            "openSession success: bookId=${AbsLogSanitizer.shortId(bookId)}, remoteItemId=${AbsLogSanitizer.shortId(remoteItemId)}, sessionId=${AbsLogSanitizer.shortId(sessionId)}, cost=${costMs}ms"
        )
    }

    fun logOpenSessionFailure(bookId: String, remoteItemId: String, costMs: Long, errorClass: String, message: String?) {
        AbsLogEmitter.warn(
            TAG,
            "openSession failure: bookId=${AbsLogSanitizer.shortId(bookId)}, remoteItemId=${AbsLogSanitizer.shortId(remoteItemId)}, cost=${costMs}ms, errorClass=$errorClass, message=${AbsLogSanitizer.compact(message)}"
        )
    }

    fun logSyncStart(bookId: String, sessionId: String, currentTimeSec: Double, durationSec: Double) {
        AbsLogEmitter.debug(
            TAG,
            "sync start: bookId=${AbsLogSanitizer.shortId(bookId)}, sessionId=${AbsLogSanitizer.shortId(sessionId)}, currentTimeSec=$currentTimeSec, durationSec=$durationSec"
        )
    }

    fun logSyncSuccess(bookId: String, sessionId: String, costMs: Long) {
        AbsLogEmitter.debug(
            TAG,
            "sync success: bookId=${AbsLogSanitizer.shortId(bookId)}, sessionId=${AbsLogSanitizer.shortId(sessionId)}, cost=${costMs}ms"
        )
    }

    fun logSyncPending(bookId: String, sessionId: String, costMs: Long, errorClass: String, message: String?) {
        AbsLogEmitter.warn(
            TAG,
            "sync pending: bookId=${AbsLogSanitizer.shortId(bookId)}, sessionId=${AbsLogSanitizer.shortId(sessionId)}, cost=${costMs}ms, errorClass=$errorClass, message=${AbsLogSanitizer.compact(message)}"
        )
    }

    fun logCloseStart(bookId: String, sessionId: String, currentTimeSec: Double, durationSec: Double) {
        AbsLogEmitter.debug(
            TAG,
            "close start: bookId=${AbsLogSanitizer.shortId(bookId)}, sessionId=${AbsLogSanitizer.shortId(sessionId)}, currentTimeSec=$currentTimeSec, durationSec=$durationSec"
        )
    }

    fun logCloseSuccess(bookId: String, sessionId: String, costMs: Long) {
        AbsLogEmitter.debug(
            TAG,
            "close success: bookId=${AbsLogSanitizer.shortId(bookId)}, sessionId=${AbsLogSanitizer.shortId(sessionId)}, cost=${costMs}ms"
        )
    }

    fun logClosePending(bookId: String, sessionId: String, costMs: Long, errorClass: String, message: String?) {
        AbsLogEmitter.warn(
            TAG,
            "close pending: bookId=${AbsLogSanitizer.shortId(bookId)}, sessionId=${AbsLogSanitizer.shortId(sessionId)}, cost=${costMs}ms, errorClass=$errorClass, message=${AbsLogSanitizer.compact(message)}"
        )
    }

    fun logFlushPendingStart(bookId: String, sessionId: String) {
        AbsLogEmitter.debug(
            TAG,
            "flushPending start: bookId=${AbsLogSanitizer.shortId(bookId)}, sessionId=${AbsLogSanitizer.shortId(sessionId)}"
        )
    }

    fun logFlushPendingSkipped(bookId: String, reason: String) {
        AbsLogEmitter.debug(
            TAG,
            "flushPending skipped: bookId=${AbsLogSanitizer.shortId(bookId)}, reason=${AbsLogSanitizer.compact(reason, 64)}"
        )
    }

    fun logFlushPendingSuccess(bookId: String, sessionId: String, costMs: Long) {
        AbsLogEmitter.debug(
            TAG,
            "flushPending success: bookId=${AbsLogSanitizer.shortId(bookId)}, sessionId=${AbsLogSanitizer.shortId(sessionId)}, cost=${costMs}ms"
        )
    }

    fun logFlushPendingFailure(bookId: String, sessionId: String, costMs: Long, errorClass: String, message: String?) {
        AbsLogEmitter.warn(
            TAG,
            "flushPending failure: bookId=${AbsLogSanitizer.shortId(bookId)}, sessionId=${AbsLogSanitizer.shortId(sessionId)}, cost=${costMs}ms, errorClass=$errorClass, message=${AbsLogSanitizer.compact(message)}"
        )
    }
}
