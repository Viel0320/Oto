package com.viel.aplayer.data

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.util.image.ImageProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.metadata.id3.CommentFrame
import com.viel.aplayer.ui.components.SubtitleLine
import com.viel.aplayer.util.parser.SubtitleParser
import com.viel.aplayer.util.parser.AudiobookParser
import com.viel.aplayer.playback.BookPlaybackPlan
import com.viel.aplayer.playback.PositionMapper
import com.viel.aplayer.library.BookImporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.io.FileOutputStream
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
    private val historyDao = database.searchHistoryDao()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val scanner = com.viel.aplayer.library.LibraryScanner(this.context)
    private val importer = BookImporter(this.context)
    private val rootStore = com.viel.aplayer.library.LibraryRootStore(this.context)
    private val coverExtractor = com.viel.aplayer.media.CoverExtractor(this.context)
    private val metadataExtractor = com.viel.aplayer.media.MetadataExtractor(this.context)

    /** Search history flow. */
    val searchHistory: Flow<List<SearchHistoryEntity>> = historyDao.getRecentHistory()

    suspend fun addToHistory(query: String) {
        if (query.isNotBlank()) {
            historyDao.insert(SearchHistoryEntity(query.trim()))
        }
    }

    suspend fun deleteFromHistory(history: SearchHistoryEntity) {
        historyDao.delete(history)
    }

    suspend fun clearHistory() {
        historyDao.clearAll()
    }

    /** Set the root library directory. */
    fun setLibraryRoot(uri: Uri) {
        scope.launch {
            rootStore.addRoot(uri, "My Library")
        }
    }


    /** Check if a file exists at the given URI. */
    fun checkFileExists(uriString: String): Boolean {
        return try {
            val uri = uriString.toUri()
            if (uri.scheme == "content") {
                val doc = DocumentFile.fromSingleUri(context, uri)
                doc?.exists() == true
            } else {
                File(uri.path ?: "").exists()
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Sync the entire library: scan folder and add new files.
     */
    suspend fun syncLibrary(trigger: String = "USER") = withContext(Dispatchers.IO) {
        rootStore.migrateLegacyRoot()

        // 1. 扫描生成快照 (包含 ScanSession 开始)
        val snapshot = scanner.scanAllRoots(trigger)
        
        // 2. 协调差异
        val reconciler = com.viel.aplayer.library.LibraryReconciler(database)
        val result = reconciler.reconcile(snapshot)

        // 3. 执行导入与更新
        result.newClaims.forEach { claim ->
            try {
                if (claim.type == com.viel.aplayer.library.ClaimSourceType.SINGLE_AUDIO || 
                    claim.type == com.viel.aplayer.library.ClaimSourceType.M4B_EMBEDDED) {
                    addAudiobook(Uri.parse(claim.sourceUri))
                } else {
                    Log.i("LibraryRepository", "🚀 Triggering Manifest Import for: ${claim.displayName}")
                    importer.importManifestBook(claim)
                }
            } catch (e: Exception) {
                Log.e("LibraryRepository", "Import failed for ${claim.displayName}", e)
            }
        }

        // 3b. 执行更新逻辑
        result.updatedClaims.forEach { (claim, existingId) ->
            try {
                if (claim.type == com.viel.aplayer.library.ClaimSourceType.SINGLE_AUDIO || 
                    claim.type == com.viel.aplayer.library.ClaimSourceType.M4B_EMBEDDED) {
                    addAudiobook(Uri.parse(claim.sourceUri), existingBookId = existingId)
                } else {
                    Log.i("LibraryRepository", "🔄 Updating Manifest for: ${claim.displayName}")
                    importer.importManifestBook(claim, existingBookId = existingId)
                }
            } catch (e: Exception) {
                Log.e("LibraryRepository", "Update failed for ${claim.displayName}", e)
            }
        }

        // 4. 执行状态更新
        // 4a. 完全缺失
        result.unavailableBookIds.forEach { id ->
            Log.w("LibraryRepository", "Marking book as UNAVAILABLE: $id")
            bookDao.updateBookStatus(id, "UNAVAILABLE")
        }

        // 4b. 部分丢失
        if (result.partialBookIds.isNotEmpty()) {
            Log.i("LibraryRepository", "Updating ${result.partialBookIds.size} books to PARTIAL status.")
            result.partialBookIds.forEach { id ->
                Log.d("LibraryRepository", "Marking book as PARTIAL: $id")
                bookDao.updateBookStatus(id, "PARTIAL")
            }
        }

        // 5. 插入待处理操作（统一标记为 SKIPPED）
        if (result.pendingActions.isNotEmpty()) {
            val skippedActions = result.pendingActions.map { it.copy(status = "SKIPPED") }
            scanSessionDao.insertActions(skippedActions)
            Log.d("LibraryRepository", "Auto-skipped ${skippedActions.size} conflicts.")
        }

        // 6. 标记 Session 完成
        scanSessionDao.insertSession(ScanSessionEntity(
            id = snapshot.scanId,
            trigger = trigger,
            status = "COMPLETED",
            startedAt = snapshot.timestamp,
            completedAt = System.currentTimeMillis(),
            discoveredBookCount = result.discoveredCount,
            unavailableBookCount = result.unavailableBookIds.size,
            partialBookCount = result.partialBookIds.size,
            updatedBookCount = result.updatedClaims.size,
            recoveredBookCount = result.recoveredBookIds.size,
            pendingActionCount = result.pendingActions.size
        ))

        Log.i("LibraryRepository", "Sync finished. New: ${result.discoveredCount}, Missing: ${result.unavailableBookIds.size}, Partial: ${result.partialBookIds.size}, Updated: ${result.updatedClaims.size}, Recovered: ${result.recoveredBookIds.size}")
    }


    
    /** Observable list of all audiobooks, sorted by added date. */
    val audiobooks: Flow<List<BookWithProgress>> = bookDao.getAllBooksWithProgress()

    /** Search audiobooks by title, author, or narrator. */
    fun searchAudiobooks(query: String): Flow<List<BookWithProgress>> = bookDao.searchBooksWithProgress(query)

    fun filterByYear(year: String): Flow<List<BookWithProgress>> = bookDao.filterByYearWithProgress(year)
    
    fun filterByAuthor(author: String): Flow<List<BookWithProgress>> = bookDao.filterByAuthorWithProgress(author)
    
    fun filterByAuthorLimited(author: String, excludeId: String, limit: Int): Flow<List<BookWithProgress>> = 
        bookDao.filterByAuthorLimitedWithProgress(author, excludeId, limit)
    
    fun filterByNarrator(narrator: String): Flow<List<BookWithProgress>> = bookDao.filterByNarratorWithProgress(narrator)

    fun filterByNarratorLimited(narrator: String, excludeId: String, limit: Int): Flow<List<BookWithProgress>> = 
        bookDao.filterByNarratorLimitedWithProgress(narrator, excludeId, limit)

    fun getRecentlyAdded(limit: Int): Flow<List<BookWithProgress>> = bookDao.getRecentlyAddedWithProgress(limit)

    fun getRecentlyAddedExclusive(currentId: String, authors: List<String>, narrators: List<String>, limit: Int): Flow<List<BookWithProgress>> = 
        bookDao.getRecentlyAddedExclusiveWithProgress(currentId, authors, narrators, limit)

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
                // 1. 获取文件大小
                var fileSize = 0L
                if (uri.scheme == "content") {
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (cursor.moveToFirst()) fileSize = cursor.getLong(sizeIndex)
                    }
                } else if (uri.scheme == "file") {
                    fileSize = File(uri.path ?: "").length()
                }

                // 2. 使用 MetadataExtractor 提取音频元数据及章节
                val metadata = metadataExtractor.extract(uri)

                // 3. 提取封面图（为了保持流程一致，此处单独开启一次 Retriever 处理封面）
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, uri)
                val coverResult = coverExtractor.extractFromRetriever(retriever, bookId)
                retriever.release()

                val lastModified = if (uri.scheme == "file") File(uri.path ?: "").lastModified() else System.currentTimeMillis()

                // 4. 调用 Importer 执行事务化导入
                importer.importSingleAudio(
                    uri = uri.toString(),
                    title = metadata.title,
                    author = metadata.author,
                    narrator = metadata.narrator,
                    description = metadata.description,
                    year = metadata.year,
                    durationMs = metadata.durationMs,
                    fileSize = fileSize,
                    lastModified = lastModified,
                    coverPath = coverResult.originalPath,
                    thumbnailPath = coverResult.thumbnailPath,
                    backgroundColorArgb = coverResult.backgroundColor,
                    chapters = metadata.chapters,
                    existingBookId = existingBookId
                )

            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("LibraryRepository", "Error adding audiobook", e)
            }
        }
    }
    
    /** Update playback position in milliseconds. */
    fun updateProgress(bookId: String, position: Long) {
        scope.launch {
            val progress = bookDao.getProgressForBookSync(bookId)
            val files = bookDao.getFilesForBookList(bookId)
            
            if (progress != null && files.isNotEmpty()) {
                val (fileIndex, posInFile) = com.viel.aplayer.playback.PositionMapper.globalToFilePosition(position, files)
                val bookFileId = files.getOrNull(fileIndex)?.id
                
                bookDao.insertProgress(progress.copy(
                    globalPositionMs = position,
                    bookFileId = bookFileId,
                    currentFileIndex = fileIndex,
                    positionInFileMs = posInFile,
                    lastPlayedAt = System.currentTimeMillis()
                ))
            } else if (progress != null) {
                // 回退逻辑，仅更新全局位置
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

    /**
     * Get bookmarks for an audiobook.
     */
    fun getBookmarks(bookId: String): Flow<List<BookmarkEntity>> = bookmarkDao.getBookmarksForBook(bookId)

    /**
     * Add a bookmark.
     */
    suspend fun addBookmark(bookId: String, position: Long, title: String) {
        bookmarkDao.insert(BookmarkEntity(
            id = UUID.randomUUID().toString(),
            bookId = bookId,
            globalPositionMs = position,
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
     */
    suspend fun loadSubtitlesForUri(mediaUri: Uri): List<SubtitleLine> = withContext(Dispatchers.IO) {
        val subtitleUriString = findSubtitleFile(mediaUri) ?: return@withContext emptyList()
        val subtitleUri = subtitleUriString.toUri()
        val extension = subtitleUriString.substringAfterLast(".").lowercase()

        try {
            if (subtitleUri.scheme == "content") {
                context.contentResolver.openInputStream(subtitleUri)?.use { 
                    SubtitleParser.parse(it, extension)
                } ?: emptyList()
            } else {
                val file = File(subtitleUri.path ?: subtitleUriString)
                if (file.exists()) {
                    file.inputStream().use {
                        SubtitleParser.parse(it, extension)
                    }
                } else {
                    emptyList()
                }
            }
        } catch (e: Exception) {
            Log.e("LibraryRepository", "Error loading subtitles for $mediaUri", e)
            emptyList()
        }
    }

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
    
    suspend fun saveProgress(progress: BookProgressEntity) {
        bookDao.insertProgress(progress)
    }

    /**
     * 同步获取书籍的文件列表（用于内部转换）。
     */
    suspend fun getFilesForBookSync(bookId: String): List<BookFileEntity> = withContext(Dispatchers.IO) {
        bookDao.getFilesForBookList(bookId)
    }

    suspend fun getPlaybackPlan(bookId: String): BookPlaybackPlan? = withContext(Dispatchers.IO) {
        val book = bookDao.getBookById(bookId) ?: return@withContext null
        val files = bookDao.getFilesForBookList(bookId)
        val chapters = chapterDao.getChaptersForBookList(bookId)
        val progress = bookDao.getProgressForBookSync(bookId)
        
        if (files.isEmpty()) return@withContext null
        
        val artworkUri = book.thumbnailPath?.let { Uri.fromFile(java.io.File(it)) } 
            ?: book.coverPath?.let { Uri.parse(it) }
        
        val artworkData = book.thumbnailPath?.let { path ->
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
            bookDao.deleteBook(book)
        }
    }

    suspend fun getBookById(id: String): BookEntity? {
        return bookDao.getBookById(id)
    }

    fun observeBookById(id: String): Flow<BookEntity?> {
        return bookDao.observeBookById(id)
    }

    fun observeLatestScanSession(): Flow<ScanSessionEntity?> {
        return scanSessionDao.observeLatestCompletedSession()
    }

    /** Observe all library root directories. */
    fun observeLibraryRoots(): Flow<List<LibraryRootEntity>> {
        return libraryRootDao.getAllRoots()
    }


    /**
     * Search for a subtitle file (srt, ass, ssa) in the same directory as the media file.
     */
    private fun findSubtitleFile(mediaUri: Uri, providedParent: DocumentFile? = null, providedName: String? = null): String? {
        return try {
            when (mediaUri.scheme) {
                "content" -> {
                    // If we are in syncLibrary, providedParent is already the correct folder
                    val parentDir = providedParent ?: run {
                        val mediaFile = DocumentFile.fromSingleUri(context, mediaUri)
                        mediaFile?.parentFile
                    } ?: return null

                    val mediaName = (providedName ?: DocumentFile.fromSingleUri(context, mediaUri)?.name)
                        ?.substringBeforeLast(".") ?: return null

                    Log.d("LibraryRepository", "Searching subtitles in ${parentDir.uri} for $mediaName")
                    val subtitleExtensions = listOf("srt", "ass", "ssa", "vtt", "lrc")

                    // Optimized search: only list files if needed
                    val found = parentDir.listFiles().find { file ->
                        val fileName = file.name ?: ""
                        val nameWithoutExt = fileName.substringBeforeLast(".")
                        val ext = fileName.substringAfterLast(".").lowercase()
                        subtitleExtensions.contains(ext) && nameWithoutExt.equals(mediaName, ignoreCase = true)
                    }

                    Log.d("LibraryRepository", "Found subtitle: ${found?.uri}")
                    found?.uri?.toString()
                }
                "file" -> {
                    val file = File(mediaUri.path ?: "")
                    val parentDir = file.parentFile ?: return null
                    val baseName = file.nameWithoutExtension
                    val subtitleExtensions = listOf("srt", "ass", "ssa", "vtt", "lrc")

                    parentDir.listFiles { _, name ->
                        val ext = name.substringAfterLast(".").lowercase()
                        name.substringBeforeLast(".") == baseName && subtitleExtensions.contains(ext)
                    }?.firstOrNull()?.absolutePath
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e("LibraryRepository", "Error finding subtitle", e)
            null
        }
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
