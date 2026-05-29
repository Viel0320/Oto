package com.viel.aplayer.media

import android.content.Context
import androidx.media3.session.MediaController
import com.viel.aplayer.data.gateway.BookQueryGateway
import com.viel.aplayer.data.gateway.ProgressGateway
import com.viel.aplayer.data.entity.BookProgressEntity
import kotlinx.coroutines.*

/**
 * 
 * 进度同步追踪器（ProgressSyncTracker）。
 * 作为 PlaybackManager 的协从组件，用于将高频进度轮询与数据库持久化落库的逻辑从大单例中解耦出来。
 * 追踪器维持核心数据落盘功能，提供清晰的进度计算与回传生命周期控制。
 * 
 * 在 M4.2 重构中，将旧的 LibraryRepository 彻底解耦，拆分为 BookQueryGateway 以及 ProgressGateway，
 * 遵循高内聚低耦合的架构设计。
 */
class ProgressSyncTracker(
    private val context: Context,
    private val bookQueryGateway: BookQueryGateway,
    private val progressGateway: ProgressGateway,
    private val scope: CoroutineScope,
    private val getController: () -> MediaController?,
    private val getCurrentPlan: () -> BookPlaybackPlan?,
    private val onProgressUpdated: (positionMs: Long, durationMs: Long) -> Unit
) {
    // 专门用于轮询物理播放器进度的协程 Job 引用，由播放状态控制其激活或停用。
    private var pollingJob: Job? = null

    /**
     * 启动高频进度轮询协程。
     * 当检测到播放器正在播放时，每 500 毫秒刷新一次 UI 进度信息，每隔 20 次循环（累计 10 秒）安全保存一次当前的播放进度至 Room 数据库。
     * 轮询在协程生命周期 scope 结束或主动调用 stopPolling 时被安全取消。
     */
    fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = scope.launch {
            var saveCounter = 0
            while (isActive) {
                val controller = getController()
                if (controller != null && controller.isPlaying) {
                    // 更新当前的全局播放进度与全书总时长
                    updateProgress(controller)
                    saveCounter++
                    if (saveCounter >= 20) {
                        saveCounter = 0
                        // 到达 10 秒周期，立即执行一次数据库保存落盘
                        saveProgressDirectly(controller)
                    }
                }
                // 根据播放器当前的播放状态动态确定延迟等待时长，播放中为 500 毫秒以确保 UI 精准，暂停时为 2 秒以降低 CPU 负载。
                val delayTime = if (getController()?.isPlaying == true) 500L else 2000L
                delay(delayTime)
            }
        }
    }

    /**
     * 停止高频进度轮询协程。
     * 安全取消当前运行的 pollingJob，并将引用置空，确保不发生协程泄漏。
     */
    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    /**
     * 计算当前的全局播放位置与时长。
     * 读取当前的播放计划 files 列表，结合 MediaController 物理上的当前文件索引与文件内偏移，
     * 通过 PositionMapper 的工具算法换算出全局连续的毫秒级进度，并通过回调推回 PlaybackManager 刷新全局 Flow 状态。
     *
     * @param player 当前已就绪的 MediaController 实例
     */
    fun updateProgress(player: MediaController) {
        val plan = getCurrentPlan()
        if (plan != null && plan.files.isNotEmpty() && player.currentMediaItem != null) {
            val fileIndex = player.currentMediaItemIndex.coerceIn(0, plan.files.lastIndex)
            val positionInFile = player.currentPosition.coerceAtLeast(0L)
            val totalDur = plan.files.sumOf { it.durationMs }
            val globalPos = PositionMapper.fileToGlobalPosition(fileIndex, positionInFile, plan.files)
                .coerceIn(0L, totalDur.coerceAtLeast(0L))
            onProgressUpdated(globalPos, totalDur)
        } else {
            // 若无有效播放计划，则直接回传底层播放器原本的单文件物理进度
            onProgressUpdated(
                player.currentPosition.coerceAtLeast(0L),
                player.duration.coerceAtLeast(0L)
            )
        }
    }

    /**
     * 执行即时进度保存落库。
     * 适用于状态突变（如暂停、切换音轨）或外部主动调用存盘的场景。
     */
    fun saveProgress() {
        val controller = getController() ?: return
        saveProgressDirectly(controller)
    }

    /**
     * 内部底层执行进度入库持久化的具体实现。
     * 将物理的媒体 mediaId 解析还原为业务 bookId，安全计算全局偏移后构造并插入 BookProgressEntity 数据实体。
     *
     * @param controller 当前的 MediaController 物理控制器实例
     */
    private fun saveProgressDirectly(controller: MediaController) {
        val mediaId = controller.currentMediaItem?.mediaId ?: return
        if (!mediaId.contains(":")) return

        val bookId = mediaId.substringBefore(":")
        val fileIndex = controller.currentMediaItemIndex.coerceAtLeast(0)
        val positionInFile = controller.currentPosition.coerceAtLeast(0L)

        scope.launch {
            // 使用解耦后的 bookQueryGateway 网关服务获取书籍相关的全部音频分轨文件列表
            val files = bookQueryGateway.getFilesForBookSync(bookId)
            if (files.isNotEmpty()) {
                val globalPos = PositionMapper.fileToGlobalPosition(fileIndex, positionInFile, files)
                val bookFileId = files.getOrNull(fileIndex)?.id

                // 使用专门用于进度持久化的 progressGateway 异步落库当前的播放进度
                progressGateway.saveProgress(
                    BookProgressEntity(
                        bookId = bookId,
                        globalPositionMs = globalPos,
                        bookFileId = bookFileId,
                        currentFileIndex = fileIndex,
                        positionInFileMs = positionInFile,
                        lastPlayedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    /**
     * 强制持久化保存指定的进度信息。
     * 主要用于跳转定位（seekTo）、首次载入播放计划（applyPlaybackPlan）等需要直接修改指定进度的场景，
     * 绕过对 MediaController 瞬时状态的获取依赖，在 IO 线程池中物理执行写入。
     *
     * @param bookId 目标书籍 ID
     * @param fileIndex 目标文件在播放列表中的索引
     * @param positionInFile 文件内部的偏移毫秒数
     */
    fun persistProgress(bookId: String, fileIndex: Int, positionInFile: Long) {
        scope.launch {
            // 使用解耦后的 bookQueryGateway 只读网关服务同步获取最新的书籍音频分轨列表
            val files = bookQueryGateway.getFilesForBookSync(bookId)
            if (files.isNotEmpty()) {
                val safeFileIndex = fileIndex.coerceIn(0, files.lastIndex)
                val safePositionInFile = positionInFile.coerceAtLeast(0L)
                val globalPos = PositionMapper.fileToGlobalPosition(safeFileIndex, safePositionInFile, files)
                    .coerceIn(0L, files.sumOf { it.durationMs }.coerceAtLeast(0L))
                val bookFileId = files.getOrNull(safeFileIndex)?.id

                // 调用 progressGateway 网关将高精度的定位快照保存落库
                progressGateway.saveProgress(
                    BookProgressEntity(
                        bookId = bookId,
                        globalPositionMs = globalPos,
                        bookFileId = bookFileId,
                        currentFileIndex = safeFileIndex,
                        positionInFileMs = safePositionInFile,
                        lastPlayedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }
}
