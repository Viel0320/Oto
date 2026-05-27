package com.viel.aplayer.logger

import android.os.SystemClock
import android.util.Log

/**
 * VFS 文件 I/O 操作耗时 Logger。
 * 统一覆盖 SAF (SafSourceProvider) 和 WebDAV (WebDavSourceProvider) 两条存储路径中
 * 与文件打开、定位、Range 读取、PROPFIND 目录枚举以及网络异常相关的所有日志输出。
 * 使用统一 TAG "VfsIO"，方便在 Logcat 中过滤整个虚拟文件系统 I/O 层的性能与异常诊断信息。
 */
internal object VfsLogger {

    private const val TAG = "VfsIO"
    // 长路径/URL 的最大显示长度，超出部分截断以保持 Logcat 可读性
    private const val MAX_PATH_LENGTH = 120

    // 用于标记计时起始点，使用 elapsedRealtime 以排除系统休眠和时间调整的影响
    fun mark(): Long = SystemClock.elapsedRealtime()

    // 将起始时间戳转换为已耗费的毫秒数
    fun elapsedMs(startMs: Long): Long = SystemClock.elapsedRealtime() - startMs

    // ========== SAF ==========

    /**
     * 记录 SAF openInputStream 操作的耗时和结果。
     *
     * @param path 文件的 sourcePath
     * @param offset 读取偏移量（0 表示顺序读流）
     * @param costMs 操作耗时（毫秒）
     * @param success 是否成功打开
     * @param error 失败时的异常类名（可选）
     */
    fun logSafOpen(path: String, offset: Long, costMs: Long, success: Boolean, error: String? = null) {
        val errorSuffix = error?.let { ", error=$it" }.orEmpty()
        Log.d(
            TAG,
            "SAF openInputStream(path=${compact(path)}, offset=$offset) " +
                "cost=${costMs}ms, success=$success$errorSuffix"
        )
    }

    /**
     * 记录 SAF openFileDescriptor 操作的耗时和结果。
     *
     * @param path 文件的 sourcePath
     * @param costMs 操作耗时（毫秒）
     * @param success 是否成功打开
     */
    fun logSafOpenFd(path: String, costMs: Long, success: Boolean) {
        Log.d(
            TAG,
            "SAF openFileDescriptor(path=${compact(path)}) cost=${costMs}ms, success=$success"
        )
    }

    // ========== WebDAV ==========

    /**
     * 记录 WebDAV PROPFIND 目录枚举请求的耗时和结果。
     *
     * @param sourcePath 请求的目录 sourcePath
     * @param depth PROPFIND 深度 ("0" 或 "1")
     * @param costMs 操作耗时（毫秒）
     * @param resourceCount 返回的资源数量
     */
    fun logWebDavPropfind(sourcePath: String, depth: String, costMs: Long, resourceCount: Int) {
        Log.d(
            TAG,
            "WebDAV PROPFIND(path=${compact(sourcePath)}, depth=$depth) " +
                "cost=${costMs}ms, resources=$resourceCount"
        )
    }

    /**
     * 记录 WebDAV GET/Range 流打开请求的耗时和结果。
     *
     * @param sourcePath 文件的 sourcePath
     * @param offset 读取偏移量
     * @param costMs 操作耗时（毫秒）
     * @param httpCode HTTP 响应状态码
     * @param success 是否成功打开
     */
    fun logWebDavOpen(sourcePath: String, offset: Long, costMs: Long, httpCode: Int, success: Boolean) {
        Log.d(
            TAG,
            "WebDAV GET(path=${compact(sourcePath)}, offset=$offset) " +
                "cost=${costMs}ms, http=$httpCode, success=$success"
        )
    }

    /**
     * 记录 WebDAV Range 片段读取请求的耗时和字节数。
     *
     * @param sourcePath 文件的 sourcePath
     * @param offset 读取偏移量
     * @param requestedLength 请求的字节数
     * @param costMs 操作耗时（毫秒）
     * @param actualBytes 实际读取的字节数（null 表示失败）
     */
    fun logWebDavRange(sourcePath: String, offset: Long, requestedLength: Int, costMs: Long, actualBytes: Int?) {
        Log.d(
            TAG,
            "WebDAV Range(path=${compact(sourcePath)}, offset=$offset, len=$requestedLength) " +
                "cost=${costMs}ms, actual=${actualBytes ?: "null"}"
        )
    }

    /**
     * 记录 WebDAV 请求因超时或网络不可用而失败的异常。
     *
     * @param url 请求的 URL
     * @param status 映射后的可用性状态
     * @param errorClass 异常类名
     */
    fun logWebDavError(url: String, status: String, errorClass: String) {
        Log.d(
            TAG,
            "WebDAV error url=${compact(url)}, status=$status, exception=$errorClass"
        )
    }

    /**
     * 将长路径或 URL 截断为可读长度，避免 Logcat 过长导致难以阅读。
     */
    private fun compact(value: String): String {
        return if (value.length <= MAX_PATH_LENGTH) {
            value
        } else {
            "${value.take(MAX_PATH_LENGTH)}..."
        }
    }
}
