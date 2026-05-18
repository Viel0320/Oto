package com.viel.aplayer.library

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.viel.aplayer.data.*
import com.viel.aplayer.media.CoverExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*

/**
 * 负责书籍导入的核心组件。
 */
class BookImporter(private val context: Context) {
    private val database = AppDatabase.getInstance(context)
    private val bookDao = database.bookDao()
    private val chapterDao = database.chapterDao()
    private val coverExtractor = CoverExtractor(context)

    /**
     * 导入单文件书籍。
     */
    suspend fun importSingleAudio(
        uri: String,
        title: String,
        author: String,
        narrator: String,
        description: String,
        year: String,
        durationMs: Long,
        fileSize: Long,
        lastModified: Long,
        coverPath: String?,
        thumbnailPath: String?,
        backgroundColorArgb: Int?,
        chapters: List<ChapterEntity>,
        existingBookId: String? = null
    ): String = withContext(Dispatchers.IO) {
        val bookId = existingBookId ?: UUID.randomUUID().toString()
        val fileId = UUID.randomUUID().toString()
        val rootId = "default"

        val book = BookEntity(
            id = bookId,
            sourceType = "SINGLE_AUDIO",
            sourceUri = uri,
            title = title,
            author = author.trim(),
            narrator = narrator.trim(),
            description = description,
            year = year,
            totalDurationMs = durationMs,
            totalFileSize = fileSize,
            coverPath = coverPath,
            thumbnailPath = thumbnailPath,
            backgroundColorArgb = backgroundColorArgb,
            sourceLastModified = lastModified,
            sourceFileSize = fileSize,
            status = "READY"
        )

        val file = BookFileEntity(
            id = fileId,
            bookId = bookId,
            rootId = rootId,
            index = 0,
            uri = uri,
            documentId = "",
            relativePath = "",
            displayName = title,
            durationMs = durationMs,
            fileSize = fileSize,
            lastModified = lastModified,
            status = "READY"
        )

        val source = BookSourceEntity(
            id = UUID.randomUUID().toString(),
            bookId = bookId,
            rootId = rootId,
            type = "SINGLE_AUDIO",
            sourceUri = uri,
            sourceDocumentId = ""
        )

        val progress = BookProgressEntity(
            bookId = bookId,
            globalPositionMs = 0L,
            bookFileId = fileId,
            currentFileIndex = 0,
            positionInFileMs = 0L,
            anchorStatus = "OK"
        )

        bookDao.insertBook(book)
        bookDao.insertBookFiles(listOf(file))
        bookDao.insertBookSource(source)
        bookDao.insertProgress(progress)
        
        if (chapters.isNotEmpty()) {
            val fixedChapters = chapters.mapIndexed { index, ch ->
                val finalDuration = if (ch.durationMs <= 0) {
                    if (index < chapters.size - 1) {
                        chapters[index + 1].startPositionMs - ch.startPositionMs
                    } else {
                        durationMs - ch.startPositionMs
                    }
                } else ch.durationMs

                ch.copy(
                    bookId = bookId,
                    bookFileId = fileId,
                    durationMs = finalDuration.coerceAtLeast(0L)
                )
            }
            chapterDao.insertChapters(fixedChapters)
        }
        
        bookId
    }

