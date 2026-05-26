package com.viel.aplayer.data

import android.content.Context
import android.util.Log
import com.viel.aplayer.data.db.AppDatabase
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.BookProgressEntity
import com.viel.aplayer.media.PlaybackReachabilityManager
import com.viel.aplayer.media.PositionMapper
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 播放历史与进度管理仓库，专门负责有声书播放进度落库、阅读状态计算、冷启动自愈状态维护。
 * 托管了高频的播放位置毫秒映射逻辑，并在运行期与 PlaybackReachabilityManager 深度协同，
 * 提供前台在发生音频物理丢失、跳轨、断点冷启动等情况下的物理就绪判定与容灾自愈能力。
 */
class PlaybackHistoryRepository private constructor(context: Context) {
    // 避免 Activity 级别的内存泄漏，使用 applicationContext
    private val context = context.applicationContext

    // 数据库接口及主要的业务 DAO 引用
    private val database = AppDatabase.getInstance(this.context)
    private val bookDao = database.bookDao()
    private val libraryRootDao = database.libraryRootDao()

    // 独立的协程异常处理器，确保进度保存及自愈运算在后台多线程运行时的稳定性
    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        Log.e("PlaybackHistoryRepository", "协程在 PlaybackHistoryRepository 运行中捕获到未处理异常", exception)
    }

    // 进度落盘专属的全局协程上下文与作用域
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)

    // 音频可用性与跳轨检索管理器，用于处理运行期文件物理丢失的容灾逻辑
    private val reachabilityManager = PlaybackReachabilityManager(this.context, bookDao, libraryRootDao)

    /**
     * 高频更新播放位置。
     * 将全局的绝对毫秒位置换算为对应物理分轨文件的相对毫秒位置，并实时异步地将进度实体插入或更新回数据库。
     *
     * @param bookId 书籍 ID
     * @param position 播放器当前播放到的全局毫秒位置
     */
    fun updateProgress(bookId: String, position: Long) {
        scope.launch {
            // 获取数据库中已有的进度记录与该书籍下的所有物理音频分轨文件列表
            val progress = bookDao.getProgressForBookSync(bookId)
            val files = bookDao.getFilesForBookList(bookId)
            
            if (files.isNotEmpty()) {
                // 通过物理映射算法计算当前毫秒进度落在哪个音频文件里，以及在它的内部偏移量
                val (fileIndex, posInFile) = PositionMapper.globalToFilePosition(position, files)
                val bookFileId = files.getOrNull(fileIndex)?.id

                // 组装全新的进度更新实体
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
                // 写入 Room 数据库
                bookDao.insertProgress(updated)
            } else if (progress != null) {
                // 降级回退逻辑：如果当前书籍丢失了关联的物理文件实体，则只同步更新全局绝对毫秒进度
                bookDao.insertProgress(progress.copy(
                    globalPositionMs = position,
                    lastPlayedAt = System.currentTimeMillis()
                ))
            }

            // 根据更新后的进度位置同步计算并更新书籍的整体阅读状态
            updateReadStatusFromProgress(bookId, position)
        }
    }

    /**
     * 强刷并保存进度实体。
     * 强制将主线程可能派发的播放器回调进度同步操作切到 IO 线程，以确保多线程的有序落库，并推进书籍阅读状态。
     *
     * @param progress 需要落库保存的完整 BookProgressEntity 实体
     */
    suspend fun saveProgress(progress: BookProgressEntity) = withContext(Dispatchers.IO) {
        bookDao.insertProgress(progress)
        updateReadStatusFromProgress(progress.bookId, progress.globalPositionMs)
    }

    /**
     * 根据播放进度换算阅读状态并实时写入数据库。
     * 如果播放时长达到书籍总时长的 99% 以上，判定为已读完（FINISHED）；如果进度大于0，判定为进行中（IN_PROGRESS）；否则判定为未开始。
     */
    private suspend fun updateReadStatusFromProgress(bookId: String, position: Long) {
        val book = bookDao.getBookById(bookId) ?: return
        val nextStatus = when {
            book.totalDurationMs > 0L && position >= (book.totalDurationMs * 0.99).toLong() -> AudiobookSchema.ReadStatus.FINISHED
            position > 0L -> AudiobookSchema.ReadStatus.IN_PROGRESS
            else -> AudiobookSchema.ReadStatus.NOT_STARTED
        }
        // 只有当阅读状态真实发生越界改变时，才进行数据库写事务，以减少多余的 IO 摩擦
        if (book.readStatus != nextStatus) {
            bookDao.updateBookReadStatus(bookId, nextStatus)
        }
    }

    /**
     * 同步获取最近一次播放的有声书进度记录，通常用于冷启动阶段恢复紧凑型播放器（Compact Player）。
     *
     * @return 最近一条已播放的书籍进度实体，如果没有记录则返回 null
     */
    suspend fun getLastPlayedProgressSync(): BookProgressEntity? = withContext(Dispatchers.IO) {
        bookDao.getLastPlayedProgressSync()
    }

    /**
     * 校验前台恢复 compact 播放进度时的物理音频文件就绪判定。
     * 委托给专门的就绪状态管理器，对需要恢复播放的文件进行底层真实可读性检测。
     *
     * @param bookId 书籍 ID
     * @return 文件物理可用且被授权则返回 true，否则返回 false
     */
    suspend fun checkCurrentPlaybackFileAvailability(bookId: String): Boolean = 
        reachabilityManager.checkCurrentPlaybackFileAvailability(bookId)

    /**
     * 运行期标记具体某轨物理音频文件失效。
     * 当 ExoPlayer 发生底层源读取错误（如外置 SD 卡拔出或 SAF 授权被吊销）时，
     * 标记其文件状态为 UNAVAILABLE 并生成带有待自愈时间戳的桥接状态。
     *
     * @param bookId 书籍 ID
     * @param queueIndex 异常队列索引
     */
    suspend fun markPlaybackFileUnavailable(bookId: String, queueIndex: Int) = 
        reachabilityManager.markPlaybackFileUnavailable(bookId, queueIndex)

    /**
     * 运行期文件失效时，寻找队列中的下一条就绪（READY）可用轨。
     * 用于防文件丢失（ENOENT）自愈系统，在当前播放轨道不可达时寻找下一个能无缝起播的音轨。
     *
     * @param bookId 书籍 ID
     * @param afterQueueIndex 当前出现故障的音轨索引位置
     * @return 下一个可用的音轨队列索引和文件实体的 Pair，若无可用轨则返回 null
     */
    suspend fun findNextAvailablePlaybackFile(bookId: String, afterQueueIndex: Int): Pair<Int, BookFileEntity>? = 
        reachabilityManager.findNextAvailablePlaybackFile(bookId, afterQueueIndex)

    companion object {
        @Volatile
        private var INSTANCE: PlaybackHistoryRepository? = null

        /**
         * 获取播放历史仓库的双检锁线程安全单例。
         */
        fun getInstance(context: Context): PlaybackHistoryRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PlaybackHistoryRepository(context).also { INSTANCE = it }
            }
        }
    }
}
