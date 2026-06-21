package com.viel.aplayer.logger

/**
 * Playback session and remote progress synchronization logging.
 *
 * Boundaries of responsibility:
 * 1. Only logs events related to remote sessions like open, sync, close, pending flush, and credential resolution.
 * 2. Does not log local playback plan construction or low-level byte stream reads.
 * 3. Focuses on whether local progress successfully syncs with the ABS server or falls back to pending queue states upon failure.
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

    fun logSyncSkipped(bookId: String, sessionId: String, reason: String) {
        AbsLogEmitter.debug(
            TAG,
            "sync skipped: bookId=${AbsLogSanitizer.shortId(bookId)}, sessionId=${AbsLogSanitizer.shortId(sessionId)}, reason=${AbsLogSanitizer.compact(reason, 64)}"
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

    fun logCloseSkipped(bookId: String, sessionId: String, reason: String) {
        AbsLogEmitter.debug(
            TAG,
            "close skipped: bookId=${AbsLogSanitizer.shortId(bookId)}, sessionId=${AbsLogSanitizer.shortId(sessionId)}, reason=${AbsLogSanitizer.compact(reason, 64)}"
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
