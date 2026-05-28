package com.viel.aplayer.data.gateway

import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.BookProgressEntity
import kotlinx.coroutines.flow.Flow

/**
 * 领域解耦的网关接口：专注于有声书的播放位置更新、记忆持久化以及多音轨物理可用性状态流转。
 *
 * 核心设计目标：
 * 1. 细粒度接口定义：隔离高频的播放器进度落库和容灾跳轨计算，不与书籍信息、书签和扫描逻辑混合。
 * 2. 支持独立测试：播放器控制器可只依赖此网关，避免上帝仓储类引入不必要的数据库事务。
 */
interface ProgressGateway {

    /**
     * 高频更新播放位置。
     * 需将全局进度自动映射到对应的物理分轨位置，并安全地在异步任务中落库。
     */
    fun updateProgress(bookId: String, position: Long)

    /**
     * 显式保存或覆盖进度实体。
     */
    suspend fun saveProgress(progress: BookProgressEntity)

    /**
     * 同步获取最近一次播放进度，用于应用冷启动或主界面快捷恢复播放状态。
     */
    suspend fun getLastPlayedProgressSync(): BookProgressEntity?

    /**
     * 校验有声书当前播放文件在底层 VFS 文件系统中的物理可用性。
     */
    suspend fun checkCurrentPlaybackFileAvailability(bookId: String): Boolean

    /**
     * 将特定有声书内指定的音频分轨标记为已损坏或物理丢失。
     */
    suspend fun markPlaybackFileUnavailable(bookId: String, queueIndex: Int)

    /**
     * 容灾切换：当当前音频轨损坏或物理失效时，在其后部音轨序列中匹配首个就绪并可读的音轨。
     */
    suspend fun findNextAvailablePlaybackFile(
        bookId: String,
        afterQueueIndex: Int
    ): Pair<Int, BookFileEntity>?
}
