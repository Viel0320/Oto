package com.viel.aplayer.data

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.util.Log
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.data.db.AppDatabase
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.BookProgressEntity
import com.viel.aplayer.data.entity.BookWithProgress
import com.viel.aplayer.data.entity.BookmarkEntity
import com.viel.aplayer.data.entity.ChapterEntity
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.data.entity.ScanSessionEntity
import com.viel.aplayer.data.store.SearchHistoryEntry
import com.viel.aplayer.data.store.SearchHistoryStore
import com.viel.aplayer.library.DetailAvailabilityChecker
import com.viel.aplayer.library.availability.AvailabilityChecker
import com.viel.aplayer.library.sourceProvider.LibrarySourceKind
import com.viel.aplayer.library.sourceProvider.webdav.WebDavCredentialStore
import com.viel.aplayer.media.BookPlaybackPlan
import com.viel.aplayer.media.PlaybackReachabilityManager
import com.viel.aplayer.media.PositionMapper
import com.viel.aplayer.media.subtitle.SubtitleFileResolver
import com.viel.aplayer.media.parser.CoverRecoveryHelper
import com.viel.aplayer.ui.player.components.SubtitleLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
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
import androidx.core.net.toUri

/**
 * Repository that wraps Room database and handles cover art caching.
 */
@OptIn(UnstableApi::class)
class LibraryRepository private constructor(context: Context) {
    @SuppressLint("StaticFieldLeak")
    private val context = context.applicationContext
    private val database = AppDatabase.getInstance(this.context)
    private val bookDao = database.bookDao()
    private val chapterDao = database.chapterDao()
    private val bookmarkDao = database.bookmarkDao()
    private val libraryRootDao = database.libraryRootDao()
    private val scanSessionDao = database.scanSessionDao()
    private val searchHistoryStore = SearchHistoryStore.getInstance(this.context)
    // 新增 CoroutineExceptionHandler 以捕获在 Dispatchers.IO 线程池中并发执行的后台逻辑（如媒体扫描、封面恢复等）中抛出的未知崩溃，增强多线程后台架构稳定性。
    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        Log.e("LibraryRepository", "Unhandled coroutine exception in LibraryRepository", exception)
    }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)
    // 扫描任务提升到 Repository 单例的应用级队列，并用互斥锁串行执行，页面切换不会取消正在进行的导入。
    private val scanMutex = Mutex()
    private val availabilityChecker = DetailAvailabilityChecker(this.context)
    private val fileAvailabilityChecker = AvailabilityChecker(this.context)
    private val rootStore = com.viel.aplayer.library.LibraryRootStore(this.context)
    // Repository 负责删除 root 时清理 WebDAV 凭据引用，避免 UI 层接触凭据存储。
    private val webDavCredentialStore = WebDavCredentialStore(this.context)
    private val coverExtractor = com.viel.aplayer.media.parser.CoverExtractor(this.context)
    private val MetadataResolver = com.viel.aplayer.media.parser.MetadataResolver(this.context)

    // 实例化三个全新的、低耦合的子功能组件。
    // SubtitleFileResolver 负责字幕文件的路径检索与流式解析；
    // CoverRecoveryHelper 负责非阻塞的封面物理完整性自愈重建；
    // PlaybackReachabilityManager 负责运行期的音频文件物理就绪校验、就绪降级重算与跳过检索。
    // 原 pendingRegenerations 排重集合已被安全剥离迁移至 CoverRecoveryHelper 中。
    private val subtitleResolver = SubtitleFileResolver(this.context, bookDao, libraryRootDao)
    // 实例化低耦合的封面恢复组件，将 libraryRootDao 传入以支持利用 SAF 授权路径递归搜寻并重建同目录外部封面
    private val coverRecoveryHelper = CoverRecoveryHelper(
        this.context,
        bookDao,
        database.libraryRootDao(),
        coverExtractor,
        scope
    )
    private val reachabilityManager = PlaybackReachabilityManager(this.context, bookDao, libraryRootDao)

    // 在内存中持久维护一份媒体库根目录的最新缓存，随 Repository 全局单例生命周期存活，用于向设置页等冷启动界面提供零延迟的首帧数据
    @Volatile
    private var cachedRoots: List<LibraryRootEntity> = emptyList()

    init {
        scope.launch {
            // 在单例初始化时立即订阅媒体库数据流，实时将最新数据同步至内存缓存中，确保首帧数据的强一致性
            observeLibraryRoots().collect {
                cachedRoots = it
            }
        }
    }

    // 暴露一个同步获取内存中缓存的媒体库根目录列表的公开方法，避免冷启动时由于 Room 异步查询导致的 UI 布局大幅度抖动
    fun getCachedLibraryRoots(): List<LibraryRootEntity> = cachedRoots

    /** Search history flow. */
    val searchHistory: Flow<List<SearchHistoryEntry>> = searchHistoryStore.history

    suspend fun addToHistory(query: String) {
        if (query.isNotBlank()) {
            // DataStore owns recency ordering and duplicate replacement for search history.
            searchHistoryStore.add(query)
        }
    }

    suspend fun deleteFromHistory(history: SearchHistoryEntry) {
        // Deleting a visible history row updates DataStore observers immediately.
        searchHistoryStore.delete(history)
    }

    suspend fun clearHistory() {
        // Settings and search-screen clear actions share the same DataStore-backed operation.
        searchHistoryStore.clear()
    }

    /** Set the root library directory. */
    suspend fun setLibraryRoot(uri: Uri): LibraryRootEntity = withContext(Dispatchers.IO) {
        // LibraryRootStore owns duplicate detection and grant-state refresh before any scan starts.
        rootStore.addRoot(uri, "My Library")
    }

    suspend fun addWebDavLibraryRoot(
        url: String,
        username: String,
        password: String,
        displayName: String,
        basePath: String
    ): LibraryRootEntity = withContext(Dispatchers.IO) {
        // WebDAV 根目录新增走 LibraryRootStore，Repository 只负责对外提供应用层入口。
        rootStore.addWebDavRoot(
            url = url,
            username = username,
            password = password,
            displayName = displayName,
            basePath = basePath
        )
    }

    fun addLibraryRootAndScheduleSync(uri: Uri, trigger: String = "USER") {
        // SAF 授权、root 入库和随后的扫描作为一个应用级后台任务执行，避免选择目录后切页取消后续扫描。
        scope.launch {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                setLibraryRoot(uri)
                syncLibrary(trigger)
            }.onFailure { error ->
                Log.e("LibraryRepository", "Failed to add SAF library root and schedule sync: ${uri.hashCode().toString(16)}", error)
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
        // WebDAV root 写入和扫描调度同样交给应用级后台队列，设置页关闭不会中断远程库扫描。
        scope.launch {
            runCatching {
                addWebDavLibraryRoot(
                    url = url,
                    username = username,
                    password = password,
                    displayName = displayName,
                    basePath = basePath
                )
                syncLibrary(trigger)
            }.onFailure { error ->
                Log.e("LibraryRepository", "Failed to add WebDAV library root and schedule sync", error)
            }
        }
    }

    suspend fun refreshLibraryRootStatuses() = withContext(Dispatchers.IO) {
        // Startup/settings entry use this to reflect revoked or restored SAF permissions in the UI.
        rootStore.refreshPermissionStatuses()
    }


    suspend fun checkPrimaryAudioFileExists(bookId: String): Boolean = withContext(Dispatchers.IO) {
        // 删除书籍等 UI 反馈也通过 BookFileEntity 的 VFS 可达性检测，不再使用 URI 旁路。
        val primaryFile = bookDao.getFilesForBookList(bookId).firstOrNull() ?: return@withContext false
        fileAvailabilityChecker.checkBookFile(primaryFile).isAvailable
    }

    /**
     * Sync the entire library: scan folder and add new files.
     */
    suspend fun syncLibrary(trigger: String = "USER") = scanMutex.withLock {
        // 无论调用来自 WorkManager、冷启动还是设置页，实际扫描都串行进入同一个应用级临界区，避免并发扫描互相清空 pending 状态。
        runSyncLibrary(trigger)
    }

    private suspend fun runSyncLibrary(trigger: String = "USER") = withContext(Dispatchers.IO) {
        // 旧版 SharedPreferences 单根目录迁移入口已移除，同步只依赖当前 library_roots 表中的标准件来源。
        rootStore.refreshPermissionStatuses()

        // New rescan path: scanner builds inventory, orchestrator decides imports, pending remains PENDING.
        val type = if (trigger == AudiobookSchema.ScanTrigger.COLD_START) {
            com.viel.aplayer.library.RescanType.COLD_START_LIGHT
        } else {
            com.viel.aplayer.library.RescanType.USER_GLOBAL
        }
        // 扫描导入阶段不再同步提取封面；这里把既有 CoverRecoveryHelper 注入 RescanCoordinator，让新书入库后立刻复用同一套异步封面重建与去重机制。
        val session = com.viel.aplayer.library.RescanCoordinator(
            context = context,
            triggerCoverRegeneration = coverRecoveryHelper::checkAndTriggerCoverRegeneration
        ).rescan(type)
        Log.i("LibraryRepository", "Sync finished. New: ${session.discoveredBookCount}, Pending: ${session.pendingActionCount}")
    }

    fun scheduleLibrarySync(trigger: String = "USER") {
        // 所有 UI 只负责发起扫描请求，实际执行交给 Repository 应用级后台 scope，避免页面切换取消长扫描。
        scope.launch { syncLibrary(trigger) }
    }


    
    /** Observable list of all audiobooks, sorted by added date. */
    val audiobooks: Flow<List<BookWithProgress>> = bookDao.getAllBooksWithProgress().checkCovers()

    /** Search audiobooks by title, author, or narrator. */
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

    /** Update playback position in milliseconds. */
    fun updateProgress(bookId: String, position: Long) {
        scope.launch {
            val progress = bookDao.getProgressForBookSync(bookId)
            val files = bookDao.getFilesForBookList(bookId)
            
            if (files.isNotEmpty()) {
                val (fileIndex, posInFile) = PositionMapper.globalToFilePosition(position, files)
                val bookFileId = files.getOrNull(fileIndex)?.id

                // Progress is upserted on first real playback/seek, not during import.
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
                // 回退逻辑：如果无法映射到 BookFile，只更新全局播放位置。
                bookDao.insertProgress(progress.copy(
                    globalPositionMs = position,
                    lastPlayedAt = System.currentTimeMillis()
                ))
            }

            updateReadStatusFromProgress(bookId, position)
        }
    }

    /**
     * Get chapters for an audiobook.
     */
    fun getChapters(bookId: String): Flow<List<ChapterEntity>> = chapterDao.getChaptersForBook(bookId)

    // PlaybackService needs a synchronous snapshot to map notification progress without depending on UI collectors.
    suspend fun getChaptersForBookSync(bookId: String): List<ChapterEntity> = withContext(Dispatchers.IO) {
        chapterDao.getChaptersForBookList(bookId)
    }

    /**
     * Get bookmarks for an audiobook.
     */
    fun getBookmarks(bookId: String): Flow<List<BookmarkEntity>> = bookmarkDao.getBookmarksForBook(bookId)

    /**
     * Add a bookmark.
     */
    suspend fun addBookmark(bookId: String, position: Long, title: String) {
        val files = bookDao.getFilesForBookList(bookId)
        val (bookFileId, fileOffsetMs, fingerprint, anchorStatus) = if (files.isNotEmpty()) {
            val (fileIndex, offset) = PositionMapper.globalToFilePosition(position, files)
            val file = files.getOrNull(fileIndex)
            Quad(file?.id, offset, file?.fingerprint, AudiobookSchema.AnchorStatus.OK)
        } else {
            Quad(null, 0L, null, AudiobookSchema.AnchorStatus.UNRESOLVED)
        }
        // Bookmark stores both display global position and stable file anchor.
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

    /**
     * Update a bookmark.
     */
    suspend fun updateBookmark(bookmark: BookmarkEntity) {
        bookmarkDao.insert(bookmark)
    }

    /**
     * Delete a bookmark.
     */
    suspend fun deleteBookmark(bookmark: BookmarkEntity) {
        bookmarkDao.delete(bookmark)
    }

    /**
     * Load and parse subtitles for an audiobook file.
     * Looks for a subtitle file with the same name in the same directory.
     * 字幕门面改用 BookFileEntity.id 直连 VFS 读流，不再保留 MediaItem.uri 反查数据库的旧入口。
     */
    suspend fun loadSubtitlesForBookFile(bookFileId: String): List<SubtitleLine> =
        subtitleResolver.loadSubtitlesForBookFile(bookFileId)

    /**
     * Save chapters to database.
     */
    fun saveChapters(bookId: String, chapters: List<ChapterEntity>) {
        scope.launch {
            if (bookDao.getBookById(bookId) != null) {
                chapterDao.deleteChaptersForBook(bookId)
                chapterDao.insertChapters(chapters)
            }
        }
    }

    /**
     * Update title/author/narrator/description from ExoPlayer's parsed metadata.
     */
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

    /**
         * 异步更新特定书籍的完整元数据（书名、作者、讲述人、简介、年份），专门供修改器页面使用以保存用户修改
     */
    suspend fun updateBookDetails(id: String, title: String, author: String, narrator: String, description: String, year: String) = withContext(Dispatchers.IO) {
        bookDao.updateBookDetails(id, title, author, narrator, description, year)
    }

    /**
         * 保存手动上传并裁剪好的自定义封面文件。
     * 首先调用 coverExtractor 执行物理复制、缩略图重建及主色调计算，
     * 随后安全且物理地删除该书籍原有的旧封面与旧缩略图文件以杜绝垃圾文件膨胀，
     * 最后将新封面的物理绝对路径、缩略图路径以及提取的主色调，连同当前系统时间戳更新回 Room 数据库，即刻触发 UI 强刷。
     */
    suspend fun saveCustomCover(bookId: String, tempCoverPath: String) = withContext(Dispatchers.IO) {
        try {
            val book = bookDao.getBookById(bookId) ?: return@withContext

            // 1. 调用 coverExtractor 保存封面并计算主色调与缩略图
            val result = coverExtractor.saveCustomCover(bookId, tempCoverPath)
            if (result.originalPath != null) {
                // 2. 物理清除旧封面的原图和缩略图文件，释放磁盘空间
                book.coverPath?.let { oldPath ->
                    val oldFile = File(oldPath)
                    if (oldFile.exists()) {
                        oldFile.delete()
                    }
                }
                book.thumbnailPath?.let { oldPath ->
                    val oldFile = File(oldPath)
                    if (oldFile.exists()) {
                        oldFile.delete()
                    }
                }

                // 3. 将新生成的封面相关路径、主色调、以及当前毫秒时间戳更新回数据库，迫使 Flow 重发以触发布局即时重绘
                bookDao.updateCoverPaths(
                    id = bookId,
                    coverPath = result.originalPath,
                    thumbnailPath = result.thumbnailPath,
                    backgroundColorArgb = result.backgroundColor,
                    lastScannedAt = System.currentTimeMillis()
                )

                // 4. 清理裁剪后留在临时存储目录下的临时 temp 文件
                val tempFile = File(tempCoverPath)
                if (tempFile.exists()) {
                    tempFile.delete()
                }
            }
        } catch (e: Exception) {
            Log.e("LibraryRepository", "手动为有声书 $bookId 保存自定义封面发生异常: ", e)
        }
    }
    
    suspend fun saveProgress(progress: BookProgressEntity) = withContext(Dispatchers.IO) {
        // Playback callbacks may arrive on the main thread, so progress upserts are forced onto IO.
        bookDao.insertProgress(progress)
        // 播放器真实保存进度只会进入 saveProgress，因此这里同步推进 books.readStatus，保证首页筛选和状态标签不再依赖已废弃的 updateProgress 入口。
        updateReadStatusFromProgress(progress.bookId, progress.globalPositionMs)
    }

    // 将播放进度到阅读状态的换算集中在一个私有方法中，避免 updateProgress 与 saveProgress 出现两套状态规则。
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

    // PlayerViewModel calls this once on app cold start to restore the compact player without scanning UI lists.
    suspend fun getLastPlayedProgressSync(): BookProgressEntity? = withContext(Dispatchers.IO) {
        bookDao.getLastPlayedProgressSync()
    }

    /**
     * 同步获取书籍的文件列表，用于内部位置映射和播放计划转换。
     */
    suspend fun getFilesForBookSync(bookId: String): List<BookFileEntity> = withContext(Dispatchers.IO) {
        bookDao.getFilesForBookList(bookId)
    }

    /**
     * 同步获取书籍的全部文件列表，包含 AUDIO 与 SOURCE_MANIFEST，专用于详情页精炼路径与源文件名解析
     */
    suspend fun getAllFilesForBookSync(bookId: String): List<BookFileEntity> = withContext(Dispatchers.IO) {
        bookDao.getAllFilesForBookList(bookId)
    }

    // DetailScreen owns old BookFile reachability checks, outside the rescan flow.
    suspend fun checkDetailAvailability(bookId: String): Boolean = withContext(Dispatchers.IO) {
        availabilityChecker.check(bookId).isAvailable
    }

    // Compact player reload only cares about the restored/current file, not whether the whole book has any playable file.
    // 前台恢复 compact 播放进度时的物理音频就绪判定门面 API，现委派至 PlaybackReachabilityManager。
    suspend fun checkCurrentPlaybackFileAvailability(bookId: String): Boolean = 
        reachabilityManager.checkCurrentPlaybackFileAvailability(bookId)

    // PlaybackService marks the exact failed queue item missing before it tries to advance.
    // 运行期标记具体某轨音频文件物理丢失的门面 API，现委派至 PlaybackReachabilityManager。
    suspend fun markPlaybackFileUnavailable(bookId: String, queueIndex: Int) = 
        reachabilityManager.markPlaybackFileUnavailable(bookId, queueIndex)

    // PlaybackService asks for the next actually openable queue item so stale READY rows do not cause repeated failures.
    // 在当前正在播放的文件发生不可达异常时，检索列表中下一个可播放（READY）音频轨的门面 API，现委派至 PlaybackReachabilityManager。
    suspend fun findNextAvailablePlaybackFile(bookId: String, afterQueueIndex: Int): Pair<Int, BookFileEntity>? = 
        reachabilityManager.findNextAvailablePlaybackFile(bookId, afterQueueIndex)

    suspend fun getPlaybackPlan(bookId: String): BookPlaybackPlan? = withContext(Dispatchers.IO) {
        val book = bookDao.getBookById(bookId) ?: return@withContext null
        // 在构建播放计划（即开始播放有声书）时，委托 CoverRecoveryHelper 静默快速物理检查一次封面。
        // 若物理缓存丢失，将非阻塞地派发后台协程启动漏斗模型提取机制进行零延迟自愈。
        coverRecoveryHelper.checkAndTriggerCoverRegeneration(book)
        val files = bookDao.getFilesForBookList(bookId)
        val progress = bookDao.getProgressForBookSync(bookId)
        
        if (files.isEmpty()) return@withContext null
        
        val artworkPath = book.coverPath
        // 播放计划在启动链路里只暴露轻量的 file:// 封面 URI，
        // 不再同步把封面原图整文件读成 ByteArray 塞进计划对象；
        // 这样可以直接避开一次大图磁盘读取，也能避免后续把同一张封面字节重复附着到整条多分轨播放队列里。
        val artworkUri = artworkPath?.let { Uri.fromFile(File(it)) }

        BookPlaybackPlan(
            bookId = bookId,
            title = book.title,
            author = book.author,
            artworkUri = artworkUri,
            files = files,
            // 播放计划不预解析整本书字幕；当前文件字幕由 PlayerViewModel 按需加载，避免多文件启动阻塞。
            subtitlesByFileId = emptyMap(),
            startGlobalPositionMs = progress?.globalPositionMs ?: 0L
        )
    }

    /**
     * Cache the dominant color of the book cover.
     */
    fun updateBackgroundColor(id: String, color: Int) {
        scope.launch {
            bookDao.updateBackgroundColor(id, color)
        }
    }

    suspend fun deleteBook(bookId: String) {
        val book = bookDao.getBookById(bookId)
        if (book != null) {
            // Soft delete keeps BookFile ownership so rescans do not immediately re-import the same files.
            bookDao.updateBookStatus(bookId, AudiobookSchema.BookStatus.DELETED)
        }
    }

    // 根据书籍 ID 更新有声书的阅读状态（未开始/进行中/已完成）
    suspend fun updateBookReadStatus(bookId: String, readStatus: String) = withContext(Dispatchers.IO) {
        bookDao.updateBookReadStatus(bookId, readStatus)
    }

    // 强制重新生成指定有声书的物理封面与元数据（包括章节信息），
    // 专门用于长按菜单中的“重建封面与元数据”功能，实现从音频物理原文件中执行全量提取与数据库覆盖写入，并强行打破缓存刷新 UI
    suspend fun forceRegenerateCoverAndMetadata(bookId: String) = withContext(Dispatchers.IO) {
        try {
            val book = bookDao.getBookById(bookId) ?: return@withContext
            val files = bookDao.getFilesForBookList(bookId)
            if (files.isEmpty()) return@withContext

            // 1. 提取主要音频文件的物理元数据
            val primaryFile = files.firstOrNull { it.status == AudiobookSchema.FileStatus.READY } ?: files.first()
            // 强制重建元数据通过 BookFileEntity 的 VFS 路径读取，不再恢复旧 uri 字段。
            val metadata = MetadataResolver.extract(primaryFile)

            // 2. 将全新提取出来的有声书基本元数据和年份字段覆盖更新回 Room 数据库中
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

            // 3. 重置物理章节，安全清除旧有章节并批量重新插入全新提取的章节
            if (metadata.chapters.isNotEmpty()) {
                chapterDao.deleteChaptersForBook(bookId)
                val chaptersWithBookId = metadata.chapters.map { it.copy(bookId = bookId) }
                chapterDao.insertChapters(chaptersWithBookId)
            }

            // 4. 强行重新触发物理封面的后台非阻塞重建与缓存强刷
            coverRecoveryHelper.forceRegenerateCover(bookId)
        } catch (e: Exception) {
            Log.e("LibraryRepository", "物理强制重建有声书 $bookId 的封面与元数据失败，原因: ", e)
        }
    }

    suspend fun getBookById(id: String): BookEntity? = withContext(Dispatchers.IO) {
        // 查询特定有声书实体时，委托 CoverRecoveryHelper 先快速静默地物理检查封面并触发自愈重建。
        bookDao.getBookById(id)?.also { coverRecoveryHelper.checkAndTriggerCoverRegeneration(it) }
    }

    fun observeBookById(id: String): Flow<BookEntity?> {
        // 外部观察特定有声书实体时，通过 Flow map 进行非阻塞式的封面物理完整性自愈调度委派。
        return bookDao.observeBookById(id).map { book ->
            book?.also { coverRecoveryHelper.checkAndTriggerCoverRegeneration(it) }
        }
    }

    fun observeLatestScanSession(): Flow<ScanSessionEntity?> {
        return scanSessionDao.observeLatestCompletedSession()
    }

    /** Observe all library root directories. */
    fun observeLibraryRoots(): Flow<List<LibraryRootEntity>> {
        return libraryRootDao.getAllRoots()
    }

    // Small local carrier for bookmark anchor mapping results.
    private data class Quad(
        val bookFileId: String?,
        val fileOffsetMs: Long,
        val fingerprint: String?,
        val anchorStatus: String
    )

    // 针对 Flow<List<BookWithProgress>> 数据流的自定义扩展算子。
    // 将封面物理文件检查与自愈重建委托给全新的 CoverRecoveryHelper 辅助组件执行，在保持流非阻塞特性的同时极大精炼了 Repository 内部逻辑。
    private fun Flow<List<BookWithProgress>>.checkCovers(): Flow<List<BookWithProgress>> = this.map { list ->
        list.onEach { coverRecoveryHelper.checkAndTriggerCoverRegeneration(it.book) }
    }

    // 删除媒体库根目录：释放 SAF 授权、停止受影响的播放、清理 Room 数据。
    // 返回 true 表示当前播放被中断（属于该库），false 表示播放未受影响。
    suspend fun deleteLibraryRoot(root: LibraryRootEntity): Boolean = withContext(Dispatchers.IO) {
        var playbackStopped = false

        // 检查当前正在播放的书籍是否属于即将删除的书库
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
            Log.e("LibraryRepository", "Failed to check/stop playback during library root deletion", e)
        }

        // 在通过 Room 级联删除数据库记录之前，先主动查询并物理清理该书库下所有书籍的封面原图与缩略图缓存文件，防止物理缓存目录随着书库删除后无限膨胀残留垃圾文件
        try {
            val books = bookDao.getBooksByRootId(root.id)
            books.forEach { book ->
                book.coverPath?.let { path ->
                    val file = File(path)
                    if (file.exists()) {
                        val deleted = file.delete()
                        Log.d("LibraryRepository", "物理删除有声书封面缓存文件成功，书籍ID: ${book.id}, 路径: $path, 结果: $deleted")
                    }
                }
                book.thumbnailPath?.let { path ->
                    val file = File(path)
                    if (file.exists()) {
                        val deleted = file.delete()
                        Log.d("LibraryRepository", "物理删除有声书缩略图缓存文件成功，书籍ID: ${book.id}, 路径: $path, 结果: $deleted")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("LibraryRepository", "Failed to clear cover cache during library root deletion", e)
        }

        when (LibrarySourceKind.from(root.sourceType)) {
            LibrarySourceKind.SAF -> {
                // 只有 SAF root 需要释放系统持久化授权，远程 root 不再误走 ContentResolver。
                try {
                    val uri = root.sourceUri.toUri()
                    context.contentResolver.releasePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    Log.e("LibraryRepository", "Failed to release persistable permission for ${root.sourceUri}", e)
                }
            }
            LibrarySourceKind.WEBDAV -> {
                // 删除 WebDAV root 时同步清理 credentialId 指向的本地凭据，不触碰任何远端文件。
                webDavCredentialStore.delete(root.credentialId)
            }
        }

        // 从 Room 中删除（CASCADE 外键会自动清理 books/book_files）
        libraryRootDao.deleteRoot(root)
        playbackStopped
    }

    
    companion object {
        @Volatile
        @SuppressLint("StaticFieldLeak")
        private var INSTANCE: LibraryRepository? = null

        fun getInstance(context: Context): LibraryRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LibraryRepository(context).also { INSTANCE = it }
            }
        }
    }
}
