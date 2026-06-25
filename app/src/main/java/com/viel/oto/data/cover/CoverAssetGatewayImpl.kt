package com.viel.oto.data.cover

import com.viel.oto.data.dao.BookDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Dedicated custom cover persistence implementation.
 *
 * Handles only user-supplied cover file replacement and database path updates, keeping metadata recovery
 * and subtitle parsing in their own services.
 */
class CoverAssetGatewayImpl(
    private val bookDao: BookDao,
    private val coverImageWriter: CoverImageWriter
) : CoverAssetGateway {
    override suspend fun saveCustomCover(bookId: String, coverUri: String) = withContext(Dispatchers.IO) {
        val book = bookDao.getBookById(bookId) ?: return@withContext
        val normalizedUri = if (!coverUri.startsWith("content://") && !coverUri.startsWith("file://")) {
            "file://$coverUri"
        } else {
            coverUri
        }
        val result = coverImageWriter.saveCustomCoverFromUri(bookId, normalizedUri)
        if (result.originalPath != null) {
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

            bookDao.updateCoverPaths(
                id = bookId,
                coverPath = result.originalPath,
                thumbnailPath = result.thumbnailPath,
                lastScannedAt = System.currentTimeMillis()
            )
        }
    }
}
