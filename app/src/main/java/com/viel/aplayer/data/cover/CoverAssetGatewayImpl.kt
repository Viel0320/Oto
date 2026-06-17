package com.viel.aplayer.data.cover

import com.viel.aplayer.data.dao.BookDao
import com.viel.aplayer.data.cover.CoverAssetGateway
import com.viel.aplayer.media.parser.CoverExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Cover Asset Service (Dedicated custom cover persistence implementation)
 *
 * Handles only user-supplied cover file replacement and database path updates, keeping metadata recovery
 * and subtitle parsing in their own services.
 */
class CoverAssetGatewayImpl(
    private val bookDao: BookDao,
    private val coverExtractor: CoverExtractor
) : CoverAssetGateway {
    override suspend fun saveCustomCover(bookId: String, coverUri: String) = withContext(Dispatchers.IO) {
        val book = bookDao.getBookById(bookId) ?: return@withContext
        // Custom Cover Extraction From URI (Crop, scale and normalize the selected artwork using its URI)
        // Resolves the image source using the background-safe extractor and avoids temporary file copies.
        val normalizedUri = if (!coverUri.startsWith("content://") && !coverUri.startsWith("file://")) {
            // Fallback scheme routing (Prepend file scheme for raw absolute paths to ensure provider decoding compatibility)
            "file://$coverUri"
        } else {
            coverUri
        }
        val result = coverExtractor.saveCustomCoverFromUri(bookId, normalizedUri)
        if (result.originalPath != null) {
            // Legacy Cover Cleanup (Remove stale full-size artwork after a successful replacement)
            // Deleting only after the new cover exists prevents losing artwork when extraction fails.
            book.coverPath?.let { oldPath ->
                val oldFile = File(oldPath)
                if (oldFile.exists()) {
                    oldFile.delete()
                }
            }
            // Legacy Thumbnail Cleanup (Remove stale thumbnail artwork after a successful replacement)
            // Thumbnail paths are stored separately, so they need their own cleanup pass.
            book.thumbnailPath?.let { oldPath ->
                val oldFile = File(oldPath)
                if (oldFile.exists()) {
                    oldFile.delete()
                }
            }

            // Cover Path Persistence (Persist the new artwork coordinates in Room)
            // Updating lastScannedAt keeps cover consumers aware that the visible artwork changed.
            bookDao.updateCoverPaths(
                id = bookId,
                coverPath = result.originalPath,
                thumbnailPath = result.thumbnailPath,
                lastScannedAt = System.currentTimeMillis()
            )
        }
    }
}
