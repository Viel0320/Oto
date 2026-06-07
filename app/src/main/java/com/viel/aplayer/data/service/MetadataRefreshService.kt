package com.viel.aplayer.data.service

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.room.withTransaction
import com.viel.aplayer.data.dao.BookDao
import com.viel.aplayer.data.dao.ChapterDao
import com.viel.aplayer.data.db.AppDatabase
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.gateway.MetadataRefreshGateway
import com.viel.aplayer.media.parser.CoverRecoveryHelper
import com.viel.aplayer.media.parser.MetadataResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Metadata Refresh Service (Dedicated audio tag and chapter rescan implementation)
 *
 * Owns the physical metadata recovery workflow, leaving cover asset replacement and subtitle loading
 * behind separate interfaces.
 */
@OptIn(UnstableApi::class)
class MetadataRefreshService (
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val coverRecoveryHelper: CoverRecoveryHelper,
    private val metadataResolver: MetadataResolver,
    private val database: AppDatabase
) : MetadataRefreshGateway {
    override suspend fun forceRegenerateCoverAndMetadata(bookId: String) = withContext(Dispatchers.IO) {
        // Metadata Refresh Command (Re-read tags and chapters from the primary track)
        // This command is intentionally separate from custom cover persistence because it mutates book metadata and chapter schemas.
        try {
            val book = bookDao.getBookById(bookId) ?: return@withContext
            val files = bookDao.getFilesForBookList(bookId)
            if (files.isEmpty()) return@withContext

            // Primary Track Selection (Choose the first ready track when possible)
            // MetadataResolver needs one concrete audio file, so READY files get priority over stale or unchecked rows.
            val primaryFile = files.firstOrNull { it.status == AudiobookSchema.FileStatus.READY } ?: files.first()
            val metadata = metadataResolver.extract(primaryFile)

            // Atomic Metadata Synchronization (Write book fields and chapter rows as one Room transaction)
            // A failed chapter rewrite must not leave the book with partially refreshed details.
            database.withTransaction {
                bookDao.updateMetadata(
                    id = bookId,
                    title = metadata.album.trim().ifBlank { metadata.title.trim() }.ifBlank { book.title },
                    author = metadata.author,
                    narrator = metadata.narrator,
                    description = metadata.description,
                    duration = metadata.durationMs.takeIf { it > 0 } ?: book.totalDurationMs
                )
                // Series Metadata Refresh (Preserve the existing series fallback behavior)
                // Existing callers expect album/title fallback to keep the series field meaningful after tag rescans.
                bookDao.updateBookDetails(
                    id = bookId,
                    title = metadata.album.trim().ifBlank { metadata.title.trim() }.ifBlank { book.title },
                    author = metadata.author,
                    narrator = metadata.narrator,
                    description = metadata.description,
                    year = metadata.year,
                    series = metadata.album.trim().ifBlank { metadata.title.trim() }.ifBlank { book.series }
                )

                if (metadata.chapters.isNotEmpty()) {
                    // Parsed Chapter Replacement (Swap old chapter rows only when fresh parsed chapters exist)
                    // Empty parser output keeps existing generated or stored chapters untouched.
                    chapterDao.deleteChaptersForBook(bookId)
                    val chaptersWithBookId = metadata.chapters.map { it.copy(bookId = bookId) }
                    chapterDao.insertChapters(chaptersWithBookId)
                }
            }

            // Cover Recovery Refresh (Regenerate artwork after metadata extraction)
            // Some media files provide embedded artwork only through the metadata scan path.
            coverRecoveryHelper.forceRegenerateCover(bookId)
        } catch (e: Exception) {
            Log.e("MetadataRefreshService", "物理强制重建有声书 $bookId 的封面与元数据发生异常", e)
        }
    }
}
