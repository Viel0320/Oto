package com.viel.aplayer.data.service

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.room.withTransaction
import com.viel.aplayer.data.dao.BookDao
import com.viel.aplayer.data.dao.ChapterDao
import com.viel.aplayer.data.db.AppDatabase
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.gateway.CoverGateway
import com.viel.aplayer.library.availability.AvailabilityChecker
import com.viel.aplayer.library.availability.DetailAvailabilityChecker
import com.viel.aplayer.media.parser.CoverExtractor
import com.viel.aplayer.media.parser.CoverRecoveryHelper
import com.viel.aplayer.media.parser.MetadataResolver
import com.viel.aplayer.media.subtitle.SubtitleFileResolver
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Cover and Tag Extraction Application Service (Implements CoverGateway)
 * 
 * Core Design Goals:
 * 1. Complete Repository Decoupling: Injected with BookDao, ChapterDao, and narrow VFS utilities in the M6e phase, avoiding direct delegation to the monolithic PhysicalFileResolver.
 * 2. Re-anchor Metadata Scans and Healing: Retains custom cover savings, background color caches, audio track rescans, and cascade updates to Chapter schemas.
 */
@OptIn(UnstableApi::class)
class CoverService(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val coverRecoveryHelper: CoverRecoveryHelper,
    private val coverExtractor: CoverExtractor,
    private val metadataResolver: MetadataResolver,
    private val subtitleResolver: SubtitleFileResolver,
    private val detailAvailabilityChecker: DetailAvailabilityChecker,
    private val availabilityChecker: AvailabilityChecker,
    // Global Database Reference (Room transaction support)
    // Inject global AppDatabase instance to run multi-DAO database write operations in atomic Room transactions.
    private val database: AppDatabase
) : CoverGateway, java.io.Closeable {

    // Private Exception Handler (Background thread crash boundary)
    // Intercepts background exceptions in CoverService to prevent uncaught scope terminations.
    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        Log.e("CoverService", "协程在 CoverService 运行中捕获到未处理异常", exception)
    }

    // Private Operations Coroutine Scope (Non-blocking execution boundary)
    // Dedicated scope on Dispatchers.IO for background color calculations and other async operations.
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)

    override suspend fun saveCustomCover(bookId: String, tempCoverPath: String) = withContext(Dispatchers.IO) {
        val book = bookDao.getBookById(bookId) ?: return@withContext
        // Copy and generate thumbnail: Delegates to coverExtractor to save custom files in the application sandbox.
        val result = coverExtractor.saveCustomCover(bookId, tempCoverPath)
        if (result.originalPath != null) {
            // Clear legacy cover: Deletes old files from disk to prevent storage leaks.
            book.coverPath?.let { oldPath ->
                val oldFile = File(oldPath)
                if (oldFile.exists()) {
                    oldFile.delete()
                }
            }
            // Clear legacy thumbnail: Deletes old cached thumbnails from disk.
            book.thumbnailPath?.let { oldPath ->
                val oldFile = File(oldPath)
                if (oldFile.exists()) {
                    oldFile.delete()
                }
            }
            // Clear temporary files: Purges temp paths used during image cropping and edits.
            val tempFile = File(tempCoverPath)
            if (tempFile.exists()) {
                tempFile.delete()
            }

            // Update database entity: Synchronously updates absolute paths and ARGB color keys in Book database tables.
            bookDao.updateCoverPaths(
                id = bookId,
                coverPath = result.originalPath,
                thumbnailPath = result.thumbnailPath,
                backgroundColorArgb = result.backgroundColor,
                lastScannedAt = System.currentTimeMillis()
            )
        }
    }

    override suspend fun forceRegenerateCoverAndMetadata(bookId: String) = withContext(Dispatchers.IO) {
        // Trigger Tag and Image Rescan (Ingestion recovery workflow)
        // Refreshes database schemas with latest tag extractions and self-heals cover files.
        try {
            val book = bookDao.getBookById(bookId) ?: return@withContext
            val files = bookDao.getFilesForBookList(bookId)
            if (files.isEmpty()) return@withContext

            // 1. Find primary audio file: Locates the first active, readable track.
            val primaryFile = files.firstOrNull { it.status == AudiobookSchema.FileStatus.READY } ?: files.first()
            
            // 2. Extract metadata: Resolves embedded track tags (duration, author, chapters) via metadataResolver.
            val metadata = metadataResolver.extract(primaryFile)

            // Transactional Atomic Synchronization (Data integrity guard)
            // Wraps metadata updates, detail updates, chapter purges, and chapter insertions within a single database transaction.
            // Guarantees atomic rollbacks if interrupted, preventing visual flickers or empty chapter arrays in the UI.
            database.withTransaction {
                // 3. Update Book attributes: Overwrites description, author, year, and duration.
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

                // 4. Update Chapters: Purges legacy chapter schemas and batch inserts newly parsed entries.
                if (metadata.chapters.isNotEmpty()) {
                    chapterDao.deleteChaptersForBook(bookId)
                    val chaptersWithBookId = metadata.chapters.map { it.copy(bookId = bookId) }
                    chapterDao.insertChapters(chaptersWithBookId)
                }
            }

            // 5. Force cover reload: Purges cache key states to force ImageLoader systems to render the recovered image.
            coverRecoveryHelper.forceRegenerateCover(bookId)
        } catch (e: Exception) {
            Log.e("CoverService", "物理强制重建有声书 $bookId 的封面与元数据发生异常", e)
        }
    }

    override fun updateBackgroundColor(id: String, color: Int) {
        // Asynchronous Color Updates (Non-blocking UI adjustments)
        // Offloads palette updates to a background task, keeping the details transition fluid.
        scope.launch {
            bookDao.updateBackgroundColor(id, color)
        }
    }

    override suspend fun checkDetailAvailability(bookId: String): Boolean = withContext(Dispatchers.IO) {
        // Verify Details Availability (Ingestion reachability checks)
        // Audits whether parent folders are accessible and cover assets are present.
        detailAvailabilityChecker.check(bookId).isAvailable
    }

    override suspend fun checkPrimaryAudioFileExists(bookId: String): Boolean = withContext(Dispatchers.IO) {
        // Query primary track: Fetches track sequences, then checks physical VFS file readiness.
        val primaryFile = bookDao.getFilesForBookList(bookId).firstOrNull() ?: return@withContext false
        // Verify physical reachability via availabilityChecker.
        availabilityChecker.checkBookFile(primaryFile).isAvailable
    }

    override suspend fun loadSubtitlesForBookFile(bookFileId: String): List<com.viel.aplayer.ui.player.components.SubtitleLine> = withContext(Dispatchers.IO) {
        // Resolve external subtitles: Locates and parses sidecar subtitle assets mapped to this file.
        subtitleResolver.loadSubtitlesForBookFile(bookFileId)
    }

    override fun close() {
        // Explicit Coroutine Scope Cancellation (Memory leak cleanup)
        // Cancels the private scope upon service teardown to ensure pending transactions release memory resources.
        scope.cancel()
    }
}
