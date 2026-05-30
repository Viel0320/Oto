package com.viel.aplayer.data.service

import android.util.Log
import com.viel.aplayer.data.dao.BookDao
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.BookProgressEntity
import com.viel.aplayer.data.gateway.ProgressGateway
import com.viel.aplayer.library.availability.PlaybackReachabilityManager
import com.viel.aplayer.media.PositionMapper
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel

/**
 * 播放位置与进度记录应用服务（实现了 ProgressGateway 网关）。
 *
 * 核心设计目标：
 * 1. 彻底解耦消灭上帝仓库：在 M6b 阶段直接直连注入 BookDao 与 PlaybackReachabilityManager，完全剥离旧有的 PlaybackHistoryRepository 仓库。
 * 2. 完美保留高频同步协程：自主持有进度更新后台协程作用域，对 99% 的已读FINISHED状态换算、跳轨检测及 ENOENT 容灾自愈流程完美适配。
 */
class ProgressService(
    private val bookDao: BookDao,
    private val reachabilityManager: PlaybackReachabilityManager
) : ProgressGateway, java.io.Closeable {

    // 异步非阻塞进度更新的专属协程异常拦截器
    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        Log.e("ProgressService", "协程在 ProgressService 运行中捕获到未处理异常", exception)
    }

    // 进度落盘专属的后台异步处理协程作用域，运行在 IO 线程池中以规避主线程阻塞
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)

    override fun updateProgress(bookId: String, position: Long) {
        // 采用后台非阻塞协程执行高频进度换算与 Room 落盘，防止播放器主循环卡顿
        scope.launch {
            // 详尽的中文注释：通过调用 BookDao 的统一进度更新事务方法，
            // 在单次写事务内原子完成旧进度提取、音频轨道偏移换算、落盘及联动阅读状态修改，
            // 有效规避高并发并发上报时不同协程交错写造成的数据脏写和进度回退隐患
            bookDao.updateProgressWithReadStatus(bookId, position, System.currentTimeMillis())
        }
    }

    override suspend fun saveProgress(progress: BookProgressEntity) = withContext(Dispatchers.IO) {
        // 详尽的中文注释：强刷进度时同步采取统一写事务，防止留下撕裂的数据并联动更新阅读状态
        bookDao.updateProgressWithReadStatus(progress.bookId, progress.globalPositionMs, progress.lastPlayedAt)
    }

    override suspend fun getLastPlayedProgressSync(): BookProgressEntity? = withContext(Dispatchers.IO) {
        // 同步获取最近一条播放记录以支持紧凑型小播放器冷启动数据恢复
        bookDao.getLastPlayedProgressSync()
    }

    override suspend fun checkCurrentPlaybackFileAvailability(bookId: String): Boolean = withContext(Dispatchers.IO) {
        // 委托专用物理就绪判定器校验当前需要断点续播的音频物理文件是否可达与已被授权
        reachabilityManager.checkCurrentPlaybackFileAvailability(bookId)
    }

    override suspend fun markPlaybackFileUnavailable(bookId: String, queueIndex: Int) = withContext(Dispatchers.IO) {
        // 在 ExoPlayer 遇到异常读取中断时，物理标记当前所播放音频轨状态失效并记录桥接时间戳
        reachabilityManager.markPlaybackFileUnavailable(bookId, queueIndex)
    }

    override suspend fun findNextAvailablePlaybackFile(
        bookId: String,
        afterQueueIndex: Int
    ): Pair<Int, BookFileEntity>? = withContext(Dispatchers.IO) {
        // 在音轨物理丢失或读取权限被吊销时，寻找播放队列中下一个可安全挂载的就绪音轨
        reachabilityManager.findNextAvailablePlaybackFile(bookId, afterQueueIndex)
    }

    override fun close() {
        // 详尽的中文注释：在进度服务销毁或 DI 容器关闭重置时，显式取消专属高频进度异步落库协程作用域，
        // 确保挂起事务全量释放，不遗留任何长驻协程内存垃圾
        scope.cancel()
    }
}
