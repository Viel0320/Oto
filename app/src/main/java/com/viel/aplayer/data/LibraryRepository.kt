package com.viel.aplayer.data

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
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
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import com.viel.aplayer.ui.components.SubtitleLine
import com.viel.aplayer.util.parser.SubtitleParser
import com.viel.aplayer.util.parser.AudiobookParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import androidx.core.graphics.scale

/**
 * Repository that wraps Room database and handles cover art caching.
 */
@OptIn(UnstableApi::class)
class LibraryRepository private constructor(context: Context) {
    @SuppressLint("StaticFieldLeak")
    private val context = context.applicationContext
    private val database = AppDatabase.getInstance(this.context)
    private val dao = database.audiobookDao()
    private val chapterDao = database.chapterDao()
    private val bookmarkDao = database.bookmarkDao()
    private val historyDao = database.searchHistoryDao()
    private val coversDir = File(this.context.filesDir, "covers").also { it.mkdirs() }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs = this.context.getSharedPreferences("library_prefs", Context.MODE_PRIVATE)

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
        prefs.edit { putString(KEY_LIBRARY_URI, uri.toString()) }
    }

    /** Get the root library directory. */
    fun getLibraryRoot(): Uri? {
        return prefs.getString(KEY_LIBRARY_URI, null)?.toUri()
    }

    fun setHomeFilter(filter: String) {
        prefs.edit { putString(KEY_HOME_FILTER, filter) }
    }

    fun getHomeFilter(): String {
        return prefs.getString(KEY_HOME_FILTER, "NotStarted") ?: "NotStarted"
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
     * 使用挂起函数顺序执行扫描，避免并发过高导致系统资源（如 Codec 实例）耗尽。
     */
    fun syncLibrary() {
        val rootUri = getLibraryRoot() ?: return
        scope.launch {
            val rootDoc = DocumentFile.fromTreeUri(context, rootUri) ?: return@launch
            val existingUris = dao.getAllUris().toSet()
            scanDirectory(rootDoc, existingUris)
            
            // TODO: 实现手动清理逻辑。
            // 扫描并识别出已不存在的文件（existingUris - scannedUris），但暂时不自动删除，
            // 留待将来在设置或库管理界面由用户手动确认后触发清理。
        }
    }

    /** 顺序扫描目录，减少 CPU 和内存瞬间冲击。 */
    private suspend fun scanDirectory(directory: DocumentFile, existingUris: Set<String>) {
        directory.listFiles().forEach { file ->
            if (file.isDirectory) {
                scanDirectory(file, existingUris)
            } else if (isAudioFile(file.name ?: "")) {
                val uriStr = file.uri.toString()
                if (!existingUris.contains(uriStr)) {
                    addAudiobook(file.uri, directory, file.name)
                }
            }
        }
    }

    private fun isAudioFile(fileName: String): Boolean {
        val extensions = listOf(".mp3", ".m4b", ".m4a", ".aac", ".flac", ".wav", ".ogg")
        return extensions.any { fileName.endsWith(it, ignoreCase = true) }
    }
    
    /** Observable list of all audiobooks, sorted by last played. */
    val audiobooks: Flow<List<AudiobookEntity>> = dao.getAllAudiobooks()

    /** Search audiobooks by title, author, or narrator. */
    fun searchAudiobooks(query: String): Flow<List<AudiobookEntity>> = dao.searchAudiobooks(query)

    fun filterByYear(year: String): Flow<List<AudiobookEntity>> = dao.filterByYear(year)
    
    fun filterByAuthor(author: String): Flow<List<AudiobookEntity>> = dao.filterByAuthor(author)
    
    fun filterByAuthorLimited(author: String, excludeUri: String, limit: Int): Flow<List<AudiobookEntity>> = 
        dao.filterByAuthorLimited(author, excludeUri, limit)
    
    fun filterByNarrator(narrator: String): Flow<List<AudiobookEntity>> = dao.filterByNarrator(narrator)

    fun filterByNarratorLimited(narrator: String, excludeUri: String, limit: Int): Flow<List<AudiobookEntity>> = 
        dao.filterByNarratorLimited(narrator, excludeUri, limit)

    fun getRecentlyAdded(limit: Int): Flow<List<AudiobookEntity>> = dao.getRecentlyAdded(limit)

    fun getRecentlyAddedExclusive(currentUri: String, authors: List<String>, narrators: List<String>, limit: Int): Flow<List<AudiobookEntity>> = 
        dao.getRecentlyAddedExclusive(currentUri, authors, narrators, limit)

    /**
     * 顺序添加有声书，并整合元数据提取。
     */
    @SuppressLint("CheckResult")
    @Suppress("DEPRECATION")
    suspend fun addAudiobook(uri: Uri, parentDir: DocumentFile? = null, fileName: String? = null) {
        withContext(Dispatchers.IO) {
            Log.d("LibraryRepository", "Adding audiobook: $uri (Name: $fileName)")
            // 复用单个 retriever 完成所有基本信息的提取，避免多次 open/release。
            val retriever = MediaMetadataRetriever()
            var title: String
            var author: String
            var narrator: String
            var description = ""
            var duration: Long
            var year = ""
            var fileSize = 0L
            var finalChapters = emptyList<ChapterEntity>()
            
            try {
                // ... (existing code for file size, duration, etc.) ...
                // Extract file size
                if (uri.scheme == "content") {
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (cursor.moveToFirst()) {
                            fileSize = cursor.getLong(sizeIndex)
                        }
                    }
                } else if (uri.scheme == "file") {
                    fileSize = File(uri.path ?: "").length()
                }

                retriever.setDataSource(context, uri)
                val rawTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                
                // Extract year/date
                val rawYear = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR) 
                    ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
                if (!rawYear.isNullOrBlank()) {
                    year = Regex("\\d{4}").find(rawYear)?.value ?: rawYear
                }

                // Extract duration
                val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                duration = durationStr?.toLongOrNull() ?: 0L

                // Title/Author extraction
                title = if (!rawTitle.isNullOrBlank() && !rawTitle.contains("/")) {
                    rawTitle
                } else {
                    uri.lastPathSegment?.substringAfterLast("/")?.substringBeforeLast(".") ?: "Unknown Title"
                }

                author = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "Unknown Author"
                narrator = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER) ?: "Unknown Narrator"

                // 使用 Media3 MetadataRetriever 一次性提取评论（描述）和章节，提升效率并减少资源竞争。
                try {
                    val mediaItem = MediaItem.Builder()
                        .setUri(uri)
                        .setMimeType(if (uri.toString().endsWith(".m4b", ignoreCase = true)) "audio/mp4" else null)
                        .build()
                    
                    val extractorsFactory = DefaultExtractorsFactory()
                        .setMp4ExtractorFlags(androidx.media3.extractor.mp4.Mp4Extractor.FLAG_READ_SEF_DATA)
                    val mediaSourceFactory = DefaultMediaSourceFactory(context, extractorsFactory)

                    val trackGroups = androidx.media3.exoplayer.MetadataRetriever.retrieveMetadata(mediaSourceFactory, mediaItem).get()
                    
                    val metadataEntries = mutableListOf<androidx.media3.common.Metadata.Entry>()
                    for (i in 0 until trackGroups.length) {
                        val group = trackGroups[i]
                        for (j in 0 until group.length) {
                            val format = group.getFormat(j)
                            val metadata = format.metadata ?: continue
                            for (k in 0 until metadata.length()) {
                                val entry = metadata.get(k)
                                metadataEntries.add(entry)
                                if (entry is CommentFrame && description.isEmpty()) {
                                    description = entry.text
                                }
                            }
                        }
                    }
                    
                    // Extract chapters
                    val chapters = AudiobookParser.extractChaptersFromMetadata(metadataEntries, uri.toString())
                    finalChapters = if (chapters.isEmpty()) {
                        AudiobookParser.extractChaptersLowLevel(context, uri)
                    } else {
                        chapters
                    }.asSequence().distinctBy { it.startPosition }.sortedBy { it.startPosition }.toList()

                } catch (e: Exception) {
                    Log.e("LibraryRepository", "Media3 metadata extraction failed", e)
                }

                // Extract and save cover art using the SAME retriever
                val (coverPath, thumbnailPath) = extractAndSaveCoverWithRetriever(retriever, uri)

                val entity = AudiobookEntity(
                    uri = uri.toString(),
                    title = title,
                    author = author,
                    narrator = narrator,
                    description = description,
                    duration = duration,
                    year = year,
                    fileSize = fileSize,
                    subtitlePath = findSubtitleFile(uri, parentDir, fileName),
                    coverPath = coverPath,
                    thumbnailPath = thumbnailPath,
                    addedAt = System.currentTimeMillis()
                )
                Log.d("LibraryRepository", "Inserting entity: ${entity.title}, Subtitle: ${entity.subtitlePath}")
                dao.insert(entity)

                // Save chapters AFTER the book entity is inserted to satisfy Foreign Key constraints
                if (finalChapters.isNotEmpty()) {
                    try {
                        chapterDao.deleteChaptersForBook(uri.toString())
                        chapterDao.insertChapters(finalChapters)
                    } catch (e: Exception) {
                        Log.e("LibraryRepository", "Failed to insert chapters for ${entity.title}", e)
                    }
                }

            } catch (e: Exception) {
                Log.e("LibraryRepository", "Error adding audiobook", e)
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }
        }
    }
    
    /** Update playback position in milliseconds. */
    fun updateProgress(uri: String, position: Long) {
        scope.launch {
            Log.d("LibraryRepository", "Updating progress for $uri: $position")
            dao.updateProgress(uri, position, System.currentTimeMillis())
        }
    }

    /**
     * Get the most recently played audiobook.
     */
    suspend fun getMostRecentAudiobook(): AudiobookEntity? = dao.getMostRecent()

    /**
     * Get chapters for an audiobook.
     */
    fun getChapters(uri: String): Flow<List<ChapterEntity>> = chapterDao.getChaptersForBook(uri)

    /**
     * Get bookmarks for an audiobook.
     */
    fun getBookmarks(uri: String): Flow<List<BookmarkEntity>> = bookmarkDao.getBookmarksForBook(uri)

    /**
     * Add a bookmark.
     */
    suspend fun addBookmark(uri: String, position: Long, title: String) {
        bookmarkDao.insert(BookmarkEntity(bookUri = uri, position = position, title = title))
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
     * Load and parse subtitles for an audiobook.
     */
    suspend fun loadSubtitles(bookUri: String): List<SubtitleLine> {
        val entity = dao.getByUri(bookUri) ?: run {
            Log.d("LibraryRepository", "loadSubtitles: Entity not found for $bookUri")
            return emptyList()
        }
        val subtitlePath = entity.subtitlePath ?: run {
            Log.d("LibraryRepository", "loadSubtitles: subtitlePath is null for $bookUri")
            return emptyList()
        }
        
        Log.d("LibraryRepository", "loadSubtitles: Path is $subtitlePath")
        return withContext(Dispatchers.IO) {
            try {
                val uri = subtitlePath.toUri()
                val extension = subtitlePath.substringAfterLast(".").lowercase()
                
                if (uri.scheme == "content") {
                    context.contentResolver.openInputStream(uri)?.use { 
                        SubtitleParser.parse(it, extension)
                    } ?: emptyList()
                } else {
                    val file = File(subtitlePath)
                    if (file.exists()) {
                        file.inputStream().use {
                            SubtitleParser.parse(it, extension)
                        }
                    } else emptyList()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }
    }

    /**
     * Save chapters to database.
     */
    fun saveChapters(uri: String, chapters: List<ChapterEntity>) {
        scope.launch {
            // 加固：检查书籍是否存在，避免 observePlaybackManager 触发时书籍尚未入库导致的外键约束错误
            if (dao.getByUri(uri) != null) {
                chapterDao.deleteChaptersForBook(uri)
                chapterDao.insertChapters(chapters)
            } else {
                Log.w("LibraryRepository", "Skip saveChapters: Book entity not found yet for $uri")
            }
        }
    }

    /**
     * Update title/author/narrator/description from ExoPlayer's parsed metadata.
     */
    fun updateMetadata(uri: String, title: String?, author: String?, narrator: String?, description: String?, duration: Long) {
        scope.launch {
            val existing = dao.getByUri(uri) ?: return@launch
            val newTitle = if (!title.isNullOrBlank()) title else existing.title
            val newAuthor = if (!author.isNullOrBlank()) author else existing.author
            val newNarrator = if (!narrator.isNullOrBlank()) narrator else existing.narrator
            val newDescription = if (!description.isNullOrBlank()) description else existing.description
            val newDuration = if (duration > 0) duration else existing.duration
            
            if (newTitle != existing.title || newAuthor != existing.author || 
                newNarrator != existing.narrator || newDescription != existing.description ||
                newDuration != existing.duration) {
                dao.updateMetadata(uri, newTitle, newAuthor, newNarrator, newDescription, newDuration)
            }
        }
    }
    
    /**
     * Get an audiobook by URI.
     */
    suspend fun getByUri(uri: String): AudiobookEntity? {
        return dao.getByUri(uri)
    }

    fun getByUriFlow(uri: String): Flow<AudiobookEntity?> {
        return dao.getByUriFlow(uri)
    }

    /**
     * Cache the dominant color of the book cover.
     */
    fun updateBackgroundColor(uri: String, color: Int) {
        scope.launch {
            dao.updateBackgroundColor(uri, color)
        }
    }

    /**
     * Extract embedded cover art from the audio file using an existing retriever.
     * Saves the original image and a 300px thumbnail.
     * Returns Pair(originalPath, thumbnailPath)
     */
    private fun extractAndSaveCoverWithRetriever(retriever: MediaMetadataRetriever, uri: Uri): Pair<String?, String?> {
        return try {
            val artBytes = retriever.embeddedPicture

            if (artBytes != null) {
                // 1. Save Original
                val originalFile = File(coversDir, "${uri.toString().hashCode()}_orig.jpg")
                FileOutputStream(originalFile).use { it.write(artBytes) }
                val originalPath = originalFile.absolutePath

                // 2. Create and Save Thumbnail (300px)
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size, options)

                val maxSize = 300
                options.inSampleSize = ImageProcessor.calculateInSampleSize(options, maxSize, maxSize)
                options.inJustDecodeBounds = false

                val bitmap = BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size, options)
                val thumbnailPath = if (bitmap != null) {
                    val scaledBitmap = if (bitmap.width > maxSize || bitmap.height > maxSize) {
                        ImageProcessor.scaleBitmap(bitmap, maxSize)
                    } else {
                        bitmap
                    }

                    val thumbFile = File(coversDir, "${uri.toString().hashCode()}_thumb.jpg")
                    ImageProcessor.saveBitmapToFile(scaledBitmap, thumbFile)

                    if (scaledBitmap != bitmap) {
                        scaledBitmap.recycle()
                    }
                    bitmap.recycle()
                    thumbFile.absolutePath
                } else null

                Pair(originalPath, thumbnailPath)
            } else {
                Pair(null, null)
            }
        } catch (e: Exception) {
            Log.e("LibraryRepository", "Error extracting cover", e)
            Pair(null, null)
        }
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
                    val subtitleExtensions = listOf("srt", "ass", "ssa")

                    // Optimized search: only list files if needed
                    val found = parentDir.listFiles().find { file ->
                        val fileName = file.name ?: ""
                        val nameWithoutExt = fileName.substringBeforeLast(".")
                        val ext = fileName.substringAfterLast(".").lowercase()
                        nameWithoutExt.equals(mediaName, ignoreCase = true) && subtitleExtensions.contains(ext)
                    }

                    Log.d("LibraryRepository", "Found subtitle: ${found?.uri}")
                    found?.uri?.toString()
                }
                "file" -> {
                    val file = File(mediaUri.path ?: "")
                    val parentDir = file.parentFile ?: return null
                    val baseName = file.nameWithoutExtension
                    val subtitleExtensions = listOf("srt", "ass", "ssa")

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
        private const val KEY_LIBRARY_URI = "library_root_uri"
        private const val KEY_HOME_FILTER = "home_filter"

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
