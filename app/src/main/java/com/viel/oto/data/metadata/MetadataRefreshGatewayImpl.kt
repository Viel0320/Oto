package com.viel.oto.data.metadata

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.room.withTransaction
import com.viel.oto.data.cover.CoverRecoveryGateway
import com.viel.oto.data.dao.BookDao
import com.viel.oto.data.dao.ChapterDao
import com.viel.oto.data.db.AppDatabase
import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.logger.SecureLog
import com.viel.oto.media.parser.MetadataResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Dedicated audio tag and chapter rescan implementation.
 *
 * Owns the physical metadata recovery workflow, leaving cover asset replacement and subtitle loading
 * behind separate interfaces.
 */
@OptIn(UnstableApi::class)
class MetadataRefreshGatewayImpl (
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val coverRecoveryGateway: CoverRecoveryGateway,
    private val metadataResolver: MetadataResolver,
    private val database: AppDatabase
) : MetadataRefreshGateway {
    override suspend fun forceRegenerateCoverAndMetadata(bookId: String) = withContext(Dispatchers.IO) {
        try {
            val book = bookDao.getBookById(bookId) ?: return@withContext
            val files = bookDao.getFilesForBookList(bookId)
            if (files.isEmpty()) return@withContext

            val primaryFile = files.firstOrNull { it.status == AudiobookSchema.FileStatus.READY } ?: files.first()
            val metadata = metadataResolver.extract(primaryFile)

            database.withTransaction {
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
                    year = metadata.year,
                    series = metadata.album.trim().ifBlank { metadata.title.trim() }.ifBlank { book.series }
                )

                if (metadata.chapters.isNotEmpty()) {
                    chapterDao.deleteChaptersForBook(bookId)
                    val chaptersWithBookId = metadata.chapters.map { it.copy(bookId = bookId) }
                    chapterDao.insertChapters(chaptersWithBookId)
                }
            }

            coverRecoveryGateway.forceRegenerate(bookId)
        } catch (e: Exception) {
            SecureLog.error("MetadataRefreshGatewayImpl", "物理强制重建有声书 $bookId 的封面与元数据发生异常", e)
        }
    }
}
