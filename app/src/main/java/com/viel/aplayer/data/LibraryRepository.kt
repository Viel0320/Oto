package com.viel.aplayer.data

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.content.Intent
import androidx.annotation.OptIn
import androidx.documentfile.provider.DocumentFile
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
import com.viel.aplayer.library.BookImporter
import com.viel.aplayer.library.DetailAvailabilityChecker
import com.viel.aplayer.media.BookPlaybackPlan
import com.viel.aplayer.media.PlaybackReachabilityManager
import com.viel.aplayer.media.PositionMapper
import com.viel.aplayer.media.SubtitleFileResolver
import com.viel.aplayer.media.parse.CoverRecoveryHelper
import com.viel.aplayer.ui.player.components.SubtitleLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

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
    // 详尽的中文注释：新增 CoroutineExceptionHandler 以捕获在 Dispatchers.IO 线程池中并发执行的后台逻辑（如媒体扫描、封面恢复等）中抛出的未知崩溃，增强多线程后台架构稳定性。
    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        Log.e("LibraryRepository", "Unhandled coroutine exception in LibraryRepository", exception)
    }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)
    private val importer = BookImporter(this.context)
    private val availabilityChecker = DetailAvailabilityChecker(this.context)
    private val rootStore = com.viel.aplayer.library.LibraryRootStore(this.context)
    private val coverExtractor = com.viel.aplayer.media.parse.CoverExtractor(this.context)
    private val metadataExtractor = com.viel.aplayer.media.parse.MetadataExtractor(this.context)

    // 详尽的中文注释：实例化三个全新的、低耦合的子功能组件。
    // SubtitleFileResolver 负责字幕文件的路径检索与流式解析；
    // CoverRecoveryHelper 负责非阻塞的封面物理完整性自愈重建；
    // PlaybackReachabilityManager 负责运行期的音频文件物理就绪校验、就绪降级重算与跳过检索。
    // 原 pendingRegenerations 排重集合已被安全剥离迁移至 CoverRecoveryHelper 中。
    private val subtitleResolver = SubtitleFileResolver(this.context, bookDao, libraryRootDao)
    // 详尽的中文注释：实例化低耦合的封面恢复组件，将 libraryRootDao 传入以支持利用 SAF 授权路径递归搜寻并重建同目录外部封面
    private val coverRecoveryHelper = CoverRecoveryHelper(this.context, bookDao, database.libraryRootDao(), coverExtractor, scope)
    private val reachabilityManager = PlaybackReachabilityManager(this.context, bookDao)

    // 详尽的中文注释：在内存中持久维护一份媒体库根目录的最新缓存，随 Repository 全局单例生命周期存活，用于向设置页等冷启动界面提供零延迟的首帧数据
    @Volatile
    private var cachedRoots: List<LibraryRootEntity> = emptyList()

    init {
        scope.launch {
            // 详尽的中文注释：在单例初始化时立即订阅媒体库数据流，实时将最新数据同步至内存缓存中，确保首帧数据的强一致性
            observeLibraryRoots().collect {
                cachedRoots = it
            }
        }
    }

    // 详尽的中文注释：暴露一个同步获取内存中缓存的媒体库根目录列表的公开方法，避免冷启动时由于 Room 异步查询导致的 UI 布局大幅度抖动
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

    suspend fun refreshLibraryRootStatuses() = withContext(Dispatchers.IO) {
        // Startup/settings entry use this to reflect revoked or restored SAF permissions in the UI.
        rootStore.refreshPermissionStatuses()
    }


    /**
     * Check if a file exists at the given URI.
     * 详尽的中文注释：原物理文件检测逻辑已解耦至 PlaybackReachabilityManager 组件。
     * 此处门面 API 100% 签名兼容，零侵入代理委派给该管理器。
     */
    fun checkFileExists(uriString: String): Boolean = reachabilityManager.checkFileExists(uriString)

    /**
     * Sync the entire library: scan folder and add new files.
     */
    suspend fun syncLibrary(trigger: String = "USER") = withContext(Dispatchers.IO) {
        rootStore.migrateLegacyRoot()
        rootStore.refreshPermissionStatuses()

        // New rescan path: scanner builds inventory, orchestrator decides imports, pending remains PENDING.
        val type = if (trigger == AudiobookSchema.ScanTrigger.COLD_START) {
            com.viel.aplayer.library.RescanType.COLD_START_LIGHT
        } else {
            com.viel.aplayer.library.RescanType.USER_GLOBAL
        }
        // 详尽的中文注释：扫描导入阶段不再同步提取封面；这里把既有 CoverRecoveryHelper 注入 RescanCoordinator，让新书入库后立刻复用同一套异步封面重建与去重机制。
        val session = com.viel.aplayer.library.RescanCoordinator(
            context = context,
            triggerCoverRegeneration = coverRecoveryHelper::checkAndTriggerCoverRegeneration
        ).rescan(type)
        Log.i("LibraryRepository", "Sync finished. New: ${session.discoveredBookCount}, Pending: ${session.pendingActionCount}")
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

    /**
     * 顺序添加有声书，并整合元数据提取。
     */
    @SuppressLint("CheckResult")
    @OptIn(UnstableApi::class)
    suspend fun addAudiobook(
        uri: Uri, 
        parentDir: DocumentFile? = null, 
        fileName: String? = null,
        existingBookId: String? = null
    ) {
        withContext(Dispatchers.IO) {
            Log.d("LibraryRepository", "Adding audiobook: $uri (Name: $fileName)")
            val bookId = UUID.randomUUID().toString()
            
            try {
                // 1. 获取文件大小，后续导入记录需要保存原始文件体积。
                var fileSize = 0L
                if (uri.scheme == "content") {
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (cursor.moveToFirst()) fileSize = cursor.getLong(sizeIndex)
                    }
                } else if (uri.scheme == "file") {
                    fileSize = File(uri.path ?: "").length()
                }

                // 2. 使用 MetadataExtractor 提取音频元数据及章节。
                val metadata = metadataExtractor.extract(uri)

                // Compatibility import still extracts embedded cover before delegating to the importer.
                val retriever = MediaMetadataRetriever()
                val coverResult = try {
                    retriever.setDataSource(context, uri)
                    coverExtractor.extractFromRetriever(retriever, bookId)
                } finally {
                    // MediaMetadataRetriever must always be released even when setDataSource fails.
                    retriever.release()
                }
                val finalCoverResult = if (coverResult.hasImage()) {
                    coverResult
                } else {
                    // Direct single-file import falls back to cover/folder/artwork/front in the chosen directory.
                    parentDir?.let { coverExtractor.extractFromDirectory(it) }
                        ?: com.viel.aplayer.media.parse.CoverExtractor.CoverResult(null, null)
                }

                val lastModified = if (uri.scheme == "file") File(uri.path ?: "").lastModified() else System.currentTimeMillis()

                // Compatibility import delegates persistence to BookImporter and still skips initial progress.
                importer.importSingleAudio(
                    uri = uri.toString(),
                    // Single-file import title priority: album -> title -> filename without extension.
                    title = metadata.album.trim()
                        .ifBlank { metadata.title.trim() }
                        .ifBlank { fileName?.substringBeforeLast('.') ?: uri.lastPathSegment?.substringAfterLast("/")?.substringBeforeLast(".").orEmpty() },
                    author = metadata.author,
                    narrator = metadata.narrator,
                    description = metadata.description,
                    year = metadata.year,
                    durationMs = metadata.durationMs,
                    fileSize = fileSize,
                    lastModified = lastModified,
                    coverPath = finalCoverResult.originalPath,
                    thumbnailPath = finalCoverResult.thumbnailPath,
                    backgroundColorArgb = finalCoverResult.backgroundColor,
                    chapters = metadata.chapters,
                    existingBookId = existingBookId
                )

            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("LibraryRepository", "Error adding audiobook", e)
            }
        }
    }
    
    // CoverResult may legitimately contain no image when neither embedded art nor sidecar images exist.
    private fun com.viel.aplayer.media.parse.CoverExtractor.CoverResult.hasImage(): Boolean =
        originalPath != null || thumbnailPath != null

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

            // 为每一次改动添加详尽的中文注释：根据当前播放进度，在后台自动物理判定并推进有声书的阅读状态（未开始/进行中/已完成），实现进度与状态的最终一致性
            val book = bookDao.getBookById(bookId)
            if (book != null) {
                val isFinished = position >= (book.totalDurationMs * 0.99).toLong()
                val nextStatus = if (isFinished) {
                    AudiobookSchema.ReadStatus.FINISHED
                } else if (position > 0) {
                    AudiobookSchema.ReadStatus.IN_PROGRESS
                } else {
                    AudiobookSchema.ReadStatus.NOT_STARTED
                }
                if (book.readStatus != nextStatus) {
                    bookDao.updateBookReadStatus(bookId, nextStatus)
                }
            }
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
     * 详尽的中文注释：原字幕搜索（SAF Tree 递归）、相似名匹配及文件流式解析已被完全解耦并迁移至 SubtitleFileResolver。
     * 此处门面 API 进行委派，保持原入参 and 签名完全不变，不对业务模块造成任何侵入性影响。
     */
    suspend fun loadSubtitlesForUri(mediaUri: Uri): List<SubtitleLine> = subtitleResolver.loadSubtitlesForUri(mediaUri)

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
     * 为每一次改动添加详尽的中文注释：
     * 异步更新特定书籍的完整元数据（书名、作者、讲述人、简介、年份），专门供修改器页面使用以保存用户修改
     */
    suspend fun updateBookDetails(id: String, title: String, author: String, narrator: String, description: String, year: String) = withContext(Dispatchers.IO) {
        bookDao.updateBookDetails(id, title, author, narrator, description, year)
    }

    /**
     * 为每一次改动添加详尽的中文注释：
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
     * 为每一次改动添加详尽的中文注释：同步获取书籍的全部文件列表，包含 AUDIO 与 SOURCE_MANIFEST，专用于详情页精炼路径与源文件名解析
     */
    suspend fun getAllFilesForBookSync(bookId: String): List<BookFileEntity> = withContext(Dispatchers.IO) {
        bookDao.getAllFilesForBookList(bookId)
    }

    // UI availability checks use the first AUDIO file because Book no longer owns a sourceUri.
    suspend fun getPrimaryAudioUri(bookId: String): String? = withContext(Dispatchers.IO) {
        bookDao.getFilesForBookList(bookId).firstOrNull()?.uri
    }

    // DetailScreen owns old BookFile reachability checks, outside the rescan flow.
    suspend fun checkDetailAvailability(bookId: String): Boolean = withContext(Dispatchers.IO) {
        availabilityChecker.check(bookId).isAvailable
    }

    // Compact player reload only cares about the restored/current file, not whether the whole book has any playable file.
    // 详尽的中文注释：前台恢复 compact 播放进度时的物理音频就绪判定门面 API，现委派至 PlaybackReachabilityManager。
    suspend fun checkCurrentPlaybackFileAvailability(bookId: String): Boolean = 
        reachabilityManager.checkCurrentPlaybackFileAvailability(bookId)

    // PlaybackService marks the exact failed queue item missing before it tries to advance.
    // 详尽的中文注释：运行期标记具体某轨音频文件物理丢失的门面 API，现委派至 PlaybackReachabilityManager。
    suspend fun markPlaybackFileUnavailable(bookId: String, queueIndex: Int) = 
        reachabilityManager.markPlaybackFileUnavailable(bookId, queueIndex)

    // PlaybackService asks for the next actually openable queue item so stale READY rows do not cause repeated failures.
    // 详尽的中文注释：在当前正在播放的文件发生不可达异常时，检索列表中下一个可播放（READY）音频轨的门面 API，现委派至 PlaybackReachabilityManager。
    suspend fun findNextAvailablePlaybackFile(bookId: String, afterQueueIndex: Int): Pair<Int, BookFileEntity>? = 
        reachabilityManager.findNextAvailablePlaybackFile(bookId, afterQueueIndex)

    suspend fun getPlaybackPlan(bookId: String): BookPlaybackPlan? = withContext(Dispatchers.IO) {
        val book = bookDao.getBookById(bookId) ?: return@withContext null
        // 详尽的中文注释：在构建播放计划（即开始播放有声书）时，委托 CoverRecoveryHelper 静默快速物理检查一次封面。
        // 若物理缓存丢失，将非阻塞地派发后台协程启动漏斗模型提取机制进行零延迟自愈。
        coverRecoveryHelper.checkAndTriggerCoverRegeneration(book)
        val files = bookDao.getFilesForBookList(bookId)
        val chapters = chapterDao.getChaptersForBookList(bookId)
        val progress = bookDao.getProgressForBookSync(bookId)
        
        if (files.isEmpty()) return@withContext null
        
        val artworkPath = book.coverPath
        // Cover cache paths are local files; expose them as file URIs and bytes for Media3 artwork.
        val artworkUri = artworkPath?.let { Uri.fromFile(File(it)) }

        val artworkData = artworkPath?.let { path ->
            try { File(path).readBytes() } catch (e: Exception) { null }
        }

        BookPlaybackPlan(
            bookId = bookId,
            title = book.title,
            author = book.author,
            artworkUri = artworkUri,
            artworkData = artworkData,
            files = files,
            chapters = chapters,
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

    // 为每一次改动添加详尽的中文注释：根据书籍 ID 更新有声书的阅读状态（未开始/进行中/已完成）
    suspend fun updateBookReadStatus(bookId: String, readStatus: String) = withContext(Dispatchers.IO) {
        bookDao.updateBookReadStatus(bookId, readStatus)
    }

    // 为每一次改动添加详尽的中文注释：强制重新生成指定有声书的物理封面与元数据（包括章节信息），
    // 专门用于长按菜单中的“重建封面与元数据”功能，实现从音频物理原文件中执行全量提取与数据库覆盖写入，并强行打破缓存刷新 UI
    suspend fun forceRegenerateCoverAndMetadata(bookId: String) = withContext(Dispatchers.IO) {
        try {
            val book = bookDao.getBookById(bookId) ?: return@withContext
            val files = bookDao.getFilesForBookList(bookId)
            if (files.isEmpty()) return@withContext

            // 1. 提取主要音频文件的物理元数据
            val primaryFile = files.firstOrNull { it.status == AudiobookSchema.FileStatus.READY } ?: files.first()
            val uri = Uri.parse(primaryFile.uri)
            val metadata = metadataExtractor.extract(uri)

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
        // 详尽的中文注释：查询特定有声书实体时，委托 CoverRecoveryHelper 先快速静默地物理检查封面并触发自愈重建。
        bookDao.getBookById(id)?.also { coverRecoveryHelper.checkAndTriggerCoverRegeneration(it) }
    }

    fun observeBookById(id: String): Flow<BookEntity?> {
        // 详尽的中文注释：外部观察特定有声书实体时，通过 Flow map 进行非阻塞式的封面物理完整性自愈调度委派。
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

    // 详尽的中文注释：针对 Flow<List<BookWithProgress>> 数据流的自定义扩展算子。
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

        // 为每一次改动添加详尽的中文注释：在通过 Room 级联删除数据库记录之前，先主动查询并物理清理该书库下所有书籍的封面原图与缩略图缓存文件，防止物理缓存目录随着书库删除后无限膨胀残留垃圾文件
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

        // 释放 SAF 持久化授权
        try {
            val uri = Uri.parse(root.treeUri)
            context.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: Exception) {
            Log.e("LibraryRepository", "Failed to release persistable permission for ${root.treeUri}", e)
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
