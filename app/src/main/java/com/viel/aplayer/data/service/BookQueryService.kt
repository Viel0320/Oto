package com.viel.aplayer.data.service

import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.content.FileProvider
import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.data.dao.BookDao
import com.viel.aplayer.data.dao.BookmarkDao
import com.viel.aplayer.data.dao.ChapterDao
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * 有声书查询与维护应用服务（实现了 BookQueryGateway 网关）。
 * 
 * 核心设计目标：
 * 1. 领域解耦与消灭上帝类：在 M6 阶段彻底脱离对 BookLibraryRepository 的依赖，直接直连注入各个精细化 Room DAO 与物理文件解析器。
 * 2. 完整保留运行语义：精心平移并保留书签锚定计算、播放计划构建的耗时性能日志、观察书籍时的封面自愈触发算子等核心运行期行为。
 */
@OptIn(UnstableApi::class)
class BookQueryService(
    private val context: android.content.Context,
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val bookmarkDao: BookmarkDao,
    private val scanSessionDao: ScanSessionDao,
    private val coverRecoveryHelper: CoverRecoveryHelper
) : BookQueryGateway, java.io.Closeable {

    // 异步非阻塞元数据覆写的专属协程异常拦截器
    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        Log.e("BookQueryService", "协程在 BookQueryService 运行中捕获到未处理异常", exception)
    }

    // 该服务独享的后台协程作用域，用于执行诸如异步更新书籍元数据章节等非阻塞写操作
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)

    // 用于在流式加载书籍列表时自动触发封面缓存物理自愈的响应式 Flow 拦截器
    // 详尽的中文注释：使用 flowOn 强制将 map 遍历及 checkAndTriggerCoverRegeneration 内的 File.exists() 磁盘 I/O 操作
    // 分流至 Dispatchers.IO 线程池执行，彻底防止大书库扫描渲染时，在 UI 主线程收集端高频执行同步磁盘探测引发的 ANR 与严重掉帧
    @OptIn(UnstableApi::class)
    private fun Flow<List<BookWithProgress>>.checkCovers(): Flow<List<BookWithProgress>> = this.map { list ->
        list.onEach { coverRecoveryHelper.checkAndTriggerCoverRegeneration(it.book) }
    }.flowOn(Dispatchers.IO)

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
        // 详尽的中文注释：响应式观察单本书籍时，亦通过 flowOn 将封面探测动作强制约束在 IO 线程池内，规避对 UI 收集主线程产生同步磁盘阻塞
        return bookDao.observeBookById(id).map { book ->
            book?.also { coverRecoveryHelper.checkAndTriggerCoverRegeneration(it) }
        }.flowOn(Dispatchers.IO)
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
        // 详尽的中文注释：强力防御物理文件丢失导致的 java.io.FileNotFoundException: ENOENT 异常。
        // 在此不再生成 file:// 协议的物理裸路径，而是通过我们注册在 Manifest 中的 FileProvider，将路径封装转换为安全受控且具有临时读取特权的 content:// 协议 URI。
        // 从而完美解决 SystemUI 等外部跨进程应用因 Android 的沙盒存储访问隔离规则无法直接读取 App 内部 covers 缓存物理文件抛出的 ENOENT 崩溃故障。
        val artworkUri = if (!artworkPath.isNullOrBlank()) {
            val file = File(artworkPath)
            if (file.exists()) {
                FileProvider.getUriForFile(context, "com.viel.aplayer.fileprovider", file)
            } else null
        } else null

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
        // 详尽的中文注释：章节兜底语义已经从导入期迁移到查询投影层。
        // 这里统一组合“真实章节 + 书籍快照 + 音频文件快照”，仅在单音频书且真实章节为空时合成一条只读虚拟章节，
        // 从而让播放器、通知、睡眠定时器和章节列表共享同一份章节视图，同时避免把展示层默认值落库成脏数据。
        return combine(
            chapterDao.getChaptersForBook(bookId),
            bookDao.observeBookById(bookId),
            bookDao.getFilesForBook(bookId)
        ) { chapters, book, files ->
            projectChaptersWithTrackFallback(book, files, chapters)
        }.flowOn(Dispatchers.IO)
    }

    override suspend fun getChaptersForBookSync(bookId: String): List<ChapterWithBookFile> = withContext(Dispatchers.IO) {
        // 详尽的中文注释：同步读取路径必须和 Flow 路径复用相同的投影规则，
        // 否则前台播放器、通知会话和后台任务将分别看到不同的章节语义，导致章节模式表现不一致。
        val chapters = chapterDao.getChaptersForBookList(bookId)
        val book = bookDao.getBookById(bookId)
        val files = bookDao.getFilesForBookList(bookId)
        projectChaptersWithTrackFallback(book, files, chapters)
    }

    override fun saveChapters(bookId: String, chapters: List<ChapterEntity>) {
        // 在专属后台协程域中执行物理分轨章节实体的清空与批量写入
        scope.launch {
            if (bookDao.getBookById(bookId) != null) {
                // 详尽的中文注释：改用 chapterDao 统一的 replaceChapters 事务方法，
                // 将章节的清除与重新插入强行约束在同一个写事务中，排除异常下的零章节状态残留
                chapterDao.replaceChapters(bookId, chapters)
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

    override fun close() {
        // 详尽的中文注释：在服务实例销毁或容器重置时，安全且显式地取消专属于后台异步标签写入及章节自愈覆写的私有协程作用域，
        // 彻底释放全部挂起任务，杜绝常驻内存垃圾产生
        scope.cancel()
    }
}

/**
 * 详尽的中文注释：统一的章节查询投影规则。
 *
 * 规则说明：
 * 1. 若数据库中已有真实章节，则原样返回，绝不覆盖真实解析结果。
 * 2. 若数据库中没有章节，且书籍确认为单音频书，则基于真实 BookFile 合成一条只读虚拟章节。
 * 3. 该虚拟章节只存在于查询结果中，不会写回章节表，因此不会污染持久化数据，也不会影响进度落库锚点。
 */
internal fun projectChaptersWithTrackFallback(
    book: BookEntity?,
    files: List<BookFileEntity>,
    chapters: List<ChapterWithBookFile>
): List<ChapterWithBookFile> {
    // 详尽的中文注释：只要已经存在真实章节，就直接返回真实章节，确保查询投影不会篡改真实章节事实。
    if (chapters.isNotEmpty()) {
        return chapters
    }
    // 详尽的中文注释：投影章节仍然要求存在真实书籍快照；若书本身都不存在，就不凭空构造章节。
    if (book == null) {
        return chapters
    }
    // 详尽的中文注释：查询投影只对真实音频文件生效；若连音频文件都没有，则保持空章节结果。
    val sortedFiles = files.sortedBy { file -> file.index }
    if (sortedFiles.isEmpty()) {
        return chapters
    }
    var runningStartMs = 0L
    return sortedFiles.mapIndexed { trackIndex, file ->
        val safeDurationMs = when {
            file.durationMs > 0L -> file.durationMs
            // 详尽的中文注释：当唯一音频文件本身未携带时长时，回退到书级总时长，尽量保持单 track 书的投影章节可用。
            sortedFiles.size == 1 && book.totalDurationMs > 0L -> book.totalDurationMs
            else -> 0L
        }.coerceAtLeast(0L)
        val chapter = ChapterEntity(
            // 详尽的中文注释：使用基于 bookId + fileId 的 name-based UUID 保持每个 track 投影章节 ID 稳定，
            // 这样 Compose 列表 key、章节高亮和通知章节窗口不会因为每次查询重新生成随机 ID 而抖动。
            id = syntheticTrackProjectionChapterId(book.id, file.id),
            bookId = book.id,
            // 详尽的中文注释：投影章节显式绑定真实 BookFileEntity.id，确保章节点击、章节结束和章节模式 seek 都回落到真实音频锚点。
            bookFileId = file.id,
            index = trackIndex,
            title = projectedTrackChapterTitle(book, file, trackIndex, sortedFiles.size),
            startPositionMs = runningStartMs,
            durationMs = safeDurationMs,
            fileOffsetMs = 0L,
            source = AudiobookSchema.ChapterSource.GENERATED
        )
        // 详尽的中文注释：逐 track 投影时，后一章的全局起点始终累加前一条音频文件时长，
        // 这样 ABS 多 track 无章节与 VFS 单/多 track 无章节都能共享同样的全书时间轴语义。
        runningStartMs += safeDurationMs
        ChapterWithBookFile(
            chapter = chapter,
            bookFile = file
        )
    }
}

/**
 * 详尽的中文注释：为查询投影层生成稳定的单音频虚拟章节 ID。
 * 该 ID 只用于内存态章节视图，不参与任何数据库写入，因此既能保持稳定，又不会引入持久化主键污染。
 */
internal fun syntheticTrackProjectionChapterId(bookId: String, fileId: String): String =
    UUID.nameUUIDFromBytes("track-projection:$bookId:$fileId".toByteArray(StandardCharsets.UTF_8)).toString()

/**
 * 详尽的中文注释：统一生成逐 track 投影章节标题。
 * 单 track 时优先保留书名，避免本地单文件书回退成难读的裸文件名；多 track 时优先使用文件显示名，
 * 让 ABS 与本地多文件书都能自然呈现“按 track 分章”的用户心智。
 */
internal fun projectedTrackChapterTitle(
    book: BookEntity,
    file: BookFileEntity,
    trackIndex: Int,
    totalTracks: Int
): String {
    val displayName = file.displayName.substringBeforeLast('.', file.displayName).ifBlank { file.displayName }
    return when {
        totalTracks == 1 && book.title.isNotBlank() -> book.title
        displayName.isNotBlank() -> displayName
        book.title.isNotBlank() -> "${book.title} ${trackIndex + 1}"
        else -> "Track ${trackIndex + 1}"
    }
}
