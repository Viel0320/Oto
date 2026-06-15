package com.viel.aplayer.application.usecase

import com.viel.aplayer.application.download.ManualDownloadCleanupGateway
import com.viel.aplayer.application.playback.PlaybackStopper
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.data.gateway.BookCatalogGateway
import com.viel.aplayer.data.gateway.BookRootInventoryGateway
import com.viel.aplayer.data.gateway.LibraryResourceCleanupGateway
import com.viel.aplayer.data.gateway.LibraryRootGateway
import com.viel.aplayer.logger.LibraryWorkflowLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Library Root Management Use Case (Coordinates root-scoped destructive workflows)
 * Owns the shared ordering for root deletion and ABS library switching: stop affected playback, clear derived root
 * resources, clear book-level manual downloads, and only then allow the data layer to cascade old rows.
 */
class LibraryRootManagementUseCase(
    private val playbackStopper: PlaybackStopper,
    private val bookCatalogGateway: BookCatalogGateway,
    private val bookRootInventoryGateway: BookRootInventoryGateway,
    private val libraryRootGateway: LibraryRootGateway,
    private val manualDownloadCleanupGateway: ManualDownloadCleanupGateway,
    private val libraryResourceCleanupGateway: LibraryResourceCleanupGateway
) {
    /**
     * Delete Library Root (Clear root resources before cascade deletion)
     * Rehydrates the persisted root from its id so presentation callers never pass Room entities across the UI boundary.
     */
    suspend fun deleteLibraryRoot(rootId: String): Boolean = withContext(Dispatchers.IO) {
        val root = findRoot(rootId) ?: return@withContext false
        val playbackStopped = stopPlaybackIfOwnedByRoot(root)
        clearRootResourcesBeforeCascade(root.id)
        libraryRootGateway.deleteLibraryRootDataOnly(root)
        playbackStopped
    }

    /**
     * Update ABS Library Root (Clean old mirrored resources before replacing the remote library)
     * When the selected ABS library changes, old mirrored books must lose cover files and manual-download records before
     * the data layer deletes their BookEntity and BookFileEntity rows.
     */
    suspend fun updateAbsLibraryRoot(
        id: String,
        credentialId: String,
        libraryId: String,
        displayName: String
    ): LibraryRootEntity = withContext(Dispatchers.IO) {
        val oldRoot = findRoot(id)
        if (oldRoot != null && oldRoot.basePath != libraryId) {
            stopPlaybackIfOwnedByRoot(oldRoot)
            clearRootResourcesBeforeCascade(oldRoot.id)
        }
        libraryRootGateway.updateAbsLibraryRoot(
            id = id,
            credentialId = credentialId,
            libraryId = libraryId,
            displayName = displayName
        )
    }

    private suspend fun findRoot(rootId: String): LibraryRootEntity? {
        return libraryRootGateway.getAllRootsOnce().firstOrNull { candidate -> candidate.id == rootId }
    }

    private suspend fun clearRootResourcesBeforeCascade(rootId: String) {
        libraryResourceCleanupGateway.clearRootDerivedCaches(rootId)
        bookRootInventoryGateway.getBookIdsByRootId(rootId).forEach { bookId ->
            manualDownloadCleanupGateway.deleteDownload(bookId)
        }
    }

    private suspend fun stopPlaybackIfOwnedByRoot(root: LibraryRootEntity): Boolean {
        return try {
            val currentBookId = playbackStopper.currentPlayingBookId ?: return false
            val currentBook = bookCatalogGateway.getBookById(currentBookId)
            if (currentBook?.rootId != root.id) return false
            val playbackStopped = playbackStopper.stopIfPlaying(currentBookId)
            if (playbackStopped) {
                LibraryWorkflowLogger.debug("manageRoot stopPlayback: rootId=${root.id}, currentBookId=$currentBookId")
                LibraryWorkflowLogger.info("manageRoot stopPlayback success: rootId=${root.id}")
            }
            playbackStopped
        } catch (e: Exception) {
            LibraryWorkflowLogger.warn("manageRoot stopPlayback failed: rootId=${root.id}", e)
            false
        }
    }
}
