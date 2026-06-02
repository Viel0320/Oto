package com.viel.aplayer.logger

/**
 * ABS 封面缓存链路日志。
 *
 * 责任边界：
 * 1. 只记录 cover 下载、cover 处理、cover 缺失、cover 缓存失败。
 * 2. 不记录 catalog upsert，不记录通用封面自愈链，那是公共流程。
 * 3. 用于排查“为什么 ABS 书没有封面 / 为什么同步很慢 / 为什么封面缓存失败”。
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
