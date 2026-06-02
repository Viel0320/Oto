package com.viel.aplayer.media

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import com.viel.aplayer.data.AppSettingsRepository
import com.viel.aplayer.logger.PlaybackWorkflowLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 自动回退管理类 (AutoRewindManager)
 * 负责管理自动回退相关的状态变量、设置参数读取、在暂停时应用回退，以及冷启动进度自愈等核心业务逻辑。
 * 遵循高内聚、低耦合的设计原则，从 PlaybackManager 中独立出来，方便单独维护与扩展。
 */
@OptIn(UnstableApi::class)
class AutoRewindManager private constructor(context: Context) {

    // 应用程序的全局上下文，避免内存泄漏
    private val appContext = context.applicationContext

    // 实例化设置仓库，用于异步读取和写入用户的回退设置以及播放中断标志
    private val settingsRepository = AppSettingsRepository.getInstance(appContext)

    /**
     * 临时状态标志：控制是否忽略下一次自动回退动作。
     * 当为 true 时，下一次暂停事件将不会触发回退，用以拦截由于音频焦点临时丢失、主动停止或切换书籍导致的误回退。
     */
    var ignoreNextAutoRewind: Boolean = false

    /**
     * 处理播放暂停状态跃迁时的自动回退逻辑。
     * 当检测到正在播放状态转为暂停状态时调用。
     * 
     * @param controller 媒体控制器实例，用于进行具体的时间定位寻址
     * @param currentPlan 当前书籍的播放计划，包含多分轨文件列表，用于跨音轨高精度定位
     * @param scope 协程作用域，用于执行异步读取设置与进度落盘的操作
     * @param onProgressUpdated 回调函数，当回退完成后，用于通知主控制器刷新当前的全局进度流
     * @param onSaveProgress 回调函数，当回退完成后，用于立即将回退后的新进度落盘持久化保存到数据库中
     */
    fun handlePause(
        controller: MediaController?,
        currentPlan: BookPlaybackPlan?,
        scope: CoroutineScope,
        onProgressUpdated: (MediaController) -> Unit,
        onSaveProgress: () -> Unit
    ) {
        // 如果检测到 ignoreNextAutoRewind 为 true，说明此番暂停因临时失去焦点而被动触发，我们应跳过回退逻辑，并将标志重置为 false。
        if (ignoreNextAutoRewind) {
            ignoreNextAutoRewind = false
            return
        }

        // 调用内部的核心自动回退寻址定位逻辑
        applyAutoRewind(controller, currentPlan, scope, onProgressUpdated, onSaveProgress)
    }

    /**
     * 执行暂停自动回退功能的核心算法逻辑。
     * 异步读取最新的回退时长设置，并结合当前的播放状态与多分轨计划，执行单轨或跨音轨的精准定位回退。
     */
    private fun applyAutoRewind(
        controller: MediaController?,
        plan: BookPlaybackPlan?,
        scope: CoroutineScope,
        onProgressUpdated: (MediaController) -> Unit,
        onSaveProgress: () -> Unit
    ) {
        if (controller == null) return
        scope.launch {
            try {
                // 挂起并获取 DataStore 中的最新设置快照，确保读取到最新的回退时长
                val settings = settingsRepository.settingsFlow.first()
                val rewindSeconds = settings.autoRewindSeconds
                if (rewindSeconds > 0) {
                    val rewindMs = rewindSeconds * 1000L
                    
                    if (plan != null && plan.files.isNotEmpty()) {
                        // 如果当前存在多文件播放计划，在全局大维度上计算当前进度，并执行精准的跨文件边界回退，
                        // 彻底解决单文件回退时被强制截断在 0 秒而无法回退到上一音轨末尾的体验痛点。
                        val fileIndex = controller.currentMediaItemIndex.coerceIn(0, plan.files.lastIndex)
                        val positionInFile = controller.currentPosition.coerceAtLeast(0L)
                        val currentGlobalPos = PositionMapper.fileToGlobalPosition(fileIndex, positionInFile, plan.files)
                        val targetGlobalPos = (currentGlobalPos - rewindMs).coerceAtLeast(0L)
                        
                        // 根据回退后的全局目标进度，计算出映射在分轨中的文件索引和文件内偏移位置
                        val (targetFileIndex, targetPosInFile) = PositionMapper.globalToFilePosition(targetGlobalPos, plan.files)
                        // 跨文件定位可能导致媒体源发生变更，因此必须使用 index + file-position 执行 seek
                        controller.seekTo(targetFileIndex, targetPosInFile)
                    } else {
                        // 兜底单文件播放场景下的普通回退寻址。
                        val currentPos = controller.currentPosition
                        val targetPos = (currentPos - rewindMs).coerceAtLeast(0L)
                        controller.seekTo(targetPos)
                    }
                    
                    // 回退定位成功后，触发外部刷新进度与状态流
                    onProgressUpdated(controller)
                    // 立即落盘落库，消除强杀、崩溃导致的位置丢失风险
                    onSaveProgress()
                }
            } catch (e: Exception) {
                PlaybackWorkflowLogger.error("autoRewind pause rewind failed", e)
            }
        }
    }

