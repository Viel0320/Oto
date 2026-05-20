package com.viel.aplayer.data

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
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
import com.viel.aplayer.data.entity.SearchHistoryEntity
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
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
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

    /** Search history flow. */
    val searchHistory: Flow<List<SearchHistoryEntity>> = searchHistoryStore.history

    suspend fun addToHistory(query: String) {
        if (query.isNotBlank()) {
            // DataStore owns recency ordering and duplicate replacement for search history.
            searchHistoryStore.add(query)
        }
    }

    suspend fun deleteFromHistory(history: SearchHistoryEntity) {
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
        val type = if (trigger == AudiobookSchema.ScanTrigger.COLD_START || trigger == "COLD_START") {
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
                val (fileIndex, posInFile) = com.viel.aplayer.media.PositionMapper.globalToFilePosition(position, files)
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
        val artworkUri = artworkPath?.let { Uri.fromFile(java.io.File(it)) }

        val artworkData = artworkPath?.let { path ->
            try { java.io.File(path).readBytes() } catch (e: Exception) { null }
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
