package com.viel.aplayer.logger

import android.os.SystemClock
import android.util.Log

/**
 * 书库仓库 CRUD 及播放计划耗时 Logger。
 * 统一收纳 BookLibraryRepository 中与播放计划构建查询耗时、
 * 封面/缩略图物理缓存删除等相关的所有日志输出。
 * 使用统一 TAG "Library"，方便在 Logcat 中过滤书库仓库层的操作诊断信息。
 */
internal object LibraryLogger {

    private const val TAG = "Library"

    // 用于标记计时起始点，使用 elapsedRealtime 以排除系统休眠和时间调整的影响
    fun mark(): Long = SystemClock.elapsedRealtime()

    // 将起始时间戳转换为已耗费的毫秒数
    fun elapsedMs(startMs: Long): Long = SystemClock.elapsedRealtime() - startMs

    /**
     * 记录 getPlaybackPlan 查询无可播放文件的耗时明细。
     */
    fun logPlaybackPlanEmpty(
        bookId: String,
        bookQueryMs: Long,
        filesQueryMs: Long,
        progressQueryMs: Long,
        totalMs: Long
    ) {
        Log.d(
            TAG,
            "getPlaybackPlan($bookId) 无可播放文件, book=${bookQueryMs}ms, " +
                "files=${filesQueryMs}ms, progress=${progressQueryMs}ms, total=${totalMs}ms"
        )
    }

    /**
     * 记录 getPlaybackPlan 查询完成的耗时明细。
     */
    fun logPlaybackPlanReady(
        bookId: String,
        bookQueryMs: Long,
        filesQueryMs: Long,
        progressQueryMs: Long,
        totalMs: Long,
        fileCount: Int,
        startPosition: Long
    ) {
        Log.d(
            TAG,
            "getPlaybackPlan($bookId) 完成, book=${bookQueryMs}ms, files=${filesQueryMs}ms, " +
                "progress=${progressQueryMs}ms, total=${totalMs}ms, " +
                "files=$fileCount, start=$startPosition"
        )
    }

    /**
     * 记录物理删除有声书封面缓存文件的结果。
     *
     * @param bookId 书籍 ID
     * @param path 被删除文件的路径
     * @param deleted 删除是否成功
     */
    fun logCoverDeleted(bookId: String, path: String, deleted: Boolean) {
        Log.d(TAG, "物理删除有声书封面缓存文件成功，书籍ID: $bookId, 路径: $path, 结果: $deleted")
    }

    /**
     * 记录物理删除有声书缩略图缓存文件的结果。
     *
     * @param bookId 书籍 ID
     * @param path 被删除文件的路径
     * @param deleted 删除是否成功
     */
    fun logThumbnailDeleted(bookId: String, path: String, deleted: Boolean) {
        Log.d(TAG, "物理删除有声书缩略图缓存文件成功，书籍ID: $bookId, 路径: $path, 结果: $deleted")
    }
}
