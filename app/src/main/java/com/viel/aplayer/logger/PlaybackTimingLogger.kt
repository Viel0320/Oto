package com.viel.aplayer.logger

import android.os.SystemClock
import android.util.Log

/**
 * 播放链路性能计时 Logger。
 * 统一收纳 PlaybackManager / PlayerViewModel / VfsPlaybackDataSource / BookLibraryRepository
 * 中与播放计划构建、下发、MediaItems 构建、autoplay 消费、DataSource 打开等相关的所有性能打点日志。
 * 使用统一 TAG "PlaybackTiming"，方便在 Logcat 中通过单一 TAG 过滤整条播放链路耗时。
 */
internal object PlaybackTimingLogger {

    private const val TAG = "PlaybackTiming"

    // 用于标记计时起始点，使用 elapsedRealtime 以排除系统休眠和时间调整的影响
    fun mark(): Long = SystemClock.elapsedRealtime()

    // 将起始时间戳转换为已耗费的毫秒数
    fun elapsedMs(startMs: Long): Long = SystemClock.elapsedRealtime() - startMs

    // ========== PlaybackManager ==========

    /**
     * 记录 setBookPlaybackPlan 入口时的初始参数与设置读取耗时。
     */
    fun logSetPlanEntry(
        bookId: String,
        settingsReadMs: Long,
        originalStart: Long,
        finalStart: Long,
        fileCount: Int,
        playWhenReady: Boolean
    ) {
        Log.d(
            TAG,
            "setBookPlaybackPlan($bookId) settingsRead=${settingsReadMs}ms, " +
                "原始起点=$originalStart, 最终起点=$finalStart, files=$fileCount, " +
                "playWhenReady=$playWhenReady"
        )
    }

    /**
     * 记录 setBookPlaybackPlan 从入口到调用 applyPlaybackPlan 之间的前置总耗时。
     */
    fun logPreApplyCost(bookId: String, preApplyCostMs: Long) {
        Log.d(
            TAG,
            "setBookPlaybackPlan($bookId) 即将调用 applyPlaybackPlan, 前置总耗时=${preApplyCostMs}ms"
        )
    }

    /**
     * 记录 applyPlaybackPlan 中 MediaItems 构建和 Controller 下发各阶段的耗时。
     */
    fun logApplyPlan(
        bookId: String,
        mediaItemsBuildMs: Long,
        controllerDispatchMs: Long,
        totalMs: Long,
        fileCount: Int,
        fileIndex: Int,
        positionInFile: Long
    ) {
        Log.d(
            TAG,
            "applyPlaybackPlan($bookId) mediaItems构建=${mediaItemsBuildMs}ms, " +
                "controller下发=${controllerDispatchMs}ms, total=${totalMs}ms, " +
                "files=$fileCount, fileIndex=$fileIndex, positionInFile=$positionInFile"
        )
    }

    /**
     * 记录 autoplay 请求已被消费并调用 play() 的事件。
     */
    fun logAutoplayConsumed(bookId: String) {
        Log.d(TAG, "applyPlaybackPlan($bookId) 已消费 autoplay 请求并调用 play()")
    }

    /**
     * 记录 MediaController 尚未就绪导致 applyPlaybackPlan 被跳过。
     */
    fun logApplyPlanSkipped(bookId: String, totalMs: Long) {
        Log.d(
            TAG,
            "applyPlaybackPlan($bookId) 跳过, mediaController 尚未就绪, total=${totalMs}ms"
        )
    }

    // ========== PlayerViewModel ==========

    /**
     * 记录 loadBook 中播放计划构建耗时。
     */
    fun logPlaybackPlanBuild(bookId: String, costMs: Long, planReady: Boolean, playWhenReady: Boolean) {
        Log.d(
            TAG,
            "loadBook($bookId) 播放计划构建耗时=${costMs}ms, planReady=$planReady, " +
                "playWhenReady=$playWhenReady"
        )
    }

    /**
     * 记录 loadBook 准备交给 PlaybackDelegate 时的总耗时。
     */
    fun logLoadBookReady(bookId: String, totalMs: Long, fileCount: Int, startPosition: Long) {
        Log.d(
            TAG,
            "loadBook($bookId) 即将交给 PlaybackDelegate, 总耗时=${totalMs}ms, " +
                "files=$fileCount, start=$startPosition"
        )
    }

    /**
     * 记录 loadBook 未能生成播放计划。
     */
    fun logLoadBookNoPlan(bookId: String, totalMs: Long) {
        Log.d(TAG, "loadBook($bookId) 未生成播放计划, 总耗时=${totalMs}ms")
    }

    // ========== VfsPlaybackDataSource ==========

    /**
     * 记录 VfsPlaybackDataSource.open() 中数据库查询和 VFS 流打开各阶段耗时。
     */
    fun logDataSourceOpen(
        bookFileId: String,
        offset: Long,
        dbCostMs: Long,
        vfsCostMs: Long,
        totalMs: Long,
        fileSize: Long
    ) {
        Log.d(
            TAG,
            "VfsDataSource.open(bookFileId=$bookFileId, offset=$offset) " +
                "db=${dbCostMs}ms, vfs=${vfsCostMs}ms, total=${totalMs}ms, fileSize=$fileSize"
        )
    }
}
