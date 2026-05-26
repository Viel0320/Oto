package com.viel.aplayer.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.data.db.AppDatabase
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.BookWithProgress
import com.viel.aplayer.data.entity.BookmarkEntity
import com.viel.aplayer.data.entity.ChapterEntity
import com.viel.aplayer.data.entity.ChapterWithBookFile
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.data.entity.ScanSessionEntity
import com.viel.aplayer.data.store.SearchHistoryEntry
import com.viel.aplayer.data.store.SearchHistoryStore
import com.viel.aplayer.library.LibraryRootStore
import com.viel.aplayer.library.RescanCoordinator
import com.viel.aplayer.library.RescanType
import com.viel.aplayer.library.sourceProvider.LibrarySourceKind
import com.viel.aplayer.library.sourceProvider.webdav.WebDavCredentialStore
import com.viel.aplayer.media.BookPlaybackPlan
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 有声书籍库管理仓库，专注处理书籍、章节、书签的 Room 数据库 CRUD 事务及流式加载。
 * 托管了媒体库根目录管理、扫描同步协调、历史记录管理，并单向依赖 PhysicalFileResolver，
 * 在查询数据时协同封面异步重建机制，保证首帧流畅性，且在删除书库时能够彻底清除相关的磁盘物理垃圾缓存。
 */
@OptIn(UnstableApi::class)
class BookLibraryRepository private constructor(context: Context) {
    // 采用 applicationContext 避免全局单例造成的潜在内存泄漏
    private val context = context.applicationContext

    // 实例化底层的数据库及所有的主要 DAO 对象
    private val database = AppDatabase.getInstance(this.context)
    private val bookDao = database.bookDao()
    private val chapterDao = database.chapterDao()
    private val bookmarkDao = database.bookmarkDao()
    private val libraryRootDao = database.libraryRootDao()
    private val scanSessionDao = database.scanSessionDao()

    // 实例化搜索历史存储 DataStore 门面
    private val searchHistoryStore = SearchHistoryStore.getInstance(this.context)

    // 书库根目录的 SAF 与 WebDAV 底层提供者组件
    private val rootStore = LibraryRootStore(this.context)
    private val webDavCredentialStore = WebDavCredentialStore(this.context)

