package com.viel.aplayer.data.service

import com.viel.aplayer.data.PlaybackHistoryRepository
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.BookProgressEntity
import com.viel.aplayer.data.gateway.ProgressGateway

/**
 * 播放位置与进度记录应用服务（实现了 ProgressGateway 网关）。
 *
 * 核心设计目标：
 * 1. 业务逻辑防腐：在当前阶段暂时委托给 [PlaybackHistoryRepository]，平滑隔离高频进度落库和容灾跳轨逻辑。
 * 2. 面向对象单一职责：解耦进度管理与有声书基础元数据管理职责。
 */
class ProgressService(
    private val playbackHistoryRepository: PlaybackHistoryRepository
) : ProgressGateway {

    override fun updateProgress(bookId: String, position: Long) {
        playbackHistoryRepository.updateProgress(bookId, position)
    }

    override suspend fun saveProgress(progress: BookProgressEntity) {
        playbackHistoryRepository.saveProgress(progress)
    }

    override suspend fun getLastPlayedProgressSync(): BookProgressEntity? {
        return playbackHistoryRepository.getLastPlayedProgressSync()
    }

    override suspend fun checkCurrentPlaybackFileAvailability(bookId: String): Boolean {
        return playbackHistoryRepository.checkCurrentPlaybackFileAvailability(bookId)
    }

    override suspend fun markPlaybackFileUnavailable(bookId: String, queueIndex: Int) {
        playbackHistoryRepository.markPlaybackFileUnavailable(bookId, queueIndex)
    }

    override suspend fun findNextAvailablePlaybackFile(
        bookId: String,
        afterQueueIndex: Int
    ): Pair<Int, BookFileEntity>? {
        return playbackHistoryRepository.findNextAvailablePlaybackFile(bookId, afterQueueIndex)
    }
}