    /**
     * 导入或更新 Manifest 聚合书籍 (CUE/M3U8)。
     */
    suspend fun importManifestBook(claim: ClaimSource, existingBookId: String? = null) = withContext(Dispatchers.IO) {
        val bookId = existingBookId ?: UUID.randomUUID().toString()
        val rootId = claim.rootId
        val fileEntities = mutableListOf<BookFileEntity>()
        val chapterEntities = mutableListOf<ChapterEntity>()

        var bookCoverPath: String? = null
        var bookThumbnailPath: String? = null
        var bookBgColor: Int? = null

        // 书籍全局元数据策略：
        // 1. 优先使用扫描器从 Manifest 提取的建议
        // 2. 兜底逻辑：从第一个音频文件中提取
        var bookTitle = claim.metadata?.title?.trim() ?: claim.displayName.substringBeforeLast(".")
        var bookAuthor = claim.metadata?.author?.trim() ?: ""
        var bookNarrator = claim.metadata?.narrator?.trim() ?: ""
        var bookYear = claim.metadata?.year ?: ""
        val bookDesc = claim.metadata?.description ?: ""

        Log.d("BookImporter", "Importing manifest book: ${claim.displayName} with ${claim.referencedFileUris.size} files")

        // 1. 优先使用扫描阶段发现的封面图
        claim.coverUri?.let { uriStr ->
            try {
                val coverResult = coverExtractor.processExternalImage(Uri.parse(uriStr))
                bookCoverPath = coverResult.originalPath
                bookThumbnailPath = coverResult.thumbnailPath
                bookBgColor = coverResult.backgroundColor
            } catch (e: Exception) {
                Log.w("BookImporter", "Failed to process pre-scanned cover: $uriStr")
            }
        }

        // 2. 遍历引用的文件并提取时长
        val uriToDuration = mutableMapOf<String, Long>()
        val uriToFileId = mutableMapOf<String, String>()

        claim.referencedFileUris.forEachIndexed { index, fileUriStr ->
            val uri = Uri.parse(fileUriStr)
            val retriever = MediaMetadataRetriever()
            var duration = 0L
            var fileSize = 0L
            
            try {
                // 优先使用 Manifest 中提供的时长
                duration = claim.fileDurations[fileUriStr] ?: 0L
                
                if (duration <= 0) {
                    retriever.setDataSource(context, uri)
                    duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                }
                
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        fileSize = cursor.getLong(cursor.getColumnIndexOrThrow(android.provider.OpenableColumns.SIZE))
                    }
                }

                // 如果是第一个文件且 Manifest 没提供完整元数据，则补充提取
                if (index == 0) {
                    if (bookAuthor.isBlank()) {
                        val metaAuthor = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                        if (!metaAuthor.isNullOrBlank()) bookAuthor = metaAuthor.trim()
                    }
                    if (bookNarrator.isBlank()) {
                        val metaNarrator = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER)
                        if (!metaNarrator.isNullOrBlank()) bookNarrator = metaNarrator.trim()
                    }
                    if (bookYear.isBlank()) {
                        val metaYear = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
                        if (!metaYear.isNullOrBlank()) bookYear = metaYear
                    }

                    // 如果还没找到封面，提取内嵌封面
                    if (bookCoverPath == null) {
                        val embeddedResult = coverExtractor.extractFromRetriever(retriever, bookId)
                        bookCoverPath = embeddedResult.originalPath
                        bookThumbnailPath = embeddedResult.thumbnailPath
                        bookBgColor = embeddedResult.backgroundColor
                    }
                }
            } catch (e: Exception) {
                Log.e("BookImporter", "Error reading file info: $fileUriStr", e)
            } finally {
                retriever.release()
            }

            val fileId = UUID.randomUUID().toString()
            val fileName = uri.lastPathSegment ?: "Part ${index + 1}"
            
            val file = BookFileEntity(
                id = fileId,
                bookId = bookId,
                rootId = rootId,
                index = index,
                uri = fileUriStr,
                documentId = "",
                relativePath = "",
                displayName = fileName,
                durationMs = duration,
                fileSize = fileSize,
                lastModified = System.currentTimeMillis(),
                status = "READY"
            )
            fileEntities.add(file)
            uriToDuration[fileUriStr] = duration
            uriToFileId[fileUriStr] = fileId
        }

        // 3. 章节处理逻辑
        if (claim.chapters.isNotEmpty()) {
            // 情况 A: Manifest 已经提供了明确的章节定义 (例如 CUE)
            var currentGlobalStart = 0L
            val fileStarts = mutableMapOf<String, Long>()
            
            // 计算每个文件在全局时间轴的起点
            fileEntities.forEach { file ->
                fileStarts[file.uri] = currentGlobalStart
                currentGlobalStart += file.durationMs
            }

            claim.chapters.forEachIndexed { index, ch ->
                val fileStart = fileStarts[ch.fileUri] ?: 0L
                val fileDuration = uriToDuration[ch.fileUri] ?: 0L
                
                // 修正章节时长（如果它是文件的最后一个章节，需要用到文件时长）
                val finalDuration = if (ch.durationMs <= 0) {
                    val nextInSameFile = claim.chapters.getOrNull(index + 1)?.takeIf { it.fileUri == ch.fileUri }
                    if (nextInSameFile != null) nextInSameFile.fileOffsetMs - ch.fileOffsetMs
                    else fileDuration - ch.fileOffsetMs
                } else ch.durationMs

                chapterEntities.add(ChapterEntity(
                    id = UUID.randomUUID().toString(),
                    bookId = bookId,
                    bookFileId = uriToFileId[ch.fileUri] ?: "",
                    index = index,
                    title = ch.title,
                    startPositionMs = fileStart + ch.fileOffsetMs,
                    durationMs = finalDuration,
                    fileOffsetMs = ch.fileOffsetMs,
                    source = claim.type.name
                ))
            }
        } else {
            // 情况 B: Manifest 仅列出了文件 (例如 M3U8)，每个文件作为一个章节
            var currentPos = 0L
            fileEntities.forEachIndexed { index, file ->
                val fileName = Uri.parse(file.uri).lastPathSegment ?: "Part ${index + 1}"
                var chapterTitle = claim.fileTitles[file.uri] ?: fileName.substringBeforeLast(".")
                
                // 章节名净化逻辑
                val cleanedAuthor = bookAuthor.trim()
                if (cleanedAuthor.isNotEmpty()) {
                    val separators = listOf(" - ", " — ", ": ", " | ")
                    for (sep in separators) {
                        if (chapterTitle.startsWith("$cleanedAuthor$sep", ignoreCase = true)) {
                            chapterTitle = chapterTitle.substring(cleanedAuthor.length + sep.length).trim()
                            break
                        }
                    }
                }

                chapterEntities.add(ChapterEntity(
                    id = UUID.randomUUID().toString(),
                    bookId = bookId,
                    bookFileId = file.id,
                    index = index,
                    title = chapterTitle,
                    startPositionMs = currentPos,
                    durationMs = file.durationMs,
                    fileOffsetMs = 0L,
                    source = claim.type.name
                ))
                currentPos += file.durationMs
            }
        }

        val totalDuration = fileEntities.sumOf { it.durationMs }

        // 4. 确定初始状态：如果物理文件不全，直接标记为 PARTIAL
        val initialStatus = when {
            fileEntities.isEmpty() -> "ERROR"
            claim.missingFileCount > 0 -> "PARTIAL"
            else -> "READY"
        }

        // 5. 创建 Book 实体
        val book = BookEntity(
            id = bookId,
            sourceType = claim.type.name,
            sourceUri = claim.sourceUri,
            title = bookTitle,
            author = bookAuthor,
            narrator = bookNarrator,
            description = bookDesc,
            year = bookYear,
            totalDurationMs = totalDuration,
            totalFileSize = fileEntities.sumOf { it.fileSize },
            coverPath = bookCoverPath,
            thumbnailPath = bookThumbnailPath,
            backgroundColorArgb = bookBgColor,
            sourceLastModified = claim.sourceLastModified,
            sourceFileSize = claim.sourceFileSize,
            addedAt = if (existingBookId != null) (bookDao.getBookById(existingBookId)?.addedAt ?: System.currentTimeMillis()) else System.currentTimeMillis(),
            status = initialStatus
        )

        // 6. 写入数据库
        if (existingBookId != null) {
            // 清理旧的文件和章节，准备覆盖更新
            bookDao.deleteFilesForBook(existingBookId)
            chapterDao.deleteChaptersForBook(existingBookId)
        }

        bookDao.insertBook(book)
        bookDao.insertBookFiles(fileEntities)
        bookDao.insertBookSource(BookSourceEntity(
            id = UUID.randomUUID().toString(),
            bookId = bookId,
            rootId = rootId,
            type = claim.type.name,
            sourceUri = claim.sourceUri,
            sourceDocumentId = ""
        ))
        
        if (chapterEntities.isNotEmpty()) {
            chapterDao.insertChapters(chapterEntities)
        }

        // 初始进度
        bookDao.insertProgress(BookProgressEntity(
            bookId = bookId,
            globalPositionMs = 0L,
            bookFileId = fileEntities.firstOrNull()?.id,
            currentFileIndex = 0,
            positionInFileMs = 0L,
            anchorStatus = "OK"
        ))

        Log.d("BookImporter", "Manifest book imported: $bookId")
    }
}
