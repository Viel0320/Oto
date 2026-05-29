package com.viel.aplayer.data.service

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.data.dao.BookDao
import com.viel.aplayer.data.dao.ChapterDao
import com.viel.aplayer.data.dao.BookmarkDao
import com.viel.aplayer.data.dao.ScanSessionDao
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.BookWithProgress
import com.viel.aplayer.data.entity.BookmarkEntity
import com.viel.aplayer.data.entity.ChapterEntity
import com.viel.aplayer.data.entity.ChapterWithBookFile
import com.viel.aplayer.data.entity.ScanSessionEntity
import com.viel.aplayer.data.gateway.BookQueryGateway
import com.viel.aplayer.media.BookPlaybackPlan
import com.viel.aplayer.media.PositionMapper
import com.viel.aplayer.media.parser.CoverRecoveryHelper
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 有声书查询与维护应用服务（实现了 BookQueryGateway 网关）。
 * 
 * 核心设计目标：
 * 1. 领域解耦与消灭上帝类：在 M6 阶段彻底脱离对 BookLibraryRepository 的依赖，直接直连注入各个精细化 Room DAO 与物理文件解析器。
 * 2. 完整保留运行语义：精心平移并保留书签锚定计算、播放计划构建的耗时性能日志、观察书籍时的封面自愈触发算子等核心运行期行为。
 */