    // 异常拦截器，保障后台线程在调度长任务同步时不发生崩溃逃逸
    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        Log.e("BookLibraryRepository", "协程在 BookLibraryRepository 运行中捕获到未处理异常", exception)
    }

    // 媒体扫描与批量库同步专属的后台协程作用域
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)

    // 串行同步扫描任务的串行锁，防止应用前后台多处调度同时扫描产生脏数据或并发冲突
    private val scanMutex = Mutex()

    // 单向持有物理文件解析器单例，用于触发书籍封面的物理自愈提取及缓存保存
    private val physicalFileResolver = PhysicalFileResolver.getInstance(this.context)

    // 内存中缓存的书库根目录列表，提供零延迟极速冷启动展示，防止 Room 异步查询产生的大幅度 UI 抖动
    @Volatile
    private var cachedRoots: List<LibraryRootEntity> = emptyList()

    init {
        // 在仓库全局单例初始化时立即订阅媒体库状态，并热更新本地内存缓存
        scope.launch {
            observeLibraryRoots().collect {
                cachedRoots = it
            }
        }
    }

    /**
     * 同步获取内存中最新缓存的媒体库根目录列表。
     */
    fun getCachedLibraryRoots(): List<LibraryRootEntity> = cachedRoots

    // ==========================================
    // 1. 搜索历史部分（基于 DataStore 存储的响应式流）
    // ==========================================

    val searchHistory: Flow<List<SearchHistoryEntry>> = searchHistoryStore.history

    suspend fun addToHistory(query: String) {
        if (query.isNotBlank()) {
            searchHistoryStore.add(query)
        }
    }

    suspend fun deleteFromHistory(history: SearchHistoryEntry) {
        searchHistoryStore.delete(history)
    }

    suspend fun clearHistory() {
        searchHistoryStore.clear()
    }

    // ==========================================
    // 2. 媒体库根路径配置与调度（SAF 授权与 WebDAV 凭据管理）
    // ==========================================

    suspend fun setLibraryRoot(uri: Uri): LibraryRootEntity = withContext(Dispatchers.IO) {
        rootStore.addRoot(uri, "My Library")
    }

    suspend fun addWebDavLibraryRoot(
        url: String,
        username: String,
        password: String,
        displayName: String,
        basePath: String
    ): LibraryRootEntity = withContext(Dispatchers.IO) {
        rootStore.addWebDavRoot(
            url = url,
            username = username,
            password = password,
            displayName = displayName,
            basePath = basePath
        )
    }

    fun addLibraryRootAndScheduleSync(uri: Uri, trigger: String = "USER") {
        scope.launch {
            runCatching {
                // 确保持久化申请 SAF 目录树的系统级读取权限，防止重启设备后失效
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                setLibraryRoot(uri)
                syncLibrary(trigger)
            }.onFailure { error ->
                Log.e("BookLibraryRepository", "添加本地 SAF 根目录并调度扫描时发生异常", error)
            }
        }
    }

    fun addWebDavLibraryRootAndScheduleSync(
        url: String,
        username: String,
        password: String,
        displayName: String,
        basePath: String,
        trigger: String = "USER"
    ) {
        scope.launch {
            runCatching {
                addWebDavLibraryRoot(url, username, password, displayName, basePath)
                syncLibrary(trigger)
            }.onFailure { error ->
                Log.e("BookLibraryRepository", "添加 WebDAV 远端根目录并调度扫描时发生异常", error)
            }
        }
    }

    suspend fun refreshLibraryRootStatuses() = withContext(Dispatchers.IO) {
        rootStore.refreshPermissionStatuses()
    }

    // ==========================================
    // 3. 库扫描同步事务（串行协程，协同封面自愈检查）
    // ==========================================

    suspend fun syncLibrary(trigger: String = "USER") = scanMutex.withLock {
        runSyncLibrary(trigger)
    }

    private suspend fun runSyncLibrary(trigger: String = "USER") = withContext(Dispatchers.IO) {
        rootStore.refreshPermissionStatuses()
        val type = if (trigger == AudiobookSchema.ScanTrigger.COLD_START) {
            RescanType.COLD_START_LIGHT
        } else {
            RescanType.USER_GLOBAL
        }
        // 调用底层的重扫协调器构建清册，并在入库后立即传入物理文件封面自愈助手的注册回调执行非阻塞提取
        val session = RescanCoordinator(
            context = context,
            triggerCoverRegeneration = physicalFileResolver.coverRecoveryHelper::checkAndTriggerCoverRegeneration
        ).rescan(type)
        Log.i("BookLibraryRepository", "书籍同步扫描已完成. 新增: ${session.discoveredBookCount}, 待定处理: ${session.pendingActionCount}")
    }

    fun scheduleLibrarySync(trigger: String = "USER") {
        scope.launch { syncLibrary(trigger) }
    }

    // ==========================================
    // 4. 有声书实体读取及各种条件过滤数据流（接入物理封面自愈扩展算子）
    // ==========================================

    val audiobooks: Flow<List<BookWithProgress>> = bookDao.getAllBooksWithProgress().checkCovers()

    fun searchAudiobooks(query: String): Flow<List<BookWithProgress>> = bookDao.searchBooksWithProgress(query).checkCovers()

    fun filterByYear(year: String): Flow<List<BookWithProgress>> = bookDao.filterByYearWithProgress(year).checkCovers()
    
    fun filterByAuthor(author: String): Flow<List<BookWithProgress>> = bookDao.filterByAuthorWithProgress(author).checkCovers()
    
    fun filterByAuthorLimited(author: String, excludeId: String, limit: Int): Flow<List<BookWithProgress>> = 
        bookDao.filterByAuthorLimitedWithProgress(author, excludeId, limit).checkCovers()
    
    fun filterByNarrator(narrator: String): Flow<List<BookWithProgress>> = bookDao.filterByNarratorWithProgress(narrator).checkCovers()

    fun filterByNarratorLimited(narrator: String, excludeId: String, limit: Int): Flow<List<BookWithProgress>> = 
        bookDao.filterByNarratorLimitedWithProgress(narrator, excludeId, limit).checkCovers()

    fun getRecentlyAdded(limit: Int): Flow<List<BookWithProgress>> = bookDao.getRecentlyAddedWithProgress(limit).checkCovers()

    fun getRecentlyAddedExclusive(currentId: String, authors: List<String>, narrators: List<String>, limit: Int): Flow<List<BookWithProgress>> = 
        bookDao.getRecentlyAddedExclusiveWithProgress(currentId, authors, narrators, limit).checkCovers()

    // ==========================================
    // 5. 单个有声书基本 CRUD 与属性更新（非阻塞式封面与元数据自愈）
    // ==========================================

    suspend fun getBookById(id: String): BookEntity? = withContext(Dispatchers.IO) {
        bookDao.getBookById(id)?.also { physicalFileResolver.coverRecoveryHelper.checkAndTriggerCoverRegeneration(it) }
    }

    fun observeBookById(id: String): Flow<BookEntity?> {
        return bookDao.observeBookById(id).map { book ->
            book?.also { physicalFileResolver.coverRecoveryHelper.checkAndTriggerCoverRegeneration(it) }
        }
    }

    suspend fun deleteBook(bookId: String) {
        val book = bookDao.getBookById(bookId)
        if (book != null) {
            // 采用软删除（DELETED 状态），以便长按菜单清册重新检索时能有效排重
            bookDao.updateBookStatus(bookId, AudiobookSchema.BookStatus.DELETED)
        }
    }

    suspend fun updateBookReadStatus(bookId: String, readStatus: String) = withContext(Dispatchers.IO) {
        bookDao.updateBookReadStatus(bookId, readStatus)
    }

    fun updateBackgroundColor(id: String, color: Int) {
        scope.launch {
            bookDao.updateBackgroundColor(id, color)
        }
    }

    fun updateMetadata(bookId: String, title: String?, author: String?, narrator: String?, description: String?, duration: Long) {
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

    suspend fun updateBookDetails(id: String, title: String, author: String, narrator: String, description: String, year: String) = withContext(Dispatchers.IO) {
        bookDao.updateBookDetails(id, title, author, narrator, description, year)
    }

    /**
     * 保存手动上传并裁剪好的自定义封面文件。
     * 首先调用 PhysicalFileResolver 执行物理文件的复制与清理，随后安全地将新封面的物理绝对路径和主色调更新回 Room。
     */
    suspend fun saveCustomCover(bookId: String, tempCoverPath: String) = withContext(Dispatchers.IO) {
        val result = physicalFileResolver.saveCustomCover(bookId, tempCoverPath)
        if (result?.originalPath != null) {
            // 将生成好的原图路径、缩略图路径、提取的主色调更新回 Room，即刻触发 Flow 响应以重绘界面
            bookDao.updateCoverPaths(
                id = bookId,
                coverPath = result.originalPath,
                thumbnailPath = result.thumbnailPath,
                backgroundColorArgb = result.backgroundColor,
                lastScannedAt = System.currentTimeMillis()
            )
        }
    }

    /**
     * 强行重新从物理音频中解析元数据、章节，并强刷自愈物理封面缓存（供长按重建菜单使用）。
     */
    suspend fun forceRegenerateCoverAndMetadata(bookId: String) = withContext(Dispatchers.IO) {
        try {
            val book = bookDao.getBookById(bookId) ?: return@withContext
            val files = bookDao.getFilesForBookList(bookId)
            if (files.isEmpty()) return@withContext

            // 1. 委托 PhysicalFileResolver 提取主要音频文件的元数据实体
            val primaryFile = files.firstOrNull { it.status == AudiobookSchema.FileStatus.READY } ?: files.first()
            val metadata = physicalFileResolver.extractMetadata(primaryFile)

            // 2. 将提取的年份、描述、作者等详细字段覆盖写入 Room
            bookDao.updateMetadata(
                id = bookId,
                title = metadata.album.trim().ifBlank { metadata.title.trim() }.ifBlank { book.title },
                author = metadata.author,
                narrator = metadata.narrator,
                description = metadata.description,
                duration = metadata.durationMs.takeIf { it > 0 } ?: book.totalDurationMs
            )
            bookDao.updateBookDetails(
                id = bookId,
                title = metadata.album.trim().ifBlank { metadata.title.trim() }.ifBlank { book.title },
                author = metadata.author,
                narrator = metadata.narrator,
                description = metadata.description,
                year = metadata.year
            )

            // 3. 安全删除旧有物理分轨章节并批量覆盖重新插入
            if (metadata.chapters.isNotEmpty()) {
                chapterDao.deleteChaptersForBook(bookId)
                val chaptersWithBookId = metadata.chapters.map { it.copy(bookId = bookId) }
                chapterDao.insertChapters(chaptersWithBookId)
            }

            // 4. 强行重新触发物理封面的重建机制，强刷磁盘物理缓存
            physicalFileResolver.coverRecoveryHelper.forceRegenerateCover(bookId)
        } catch (e: Exception) {
            Log.e("BookLibraryRepository", "物理强制重建有声书 $bookId 的封面与元数据发生异常", e)
        }
    }

    // ==========================================
    // 6. 章节与书签 Room 交互管理
    // ==========================================

    fun getChapters(bookId: String): Flow<List<ChapterWithBookFile>> = chapterDao.getChaptersForBook(bookId)

    suspend fun getChaptersForBookSync(bookId: String): List<ChapterWithBookFile> = withContext(Dispatchers.IO) {
        chapterDao.getChaptersForBookList(bookId)
    }

    fun saveChapters(bookId: String, chapters: List<ChapterEntity>) {
        scope.launch {
            if (bookDao.getBookById(bookId) != null) {
                chapterDao.deleteChaptersForBook(bookId)
                chapterDao.insertChapters(chapters)
            }
        }
    }

    fun getBookmarks(bookId: String): Flow<List<BookmarkEntity>> = bookmarkDao.getBookmarksForBook(bookId)

    suspend fun addBookmark(bookId: String, position: Long, title: String) {
        val files = bookDao.getFilesForBookList(bookId)
        // 计算锚定状态及指纹
        val (bookFileId, fileOffsetMs, fingerprint, anchorStatus) = if (files.isNotEmpty()) {
            val (fileIndex, offset) = com.viel.aplayer.media.PositionMapper.globalToFilePosition(position, files)
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
    }

    suspend fun updateBookmark(bookmark: BookmarkEntity) {
        bookmarkDao.insert(bookmark)
    }

    suspend fun deleteBookmark(bookmark: BookmarkEntity) {
        bookmarkDao.delete(bookmark)
    }

    // ==========================================
    // 7. 运行期播放计划构建
    // ==========================================

    suspend fun getPlaybackPlan(bookId: String): BookPlaybackPlan? = withContext(Dispatchers.IO) {
        val planBuildStart = SystemClock.elapsedRealtime()
        val bookQueryStart = SystemClock.elapsedRealtime()
        val book = bookDao.getBookById(bookId) ?: return@withContext null
        val bookQueryCost = SystemClock.elapsedRealtime() - bookQueryStart
        
        // 调度自愈引擎快速对封面缓存进行检查
        physicalFileResolver.coverRecoveryHelper.checkAndTriggerCoverRegeneration(book)
        
        val filesQueryStart = SystemClock.elapsedRealtime()
        val files = bookDao.getFilesForBookList(bookId)
        val filesQueryCost = SystemClock.elapsedRealtime() - filesQueryStart
        
        val progressQueryStart = SystemClock.elapsedRealtime()
        val progress = bookDao.getProgressForBookSync(bookId)
        val progressQueryCost = SystemClock.elapsedRealtime() - progressQueryStart
        
        if (files.isEmpty()) {
            val totalCost = SystemClock.elapsedRealtime() - planBuildStart
            Log.d(
                "BookLibraryRepository",
                "getPlaybackPlan($bookId) 无可播放文件, book=${bookQueryCost}ms, files=${filesQueryCost}ms, progress=${progressQueryCost}ms, total=${totalCost}ms"
            )
            return@withContext null
        }
        
        val artworkPath = book.coverPath
        // 播放计划只暴露轻量级的 file:// 协议封面 URI，规避大文件读取开销与队列重复附加开销
        val artworkUri = artworkPath?.let { Uri.fromFile(File(it)) }

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
        Log.d(
            "BookLibraryRepository",
            "getPlaybackPlan($bookId) 完成, book=${bookQueryCost}ms, files=${filesQueryCost}ms, progress=${progressQueryCost}ms, total=${totalCost}ms, files=${plan.files.size}, start=${plan.startGlobalPositionMs}"
        )
        plan
    }

    // ==========================================
    // 8. 辅助文件实体获取及扫描状态观察
    // ==========================================

    suspend fun getFilesForBookSync(bookId: String): List<BookFileEntity> = withContext(Dispatchers.IO) {
        bookDao.getFilesForBookList(bookId)
    }

    suspend fun getAllFilesForBookSync(bookId: String): List<BookFileEntity> = withContext(Dispatchers.IO) {
        bookDao.getAllFilesForBookList(bookId)
    }

    fun observeLatestScanSession(): Flow<ScanSessionEntity?> {
        return scanSessionDao.observeLatestCompletedSession()
    }

    fun observeLibraryRoots(): Flow<List<LibraryRootEntity>> {
        return libraryRootDao.getAllRoots()
    }

    // ==========================================
    // 9. 注销/物理注销书库根目录
    // ==========================================

    suspend fun deleteLibraryRoot(root: LibraryRootEntity): Boolean = withContext(Dispatchers.IO) {
        var playbackStopped = false

        // 1. 若当前被卸载的书库内包含正在播放的有声书，先执行紧急停止指令
        try {
            val playbackManager = com.viel.aplayer.media.PlaybackManager.getInstance(context)
            val currentBookId = playbackManager.currentPlayingBookId
            if (currentBookId != null) {
                val currentBook = bookDao.getBookById(currentBookId)
                if (currentBook != null && currentBook.rootId == root.id) {
                    playbackManager.stopPlayback()
                    playbackStopped = true
                }
            }
        } catch (e: Exception) {
            Log.e("BookLibraryRepository", "检测或暂停被删除根目录的有声书播放时发生异常", e)
        }

        // 2. 在 Room 级联删除关系记录前，先递归检索该库下所有书籍并主动物理清理封面和缩略图物理文件，杜绝残留垃圾
        try {
            val books = bookDao.getBooksByRootId(root.id)
            books.forEach { book ->
                book.coverPath?.let { path ->
                    val file = File(path)
                    if (file.exists()) {
                        val deleted = file.delete()
                        Log.d("BookLibraryRepository", "物理删除有声书封面缓存文件成功，书籍ID: ${book.id}, 路径: $path, 结果: $deleted")
                    }
                }
                book.thumbnailPath?.let { path ->
                    val file = File(path)
                    if (file.exists()) {
                        val deleted = file.delete()
                        Log.d("BookLibraryRepository", "物理删除有声书缩略图缓存文件成功，书籍ID: ${book.id}, 路径: $path, 结果: $deleted")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("BookLibraryRepository", "注销根目录清理封面物理缓存时发生异常", e)
        }

        // 3. 注销系统级 SAF 目录的 persistable 权限或凭证数据
        when (LibrarySourceKind.from(root.sourceType)) {
            LibrarySourceKind.SAF -> {
                try {
                    val uri = root.sourceUri.toUri()
                    context.contentResolver.releasePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    Log.e("BookLibraryRepository", "注销 SAF 目录的持久权限失败", e)
                }
            }
            LibrarySourceKind.WEBDAV -> {
                webDavCredentialStore.delete(root.credentialId)
            }
        }

        // 4. 从 Room 中彻底删除（由于级联的外键约束，其所属的书籍和音轨实体会在事务中自动被自动擦除）
        libraryRootDao.deleteRoot(root)
        playbackStopped
    }

    // ==========================================
    // 10. 辅助实体及自愈拦截算子
    // ==========================================

    private data class Quad(
        val bookFileId: String?,
        val fileOffsetMs: Long,
        val fingerprint: String?,
        val anchorStatus: String
    )

    private fun Flow<List<BookWithProgress>>.checkCovers(): Flow<List<BookWithProgress>> = this.map { list ->
        list.onEach { physicalFileResolver.coverRecoveryHelper.checkAndTriggerCoverRegeneration(it.book) }
    }

    companion object {
        @Volatile
        private var INSTANCE: BookLibraryRepository? = null

        /**
         * 获取有声书籍库管理仓库的双检锁线程安全单例。
         */
        fun getInstance(context: Context): BookLibraryRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BookLibraryRepository(context).also { INSTANCE = it }
            }
        }
    }
}
