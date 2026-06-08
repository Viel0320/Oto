// Application Use Case Package (Keep root deletion orchestration outside data and UI layers)
// Places the deletion coordinator in the application boundary so presentation callers depend on root identifiers rather than persistence entities.
package com.viel.aplayer.application.usecase

import com.viel.aplayer.application.playback.PlaybackStopper
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.data.gateway.BookCatalogGateway
import com.viel.aplayer.data.gateway.LibraryRootGateway
import com.viel.aplayer.logger.LibraryWorkflowLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cross-Domain Coordination Use Case (DeleteLibraryRootUseCase)
 * Safely unloads and deletes a library root directory.
 * 
 * Core Responsibilities:
 * 1. Cross-Domain Coordination: Removing database records is a data domain responsibility, while pausing audio belongs to playback domains.
 *    These were originally coupled in the low-level BookLibraryRepository, causing circular dependencies.
 *    Moving coordination here to an application use case resolves dependencies cleanly.
 * 2. Safety Policy Execution:
 *    - Evaluates if the active audiobook belongs to the root designated for deletion.
 *    - If matched, stops playback immediately to avoid missing VFS handles, IO exceptions, or player crashes.
 *    - Invokes data-cleaning APIs to cascade delete files, permissions, and Room entities.
 */
class DeleteLibraryRootUseCase(
    private val playbackStopper: PlaybackStopper,
    private val bookCatalogGateway: BookCatalogGateway,
    private val libraryRootGateway: LibraryRootGateway
) {

    /**
     * Execute Resolved Root Purging (Clean ingestion entries after id resolution)
     *
     * Keeps the persisted root row private to the application use case after public callers provide only a stable root id.
     *
     * @param root Resolved persistence root row to remove
     * @return True if active playback was emergency stopped, false otherwise
     */
    private suspend fun deleteResolvedRoot(root: LibraryRootEntity): Boolean = withContext(Dispatchers.IO) {
        var playbackStopped = false

        // Retrieve active playback ID (Media player state lookup)
        // Queries only the current audiobook ID from the playback lifecycle seam before deciding whether root removal affects it.
        try {
            val currentBookId = playbackStopper.currentPlayingBookId
            if (currentBookId != null) {
                // Query Active Audiobook Entity (Catalog-only root ownership check)
                // The deletion use case reads only the active book row to decide whether the target root owns current playback.
                val currentBook = bookCatalogGateway.getBookById(currentBookId)
                if (currentBook != null && currentBook.rootId == root.id) {
                    // Halt Active Playback (Emergency state override through PlaybackStopper)
                    // Triggers stop commands only through the deletion-safe playback seam, keeping media runtime details outside the use case.
                    playbackStopped = playbackStopper.stopIfPlaying(currentBookId)
                    if (playbackStopped) {
                        // Log Emergency Pause Actions (Process diagnostics logger)
                        // Logs focus loss and shutdown routines only after the playback seam confirms a stop was performed.
                        LibraryWorkflowLogger.debug("deleteRoot stopPlayback: rootId=${root.id}, currentBookId=$currentBookId")
                        LibraryWorkflowLogger.info("deleteRoot stopPlayback success: rootId=${root.id}")
                    }
                }
            }
        } catch (e: Exception) {
            LibraryWorkflowLogger.warn("deleteRoot stopPlayback failed: rootId=${root.id}", e)
        }

        // Purge Root Cache and Database Entries (Cascade cleaning action)
        // Invokes root gateway to clear local cover caches, revoke SAF tree permissions, and delete database rows.
        libraryRootGateway.deleteLibraryRootDataOnly(root)

        playbackStopped
    }

    /**
     * Execute Library Root Purging by Id (Keep presentation callers on stable root identifiers)
     * Rehydrates the persisted root inside the application use case so settings UI can request deletion without carrying a Room entity across the scene boundary.
     */
    suspend fun invoke(rootId: String): Boolean = withContext(Dispatchers.IO) {
        val root = libraryRootGateway.getAllRootsOnce().firstOrNull { candidate -> candidate.id == rootId }
            ?: return@withContext false
        deleteResolvedRoot(root)
    }
}
