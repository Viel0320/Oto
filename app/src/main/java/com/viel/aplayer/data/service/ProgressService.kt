package com.viel.aplayer.data.service

import android.util.Log
import com.viel.aplayer.data.dao.BookDao
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.BookProgressEntity
import com.viel.aplayer.data.gateway.ProgressGateway
import com.viel.aplayer.media.PlaybackReachabilityManager
import com.viel.aplayer.media.PositionMapper
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
) : ProgressGateway {

    // 详尽的中文注释：异步非阻塞进度更新的专属协程异常拦截器
    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        Log.e("ProgressService", "协程在 ProgressService 运行中捕获到未处理异常", exception)
    }

    // 详尽的中文注释：进度落盘专属的后台异步处理协程作用域，运行在 IO 线程池中以规避主线程阻塞
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)

    override fun updateProgress(bookId: String, position: Long) {
        // 详尽的中文注释：采用后台非阻塞协程执行高频进度换算与 Room 落盘，防止播放器主循环卡顿
        scope.launch {
            val progress = bookDao.getProgressForBookSync(bookId)
            val files = bookDao.getFilesForBookList(bookId)
            
            if (files.isNotEmpty()) {
                // 详尽的中文注释：通过 PositionMapper 将播放器绝对毫秒位置换算为指定物理音频轨内的相对毫秒偏移
                val (fileIndex, posInFile) = PositionMapper.globalToFilePosition(position, files)
                val bookFileId = files.getOrNull(fileIndex)?.id

                val updated = progress?.copy(
                    globalPositionMs = position,
                    bookFileId = bookFileId,
                    currentFileIndex = fileIndex,
                    positionInFileMs = posInFile,
                    lastPlayedAt = System.currentTimeMillis()
                ) ?: BookProgressEntity(
                    bookId = bookId,
                    globalPositionMs = position,
                    bookFileId = bookFileId,
                    currentFileIndex = fileIndex,
                    positionInFileMs = posInFile,
                    anchorStatus = AudiobookSchema.AnchorStatus.OK,
                    lastPlayedAt = System.currentTimeMillis()
                )
                bookDao.insertProgress(updated)
            } else if (progress != null) {
                // 详尽的中文注释：物理音频分轨缺失时的降级路径，仅保留全局绝对进度位置
                bookDao.insertProgress(progress.copy(
                    globalPositionMs = position,
                    lastPlayedAt = System.currentTimeMillis()
                ))
            }

            // 详尽的中文注释：联动触发阅读状态变更判定与写入
            updateReadStatusFromProgress(bookId, position)
        }
    }

    override suspend fun saveProgress(progress: BookProgressEntity) = withContext(Dispatchers.IO) {
        // 详尽的中文注释：强刷进度保存，采用 IO 线程有序落库并更新阅读状态状态机
        bookDao.insertProgress(progress)
        updateReadStatusFromProgress(progress.bookId, progress.globalPositionMs)
    }

    override suspend fun getLastPlayedProgressSync(): BookProgressEntity? = withContext(Dispatchers.IO) {
        // 详尽的中文注释：同步获取最近一条播放记录以支持紧凑型小播放器冷启动数据恢复
        bookDao.getLastPlayedProgressSync()
    }

    override suspend fun checkCurrentPlaybackFileAvailability(bookId: String): Boolean = withContext(Dispatchers.IO) {
        // 详尽的中文注释：委托专用物理就绪判定器校验当前需要断点续播的音频物理文件是否可达与已被授权
        reachabilityManager.checkCurrentPlaybackFileAvailability(bookId)
    }

    override suspend fun markPlaybackFileUnavailable(bookId: String, queueIndex: Int) = withContext(Dispatchers.IO) {
        // 详尽的中文注释：在 ExoPlayer 遇到异常读取中断时，物理标记当前所播放音频轨状态失效并记录桥接时间戳
        reachabilityManager.markPlaybackFileUnavailable(bookId, queueIndex)
    }

    override suspend fun findNextAvailablePlaybackFile(
        bookId: String,
        afterQueueIndex: Int
    ): Pair<Int, BookFileEntity>? = withContext(Dispatchers.IO) {
        // 详尽的中文注释：在音轨物理丢失或读取权限被吊销时，寻找播放队列中下一个可安全挂载的就绪音轨
        reachabilityManager.findNextAvailablePlaybackFile(bookId, afterQueueIndex)
    }

    /**
     * 详尽的中文注释：根据当前播放进度百分比换算有声书的阅读状态（99% 阈值即判定为已读完FINISHED），避免脏写
     */
    private suspend fun updateReadStatusFromProgress(bookId: String, position: Long) {
        val book = bookDao.getBookById(bookId) ?: return
        val nextStatus = when {
            book.totalDurationMs > 0L && position >= (book.totalDurationMs * 0.99).toLong() -> AudiobookSchema.ReadStatus.FINISHED
            position > 0L -> AudiobookSchema.ReadStatus.IN_PROGRESS
            else -> AudiobookSchema.ReadStatus.NOT_STARTED
        }
        if (book.readStatus != nextStatus) {
            bookDao.updateBookReadStatus(bookId, nextStatus)
        }
    }
}
