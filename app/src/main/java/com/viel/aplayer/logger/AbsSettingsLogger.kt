package com.viel.aplayer.logger

/**
 * ABS 设置页与用户操作入口日志。
 *
 * 责任边界：
 * 1. 只记录“测试连接、添加服务器、手动同步、调度后台同步、删除入口”这类用户动作。
 * 2. 不直接记录底层 REST、同步明细、读流事件，那些由对应专属 logger 负责。
 * 3. 用于把用户操作和后端链路串起来，方便排查“点了什么、走到了哪一步”。
 */
internal object AbsSettingsLogger {
    private const val TAG = "AbsSettings"

    fun mark(): Long = AbsLogClock.mark()

    fun elapsedMs(startNs: Long): Long = AbsLogClock.elapsedMs(startNs)

    fun logTestConnectionStart(baseUrl: String, username: String) {
        AbsLogEmitter.debug(
            TAG,
            "testConnection start: baseUrl=${AbsLogSanitizer.sanitizeUrl(baseUrl)}, username=${AbsLogSanitizer.compact(username, 48)}"
        )
    }

    fun logTestConnectionSuccess(baseUrl: String, username: String, costMs: Long, libraryCount: Int, serverVersion: String?) {
        AbsLogEmitter.debug(
            TAG,
            "testConnection success: baseUrl=${AbsLogSanitizer.sanitizeUrl(baseUrl)}, username=${AbsLogSanitizer.compact(username, 48)}, cost=${costMs}ms, libraries=$libraryCount, serverVersion=${AbsLogSanitizer.compact(serverVersion, 32)}"
        )
    }

    fun logTestConnectionFailure(baseUrl: String, username: String, costMs: Long, errorClass: String, message: String?) {
        AbsLogEmitter.warn(
            TAG,
            "testConnection failure: baseUrl=${AbsLogSanitizer.sanitizeUrl(baseUrl)}, username=${AbsLogSanitizer.compact(username, 48)}, cost=${costMs}ms, errorClass=$errorClass, message=${AbsLogSanitizer.compact(message)}"
        )
    }

    fun logAddServerStart(baseUrl: String, username: String, libraryId: String, libraryName: String) {
        AbsLogEmitter.debug(
            TAG,
            "addServer start: baseUrl=${AbsLogSanitizer.sanitizeUrl(baseUrl)}, username=${AbsLogSanitizer.compact(username, 48)}, libraryId=${AbsLogSanitizer.shortId(libraryId)}, libraryName=${AbsLogSanitizer.compact(libraryName, 64)}"
        )
    }

    fun logAddServerSuccess(baseUrl: String, username: String, libraryId: String, rootId: String?) {
        AbsLogEmitter.debug(
            TAG,
            "addServer success: baseUrl=${AbsLogSanitizer.sanitizeUrl(baseUrl)}, username=${AbsLogSanitizer.compact(username, 48)}, libraryId=${AbsLogSanitizer.shortId(libraryId)}, rootId=${AbsLogSanitizer.shortId(rootId)}"
        )
    }

    fun logAddServerFailure(baseUrl: String, username: String, libraryId: String, errorClass: String, message: String?) {
        AbsLogEmitter.warn(
            TAG,
            "addServer failure: baseUrl=${AbsLogSanitizer.sanitizeUrl(baseUrl)}, username=${AbsLogSanitizer.compact(username, 48)}, libraryId=${AbsLogSanitizer.shortId(libraryId)}, errorClass=$errorClass, message=${AbsLogSanitizer.compact(message)}"
        )
    }

    fun logManualSyncStart(rootId: String, displayName: String) {
        AbsLogEmitter.debug(
            TAG,
            "manualSync start: rootId=${AbsLogSanitizer.shortId(rootId)}, displayName=${AbsLogSanitizer.compact(displayName, 64)}"
        )
    }

    fun logManualSyncRequiresConfirmation(rootId: String, totalItems: Int) {
        AbsLogEmitter.debug(
            TAG,
            "manualSync requiresConfirmation: rootId=${AbsLogSanitizer.shortId(rootId)}, totalItems=$totalItems"
        )
    }

    fun logManualSyncFinished(rootId: String, costMs: Long) {
        AbsLogEmitter.debug(
            TAG,
            "manualSync success: rootId=${AbsLogSanitizer.shortId(rootId)}, cost=${costMs}ms"
        )
    }

    fun logManualSyncFailure(rootId: String, costMs: Long, errorClass: String, message: String?) {
        AbsLogEmitter.warn(
            TAG,
            "manualSync failure: rootId=${AbsLogSanitizer.shortId(rootId)}, cost=${costMs}ms, errorClass=$errorClass, message=${AbsLogSanitizer.compact(message)}"
        )
    }

    fun logScheduleBackgroundSync(rootId: String) {
        AbsLogEmitter.debug(
            TAG,
            "backgroundSync enqueue: rootId=${AbsLogSanitizer.shortId(rootId)}"
        )
    }

    fun logDeleteServerStart(rootId: String, sourceType: String) {
        AbsLogEmitter.debug(
            TAG,
            "deleteServer start: rootId=${AbsLogSanitizer.shortId(rootId)}, sourceType=${AbsLogSanitizer.compact(sourceType, 24)}"
        )
    }

    fun logDeleteServerFinished(rootId: String, playbackStopped: Boolean) {
        AbsLogEmitter.debug(
            TAG,
            "deleteServer success: rootId=${AbsLogSanitizer.shortId(rootId)}, playbackStopped=$playbackStopped"
        )
    }

    fun logDeleteServerFailure(rootId: String, errorClass: String, message: String?) {
        AbsLogEmitter.warn(
            TAG,
            "deleteServer failure: rootId=${AbsLogSanitizer.shortId(rootId)}, errorClass=$errorClass, message=${AbsLogSanitizer.compact(message)}"
        )
    }
}
