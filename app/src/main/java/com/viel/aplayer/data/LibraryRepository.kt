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
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.util.image.ImageProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.metadata.id3.CommentFrame
import com.viel.aplayer.ui.components.SubtitleLine
import com.viel.aplayer.util.parser.SubtitleParser
import com.viel.aplayer.util.parser.AudiobookParser
import com.viel.aplayer.playback.BookPlaybackPlan
import com.viel.aplayer.playback.PlaybackSubtitle
import com.viel.aplayer.playback.PositionMapper
import com.viel.aplayer.library.BookImporter
import com.viel.aplayer.library.DetailAvailabilityChecker
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
import java.util.Locale
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
    private val importer = BookImporter(this.context)
    private val availabilityChecker = DetailAvailabilityChecker(this.context)
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

        // New rescan path: scanner builds inventory, orchestrator decides imports, pending remains PENDING.
        val type = if (trigger == AudiobookSchema.ScanTrigger.COLD_START || trigger == "COLD_START") {
            com.viel.aplayer.library.RescanType.COLD_START_LIGHT
        } else {
            com.viel.aplayer.library.RescanType.USER_GLOBAL
        }
        val session = com.viel.aplayer.library.RescanCoordinator(context).rescan(type)
        Log.i("LibraryRepository", "Sync finished. New: ${session.discoveredBookCount}, Pending: ${session.pendingActionCount}")
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
                        ?: com.viel.aplayer.media.CoverExtractor.CoverResult(null, null)
                }

                val lastModified = if (uri.scheme == "file") File(uri.path ?: "").lastModified() else System.currentTimeMillis()

                // Compatibility import delegates persistence to BookImporter and still skips initial progress.
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
    private fun com.viel.aplayer.media.CoverExtractor.CoverResult.hasImage(): Boolean =
        originalPath != null || thumbnailPath != null

    /** Update playback position in milliseconds. */
    fun updateProgress(bookId: String, position: Long) {
        scope.launch {
            val progress = bookDao.getProgressForBookSync(bookId)
            val files = bookDao.getFilesForBookList(bookId)
            
            if (files.isNotEmpty()) {
                val (fileIndex, posInFile) = com.viel.aplayer.playback.PositionMapper.globalToFilePosition(position, files)
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
     */
    suspend fun loadSubtitlesForUri(mediaUri: Uri): List<SubtitleLine> = withContext(Dispatchers.IO) {
        // MediaItem only gives a URI; map it back to BookFile so SAF subtitles can use rootId/relativePath.
        val scannedFile = bookDao.getBookFileByUri(mediaUri.toString())
        val attachment = scannedFile?.let { loadSubtitleAttachment(it) } ?: loadSubtitleAttachment(mediaUri)
        attachment?.lines ?: emptyList()
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
     * 同步获取书籍的文件列表，用于内部位置映射和播放计划转换。
     */
    suspend fun getFilesForBookSync(bookId: String): List<BookFileEntity> = withContext(Dispatchers.IO) {
        bookDao.getFilesForBookList(bookId)
    }

    // UI availability checks use the first AUDIO file because Book no longer owns a sourceUri.
    suspend fun getPrimaryAudioUri(bookId: String): String? = withContext(Dispatchers.IO) {
        bookDao.getFilesForBookList(bookId).firstOrNull()?.uri
    }

    // DetailScreen owns old BookFile reachability checks, outside the rescan flow.
    suspend fun checkDetailAvailability(bookId: String): Boolean = withContext(Dispatchers.IO) {
        availabilityChecker.check(bookId).isAvailable
    }

    suspend fun getPlaybackPlan(bookId: String): BookPlaybackPlan? = withContext(Dispatchers.IO) {
        val book = bookDao.getBookById(bookId) ?: return@withContext null
        val files = bookDao.getFilesForBookList(bookId)
        val chapters = chapterDao.getChaptersForBookList(bookId)
        val progress = bookDao.getProgressForBookSync(bookId)
        
        if (files.isEmpty()) return@withContext null
        
        val artworkPath = book.thumbnailPath ?: book.coverPath
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
            // 播放计划生成时先解析同目录同名字幕，播放器和字幕页共用同一份结果。
            subtitlesByFileId = files.mapNotNull { file ->
                loadSubtitleAttachment(file)?.let { subtitle -> file.id to subtitle }
            }.toMap(),
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

    // Small local carrier for bookmark anchor mapping results.
    private data class Quad(
        val bookFileId: String?,
        val fileOffsetMs: Long,
        val fingerprint: String?,
        val anchorStatus: String
    )

    /**
     * Search for a subtitle file (srt, ass, ssa) in the same directory as the media file.
     */
    private suspend fun loadSubtitleAttachment(file: BookFileEntity): PlaybackSubtitle? {
        val subtitle = findSubtitleFile(file) ?: return null
        return parseSubtitle(subtitle.uri, subtitle.extension, subtitle.displayName)
    }

    private fun loadSubtitleAttachment(mediaUri: Uri): PlaybackSubtitle? {
        val subtitle = findSubtitleFile(mediaUri) ?: return null
        return parseSubtitle(subtitle.uri, subtitle.extension, subtitle.displayName)
    }

    private fun parseSubtitle(uri: Uri, extension: String, displayName: String): PlaybackSubtitle? =
        try {
            val lines = if (uri.scheme == "content") {
                context.contentResolver.openInputStream(uri)?.use {
                    SubtitleParser.parse(it, extension)
                }.orEmpty()
            } else {
                val file = File(uri.path ?: uri.toString())
                if (file.exists()) {
                    file.inputStream().use { SubtitleParser.parse(it, extension) }
                } else {
                    emptyList()
                }
            }
            PlaybackSubtitle(
                uri = uri,
                mimeType = subtitleMimeType(extension),
                label = displayName.substringBeforeLast('.'),
                lines = lines
            )
        } catch (e: Exception) {
            Log.e("LibraryRepository", "Error loading subtitles for $uri", e)
            null
        }

    private suspend fun findSubtitleFile(file: BookFileEntity): SubtitleFileRef? {
        val root = libraryRootDao.getRootById(file.rootId)
        val rootDoc = root?.treeUri?.let { DocumentFile.fromTreeUri(context, Uri.parse(it)) }
        val parentPath = file.relativePath.substringBeforeLast('/', missingDelimiterValue = "")
        val audioName = file.relativePath.substringAfterLast('/').ifBlank { file.displayName }

        // SAF 单文件 URI 找父目录不稳定，优先用导入时保存的 rootId/relativePath 回到同目录。
        val rootBased = rootDoc
            ?.findRelativeDirectory(parentPath)
            ?.findSameBaseSubtitle(audioName)
        if (rootBased != null) return rootBased

        return findSubtitleFile(file.uri.toUri())
    }

    private fun findSubtitleFile(mediaUri: Uri, providedParent: DocumentFile? = null, providedName: String? = null): SubtitleFileRef? {
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

                    // Optimized search: only list files if needed
                    val found = parentDir.findSameBaseSubtitle(mediaName)

                    Log.d("LibraryRepository", "Found subtitle: ${found?.uri}")
                    found
                }
                "file" -> {
                    val file = File(mediaUri.path ?: "")
                    val parentDir = file.parentFile ?: return null
                    val baseName = file.nameWithoutExtension

                    parentDir.listFiles { _, name ->
                        val ext = name.substringAfterLast(".").lowercase(Locale.ROOT)
                        name.substringBeforeLast(".") == baseName && SUBTITLE_EXTENSIONS.contains(ext)
                    }?.firstOrNull()?.let { subtitle ->
                        SubtitleFileRef(
                            uri = Uri.fromFile(subtitle),
                            extension = subtitle.extension.lowercase(Locale.ROOT),
                            displayName = subtitle.name
                        )
                    }
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e("LibraryRepository", "Error finding subtitle", e)
            null
        }
    }

    private fun DocumentFile.findRelativeDirectory(relativeParentPath: String): DocumentFile? {
        if (relativeParentPath.isBlank()) return this
        // 逐级定位扫描记录里的相对父目录，确保 SAF 授权目录下的字幕可被找到。
        return relativeParentPath.split('/').fold(this as DocumentFile?) { current, segment ->
            current?.findFile(segment)?.takeIf { it.isDirectory }
        }
    }

    private fun DocumentFile.findSameBaseSubtitle(audioName: String): SubtitleFileRef? {
        val baseName = audioName.substringBeforeLast('.', missingDelimiterValue = audioName)
        return listFiles().firstNotNullOfOrNull { candidate ->
            val name = candidate.name ?: return@firstNotNullOfOrNull null
            val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)
            val sameBaseName = name.substringBeforeLast('.', missingDelimiterValue = name).equals(baseName, ignoreCase = true)
            if (candidate.isFile && sameBaseName && SUBTITLE_EXTENSIONS.contains(extension)) {
                SubtitleFileRef(candidate.uri, extension, name)
            } else {
                null
            }
        }
    }

    private fun subtitleMimeType(extension: String): String? =
        when (extension.lowercase(Locale.ROOT)) {
            "srt" -> MimeTypes.APPLICATION_SUBRIP
            "vtt" -> MimeTypes.TEXT_VTT
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            else -> null
        }

    private data class SubtitleFileRef(
        val uri: Uri,
        val extension: String,
        val displayName: String
    )
    
    companion object {
        @Volatile
        @SuppressLint("StaticFieldLeak")
        private var INSTANCE: LibraryRepository? = null

        fun getInstance(context: Context): LibraryRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LibraryRepository(context).also { INSTANCE = it }
            }
        }

        private val SUBTITLE_EXTENSIONS = setOf("srt", "ass", "ssa", "vtt", "lrc")
    }
}