@OptIn(UnstableApi::class)
class BookQueryService
    (private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val bookmarkDao: BookmarkDao,
    private val scanSessionDao: ScanSessionDao,
    private val coverRecoveryHelper: CoverRecoveryHelper
) : BookQueryGateway {

    // 异步非阻塞元数据覆写的专属协程异常拦截器
    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        Log.e("BookQueryService", "协程在 BookQueryService 运行中捕获到未处理异常", exception)
    }

    // 该服务独享的后台协程作用域，用于执行诸如异步更新书籍元数据章节等非阻塞写操作
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)

    // 用于在流式加载书籍列表时自动触发封面缓存物理自愈的响应式 Flow 拦截器
    @OptIn(UnstableApi::class)
    private fun Flow<List<BookWithProgress>>.checkCovers(): Flow<List<BookWithProgress>> = this.map { list ->
        list.onEach { coverRecoveryHelper.checkAndTriggerCoverRegeneration(it.book) }
    }

    // 锚定计算时使用的辅助数据元组，用于承载文件ID、偏移、指纹及状态
    private data class Quad(
        val bookFileId: String?,
        val fileOffsetMs: Long,
        val fingerprint: String?,
        val anchorStatus: String
    )

    override val audiobooks: Flow<List<BookWithProgress>>
        get() = bookDao.getAllBooksWithProgress().checkCovers()

    override suspend fun getBookById(id: String): BookEntity? = withContext(Dispatchers.IO) {
        // 获取书籍详情时，若书籍封面物理丢失，则非阻塞地触发封面的自动提取与自愈
        bookDao.getBookById(id)?.also { coverRecoveryHelper.checkAndTriggerCoverRegeneration(it) }
    }

    override fun observeBookById(id: String): Flow<BookEntity?> {
        // 响应式订阅单本书籍的最新状态，并在发生变动时协同封面自愈助手执行非阻塞检查
        return bookDao.observeBookById(id).map { book ->
            book?.also { coverRecoveryHelper.checkAndTriggerCoverRegeneration(it) }
        }
    }

    override fun searchAudiobooks(query: String): Flow<List<BookWithProgress>> {
        return bookDao.searchBooksWithProgress(query).checkCovers()
    }

    override fun filterByYear(year: String): Flow<List<BookWithProgress>> {
        return bookDao.filterByYearWithProgress(year).checkCovers()
    }

    override fun filterByAuthor(author: String): Flow<List<BookWithProgress>> {
        return bookDao.filterByAuthorWithProgress(author).checkCovers()
    }

    override fun filterByAuthorLimited(author: String, excludeId: String, limit: Int): Flow<List<BookWithProgress>> {
        return bookDao.filterByAuthorLimitedWithProgress(author, excludeId, limit).checkCovers()
    }

    override fun filterByNarrator(narrator: String): Flow<List<BookWithProgress>> {
        return bookDao.filterByNarratorWithProgress(narrator).checkCovers()
    }

    override fun filterByNarratorLimited(narrator: String, excludeId: String, limit: Int): Flow<List<BookWithProgress>> {
        return bookDao.filterByNarratorLimitedWithProgress(narrator, excludeId, limit).checkCovers()
    }

    override fun getRecentlyAdded(limit: Int): Flow<List<BookWithProgress>> {
        return bookDao.getRecentlyAddedWithProgress(limit).checkCovers()
    }

    override fun getRecentlyAddedExclusive(
        currentId: String,
        authors: List<String>,
        narrators: List<String>,
        limit: Int
    ): Flow<List<BookWithProgress>> {
        return bookDao.getRecentlyAddedExclusiveWithProgress(currentId, authors, narrators, limit).checkCovers()
    }

    override suspend fun deleteBook(bookId: String) = withContext(Dispatchers.IO) {
        val book = bookDao.getBookById(bookId)
        if (book != null) {
            // 执行软删除标识更新，保留数据以支持历史排重，而不是物理抹除
            bookDao.updateBookStatus(bookId, AudiobookSchema.BookStatus.DELETED)
        }
    }

    override suspend fun updateBookReadStatus(bookId: String, readStatus: String) = withContext(Dispatchers.IO) {
        bookDao.updateBookReadStatus(bookId, readStatus)
    }

    override suspend fun updateBookDetails(
        id: String,
        title: String,
        author: String,
        narrator: String,
        description: String,
        year: String
    ) = withContext(Dispatchers.IO) {
        bookDao.updateBookDetails(id, title, author, narrator, description, year)
    }

    override suspend fun getFilesForBookSync(bookId: String): List<BookFileEntity> = withContext(Dispatchers.IO) {
        bookDao.getFilesForBookList(bookId)
    }

    override suspend fun getAllFilesForBookSync(bookId: String): List<BookFileEntity> = withContext(Dispatchers.IO) {
        bookDao.getAllFilesForBookList(bookId)
    }

    override fun observeLatestScanSession(): Flow<ScanSessionEntity?> {
        // 响应式订阅观察最后一次物理文件扫描会话的记录状态
        return scanSessionDao.observeLatestCompletedSession()
    }

    override suspend fun getPlaybackPlan(bookId: String): BookPlaybackPlan? = withContext(Dispatchers.IO) {
        val planBuildStart = SystemClock.elapsedRealtime()
        val bookQueryStart = SystemClock.elapsedRealtime()
        val book = bookDao.getBookById(bookId) ?: return@withContext null
        val bookQueryCost = SystemClock.elapsedRealtime() - bookQueryStart
        
        // 在构建起播计划时，协同触发物理封面的自愈与物理提取自愈操作
        coverRecoveryHelper.checkAndTriggerCoverRegeneration(book)
        
        val filesQueryStart = SystemClock.elapsedRealtime()
        val files = bookDao.getFilesForBookList(bookId)
        val filesQueryCost = SystemClock.elapsedRealtime() - filesQueryStart
        
        val progressQueryStart = SystemClock.elapsedRealtime()
        val progress = bookDao.getProgressForBookSync(bookId)
        val progressQueryCost = SystemClock.elapsedRealtime() - progressQueryStart
        
        if (files.isEmpty()) {
            val totalCost = SystemClock.elapsedRealtime() - planBuildStart
            com.viel.aplayer.logger.LibraryLogger.logPlaybackPlanEmpty(
                bookId = bookId,
                bookQueryMs = bookQueryCost,
                filesQueryMs = filesQueryCost,
                progressQueryMs = progressQueryCost,
                totalMs = totalCost
            )
            return@withContext null
        }
        
        val artworkPath = book.coverPath
        // 强力防御物理文件丢失导致的 java.io.FileNotFoundException: ENOENT 异常。
        val artworkUri = if (artworkPath != null && File(artworkPath).exists()) {
            Uri.fromFile(File(artworkPath))
        } else {
            null
        }

        val plan = BookPlaybackPlan(
            bookId = bookId,
            title = book.title,
            author = book.author,
            artworkUri = artworkUri,
            files = files,
            subtitlesByFileId = emptyMap(),
            startGlobalPositionMs = progress?.globalPositionMs ?: 0L
        )
        val totalCost = SystemClock.elapsedRealtime() - planBuildStart
        com.viel.aplayer.logger.LibraryLogger.logPlaybackPlanReady(
            bookId = bookId,
            bookQueryMs = bookQueryCost,
            filesQueryMs = filesQueryCost,
            progressQueryMs = progressQueryCost,
            totalMs = totalCost,
            fileCount = plan.files.size,
            startPosition = plan.startGlobalPositionMs
        )
        plan
    }

    override fun updateMetadata(
        bookId: String,
        title: String?,
        author: String?,
        narrator: String?,
        description: String?,
        duration: Long
    ) {
        // 在后台协程域中非阻塞地写回扫描覆盖获取的物理多媒体元数据标签信息
        scope.launch {
            val existing = bookDao.getBookById(bookId) ?: return@launch
            val newTitle = if (!title.isNullOrBlank()) title else existing.title
            val newAuthor = if (!author.isNullOrBlank()) author else existing.author
            val newNarrator = if (!narrator.isNullOrBlank()) narrator else existing.narrator
            val newDescription = if (!description.isNullOrBlank()) description else existing.description
            val newDuration = if (duration > 0) duration else existing.totalDurationMs
            
            if (newTitle != existing.title || newAuthor != existing.author || 
                newNarrator != existing.narrator || newDescription != existing.description ||
                newDuration != existing.totalDurationMs) {
                bookDao.updateMetadata(bookId, newTitle, newAuthor, newNarrator, newDescription, newDuration)
            }
        }
    }

    override fun getChapters(bookId: String): Flow<List<ChapterWithBookFile>> {
        return chapterDao.getChaptersForBook(bookId)
    }

    override suspend fun getChaptersForBookSync(bookId: String): List<ChapterWithBookFile> = withContext(Dispatchers.IO) {
        chapterDao.getChaptersForBookList(bookId)
    }

    override fun saveChapters(bookId: String, chapters: List<ChapterEntity>) {
        // 在专属后台协程域中执行物理分轨章节实体的清空与批量写入
        scope.launch {
            if (bookDao.getBookById(bookId) != null) {
                chapterDao.deleteChaptersForBook(bookId)
                chapterDao.insertChapters(chapters)
            }
        }
    }

    override fun getBookmarks(bookId: String): Flow<List<BookmarkEntity>> {
        return bookmarkDao.getBookmarksForBook(bookId)
    }

    override suspend fun addBookmark(bookId: String, position: Long, title: String) = withContext(Dispatchers.IO) {
        val files = bookDao.getFilesForBookList(bookId)
        // 在添加书签时精确计算当前绝对毫秒位置在多音频物理分轨中的实际映射偏移、指纹及锚定状态
        val (bookFileId, fileOffsetMs, fingerprint, anchorStatus) = if (files.isNotEmpty()) {
            val (fileIndex, offset) = PositionMapper.globalToFilePosition(position, files)
            val file = files.getOrNull(fileIndex)
            Quad(file?.id, offset, file?.fingerprint, AudiobookSchema.AnchorStatus.OK)
        } else {
            Quad(null, 0L, null, AudiobookSchema.AnchorStatus.UNRESOLVED)
        }
        
        bookmarkDao.insert(BookmarkEntity(
            id = UUID.randomUUID().toString(),
            bookId = bookId,
            globalPositionMs = position,
            bookFileId = bookFileId,
            fileOffsetMs = fileOffsetMs,
            fileFingerprint = fingerprint,
            anchorStatus = anchorStatus,
            title = title
        ))
        // 显式返回 Unit 以强行匹配网关接口的方法签名定义
        Unit
    }

    override suspend fun updateBookmark(bookmark: BookmarkEntity) = withContext(Dispatchers.IO) {
        bookmarkDao.insert(bookmark)
        // 显式返回 Unit 避免 Long 类型隐式返回
        Unit
    }

    override suspend fun deleteBookmark(bookmark: BookmarkEntity) = withContext(Dispatchers.IO) {
        bookmarkDao.delete(bookmark)
        // 显式返回 Unit
        Unit
    }
}
