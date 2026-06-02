package com.viel.aplayer.logger

/**
 * ABS 媒体流读取与 Range 请求日志。
 *
 * 责任边界：
 * 1. 只记录 AbsSourceProvider 与其上游 VFS 入口的读流行为。
 * 2. 专注定位 contentUrl 解析、HEAD 可达性、GET/Range、416/401/403/404/5xx 等流媒体风险点。
 * 3. 不记录目录同步、不记录播放会话、不记录设置页动作。
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
