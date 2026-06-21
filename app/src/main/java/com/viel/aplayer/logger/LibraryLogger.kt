package com.viel.aplayer.logger

import android.os.SystemClock
import android.util.Log

/**
 * Track repository CRUD and playback plan build performance.
 *
 * Collects and logs database queries, execution durations, and file operations
 * (such as cover and thumbnail cache deletions) within the library repository layer.
 * Uses a unified tag "Library" to simplify diagnosis in Logcat.
 */
internal object LibraryLogger {

    private const val TAG = "Library"

    fun elapsedMs(startMs: Long): Long = SystemClock.elapsedRealtime() - startMs

    /**
     * Record performance metrics when no playable files are found.
     *
     * Tracks the exact time spent querying books, files, and progress for empty plans.
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
            "buildPlaybackPlan($bookId) 无可播放文件, book=${bookQueryMs}ms, " +
                "files=${filesQueryMs}ms, progress=${progressQueryMs}ms, total=${totalMs}ms"
        )
    }

    /**
     * Record performance metrics when a plan is successfully constructed.
     *
     * Tracks elapsed durations for querying books, files, and progress to diagnose plan generation latency.
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
            "buildPlaybackPlan($bookId) 完成, book=${bookQueryMs}ms, files=${filesQueryMs}ms, " +
                "progress=${progressQueryMs}ms, total=${totalMs}ms, " +
                "files=$fileCount, start=$startPosition"
        )
    }

    /**
     * Record the outcome of deleting local cover images.
     *
     * Tracks whether the physical cover file was successfully removed from the storage device.
     *
     * @param bookId The unique identifier of the book.
     * @param path The absolute path to the deleted cover cache file.
     * @param deleted True if the deletion succeeded, false otherwise.
     */
    fun logCoverDeleted(bookId: String, path: String, deleted: Boolean) {
        Log.d(TAG, "物理删除有声书封面缓存文件成功，书籍ID: $bookId, 路径: $path, 结果: $deleted")
    }

    /**
     * Record the outcome of deleting local cover thumbnail images.
     *
     * Tracks whether the physical thumbnail file was successfully removed from the storage device.
     *
     * @param bookId The unique identifier of the book.
     * @param path The absolute path to the deleted thumbnail cache file.
     * @param deleted True if the deletion succeeded, false otherwise.
     */
    fun logThumbnailDeleted(bookId: String, path: String, deleted: Boolean) {
        Log.d(TAG, "物理删除有声书缩略图缓存文件成功，书籍ID: $bookId, 路径: $path, 结果: $deleted")
    }
}
