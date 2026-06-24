package com.viel.oto.logger

/**
 * Media streaming and Range requests logging.
 *
 * Boundaries of responsibility:
 * 1. Only logs media streaming reads for AbsSourceProvider and its upper VFS entry points.
 * 2. Focuses on diagnosing streaming issues: contentUrl parsing, HEAD check latency, GET/Range response codes (e.g. 416, 401, 403, 404, 5xx).
 * 3. Does not log catalog syncs, playback session events, or settings view actions.
 */
internal object AbsStreamLogger {
    private const val TAG = "AbsStream"

    fun mark(): Long = AbsLogClock.mark()

    fun elapsedMs(startNs: Long): Long = AbsLogClock.elapsedMs(startNs)

    fun logResolveContentUrl(baseUrl: String, sourcePath: String, resolvedUrl: String) {
        AbsLogEmitter.debug(
            TAG,
            "resolveContentUrl: baseUrl=${AbsLogSanitizer.sanitizeUrl(baseUrl)}, sourcePath=${AbsLogSanitizer.compact(sourcePath)}, resolved=${AbsLogSanitizer.sanitizeUrl(resolvedUrl)}"
        )
    }

    fun logResolveContentUrlFailure(baseUrl: String, sourcePath: String, errorClass: String, message: String?) {
        AbsLogEmitter.warn(
            TAG,
            "resolveContentUrl failure: baseUrl=${AbsLogSanitizer.sanitizeUrl(baseUrl)}, sourcePath=${AbsLogSanitizer.compact(sourcePath)}, errorClass=$errorClass, message=${AbsLogSanitizer.compact(message)}"
        )
    }

    fun logOpenStart(rootId: String, sourcePath: String, offset: Long) {
        AbsLogEmitter.debug(
            TAG,
            "open start: rootId=${AbsLogSanitizer.shortId(rootId)}, sourcePath=${AbsLogSanitizer.compact(sourcePath)}, offset=$offset"
        )
    }

    fun logOpenSuccess(rootId: String, sourcePath: String, offset: Long, httpCode: Int, costMs: Long) {
        AbsLogEmitter.debug(
            TAG,
            "open success: rootId=${AbsLogSanitizer.shortId(rootId)}, sourcePath=${AbsLogSanitizer.compact(sourcePath)}, offset=$offset, http=$httpCode, cost=${costMs}ms"
        )
    }

    fun logOpenFailure(rootId: String, sourcePath: String, offset: Long, httpCode: Int?, costMs: Long, errorClass: String, message: String?) {
        AbsLogEmitter.warn(
            TAG,
            "open failure: rootId=${AbsLogSanitizer.shortId(rootId)}, sourcePath=${AbsLogSanitizer.compact(sourcePath)}, offset=$offset, http=${httpCode ?: -1}, cost=${costMs}ms, errorClass=$errorClass, message=${AbsLogSanitizer.compact(message)}"
        )
    }

    fun logRangeReadStart(rootId: String, sourcePath: String, offset: Long, length: Int) {
        AbsLogEmitter.debug(
            TAG,
            "range start: rootId=${AbsLogSanitizer.shortId(rootId)}, sourcePath=${AbsLogSanitizer.compact(sourcePath)}, offset=$offset, length=$length"
        )
    }

    fun logRangeReadSuccess(rootId: String, sourcePath: String, offset: Long, length: Int, actualBytes: Int, httpCode: Int, costMs: Long) {
        AbsLogEmitter.debug(
            TAG,
            "range success: rootId=${AbsLogSanitizer.shortId(rootId)}, sourcePath=${AbsLogSanitizer.compact(sourcePath)}, offset=$offset, length=$length, actualBytes=$actualBytes, http=$httpCode, cost=${costMs}ms"
        )
    }

    fun logRangeReadFailure(rootId: String, sourcePath: String, offset: Long, length: Int, httpCode: Int?, costMs: Long, errorClass: String, message: String?) {
        AbsLogEmitter.warn(
            TAG,
            "range failure: rootId=${AbsLogSanitizer.shortId(rootId)}, sourcePath=${AbsLogSanitizer.compact(sourcePath)}, offset=$offset, length=$length, http=${httpCode ?: -1}, cost=${costMs}ms, errorClass=$errorClass, message=${AbsLogSanitizer.compact(message)}"
        )
    }

    fun logExistsCheck(rootId: String, sourcePath: String, readable: Boolean, costMs: Long) {
        AbsLogEmitter.debug(
            TAG,
            "exists: rootId=${AbsLogSanitizer.shortId(rootId)}, sourcePath=${AbsLogSanitizer.compact(sourcePath)}, readable=$readable, cost=${costMs}ms"
        )
    }

    fun logRequestFailure(method: String, url: String?, errorClass: String, message: String?) {
        AbsLogEmitter.warn(
            TAG,
            "request failure: method=$method, url=${AbsLogSanitizer.sanitizeUrl(url)}, errorClass=$errorClass, message=${AbsLogSanitizer.compact(message)}"
        )
    }
}
