package com.viel.aplayer.logger

import android.os.SystemClock
import android.util.Log

/**
 * Track time duration metrics across the playback pipeline.
 *
 * Collects latency logs from PlaybackManager, PlayerViewModel, VfsPlaybackDataSource, and
 * BookLibraryRepository regarding plan construction, media item mapping, controller updates,
 * autoplay handling, and data source open operations.
 * Uses a unified tag "PlaybackTiming" to isolate playback performance in Logcat.
 */
internal object PlaybackTimingLogger {

    private const val TAG = "PlaybackTiming"

    fun elapsedMs(startMs: Long): Long = SystemClock.elapsedRealtime() - startMs

    /**
     * Record parameters and settings-read latency at plan entry.
     *
     * Captures the configuration status before constructing playback plans.
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
     * Record setup time prior to applying the plan.
     *
     * Tracks the interval between plan setup start and application trigger.
     */
    fun logPreApplyCost(bookId: String, preApplyCostMs: Long) {
        Log.d(
            TAG,
            "setBookPlaybackPlan($bookId) 即将调用 applyPlaybackPlan, 前置总耗时=${preApplyCostMs}ms"
        )
    }

    /**
     * Record latency of MediaItem creation and controller updates.
     *
     * Tracks time taken to compile items and notify the underlying media controller.
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
     * Record successful trigger of the play command.
     *
     * Traces when the initial play request is fulfilled after setup.
     */
    fun logAutoplayConsumed(bookId: String) {
        Log.d(TAG, "applyPlaybackPlan($bookId) 已消费 autoplay 请求并调用 play()")
    }

    /**
     * Record when plan application is bypassed because controller is unready.
     *
     * Tracks instances where setup fails to complete immediately due to controller state.
     */
    fun logApplyPlanSkipped(bookId: String, totalMs: Long) {
        Log.d(
            TAG,
            "applyPlaybackPlan($bookId) 跳过, mediaController 尚未就绪, total=${totalMs}ms"
        )
    }

    /**
     * Record construction latency during book load.
     *
     * Helps diagnose delays in fetching records to form the playback sequence.
     */
    fun logPlaybackPlanBuild(bookId: String, costMs: Long, planReady: Boolean, playWhenReady: Boolean) {
        Log.d(
            TAG,
            "loadBook($bookId) 播放计划构建耗时=${costMs}ms, planReady=$planReady, " +
                "playWhenReady=$playWhenReady"
        )
    }

    /**
     * Record total load time before routing to delegate.
     *
     * Captures total latency experienced from initiating load to final hand-off.
     */
    fun logLoadBookReady(bookId: String, totalMs: Long, fileCount: Int, startPosition: Long) {
        Log.d(
            TAG,
            "loadBook($bookId) 即将交给 PlaybackDelegate, 总耗时=${totalMs}ms, " +
                "files=$fileCount, start=$startPosition"
        )
    }

    /**
     * Record duration when no plan is generated.
     *
     * Traces latency when a book fails to generate a playable structure.
     */
    fun logLoadBookNoPlan(bookId: String, totalMs: Long) {
        Log.d(TAG, "loadBook($bookId) 未生成播放计划, 总耗时=${totalMs}ms")
    }

    /**
     * Record durations for DB queries and VFS stream access.
     *
     * Pinpoints performance bottlenecks during ExoPlayer's data source instantiation.
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
