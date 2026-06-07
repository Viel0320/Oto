package com.viel.aplayer.data.service

import com.viel.aplayer.data.dao.BookDao
import com.viel.aplayer.data.gateway.CoverAssetGateway
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
class CoverAssetService(
    private val bookDao: BookDao,
    private val coverExtractor: CoverExtractor
) : CoverAssetGateway {
    override suspend fun saveCustomCover(bookId: String, tempCoverPath: String) = withContext(Dispatchers.IO) {
        val book = bookDao.getBookById(bookId) ?: return@withContext
        // Custom Cover Extraction (Copy and normalize the temporary artwork into app-owned storage)
        // CoverExtractor owns image processing details while this service owns stale-file cleanup and Room path updates.
        val result = coverExtractor.saveCustomCover(bookId, tempCoverPath)
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
            // Temporary Cover Cleanup (Remove crop/edit scratch files after persistence)
            // The temporary input is no longer needed once the app-owned copy has been saved.
            val tempFile = File(tempCoverPath)
            if (tempFile.exists()) {
                tempFile.delete()
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