    /**
     * 执行冷启动异常中断自愈逻辑。
     * 当应用冷启动时，在后台协程中调用此方法。
     * 读取 AppSettings，如果上次播放异常中断且开启了回退秒数，
     * 则查询最后一次播放的进度，并对其进行回退补偿。
     */
     suspend fun performColdStartSelfHealing() {
        try {
            // 挂起并获取 DataStore 中的最新设置快照，确保数据一致性。
            val settings = settingsRepository.settingsFlow.first()
            if (settings.isLastPlaybackInterrupted && settings.autoRewindSeconds > 0) {
                // 
                // 在 M4.3 重构中，摒弃重量级的 LibraryRepository，通过 Application 的 container 
                // 获取解耦后的只读网关 bookQueryGateway 以及进度网关 progressGateway。
                val container = (appContext as com.viel.aplayer.APlayerApplication).container
                val bookQueryGateway = container.bookQueryGateway
                val progressGateway = container.progressGateway

                val lastProgress = progressGateway.getLastPlayedProgressSync()
                if (lastProgress != null) {
                    val rewindMs = settings.autoRewindSeconds * 1000L
                    val targetGlobalPos = (lastProgress.globalPositionMs - rewindMs).coerceAtLeast(0L)
                    
                    val files = bookQueryGateway.getFilesForBookSync(lastProgress.bookId)
                    val healedProgress = if (files.isNotEmpty()) {
                        val (targetFileIndex, targetPosInFile) = PositionMapper.globalToFilePosition(targetGlobalPos, files)
                        val bookFileId = files.getOrNull(targetFileIndex)?.id
                        lastProgress.copy(
                            globalPositionMs = targetGlobalPos,
                            bookFileId = bookFileId,
                            currentFileIndex = targetFileIndex,
                            positionInFileMs = targetPosInFile,
                            lastPlayedAt = System.currentTimeMillis()
                        )
                    } else {
                        lastProgress.copy(
                            globalPositionMs = targetGlobalPos,
                            lastPlayedAt = System.currentTimeMillis()
                        )
                    }
                    
                    progressGateway.saveProgress(healedProgress)
                    com.viel.aplayer.logger.AutoRewindLogger.logColdStartSelfHeal(
                        bookId = lastProgress.bookId,
                        rewindMs = rewindMs,
                        targetPositionMs = targetGlobalPos
                    )
                }
                
                // 自愈完成后瞬间重置异常中断标志为 false，保障状态正确复位。
                settingsRepository.updateLastPlaybackInterrupted(false)
            } else {
                // 若非异常中断恢复，依然主动重置该状态为 false 以免残留脏数据污染。
                settingsRepository.updateLastPlaybackInterrupted(false)
            }
        } catch (e: Exception) {
            PlaybackWorkflowLogger.error("autoRewind cold start self-heal failed", e)
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: AutoRewindManager? = null

        /**
         * 获取单例实例，提供全局一致的状态控制与线程安全保证
         */
        fun getInstance(context: Context): AutoRewindManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AutoRewindManager(context).also { INSTANCE = it }
            }
        }
    }
}
