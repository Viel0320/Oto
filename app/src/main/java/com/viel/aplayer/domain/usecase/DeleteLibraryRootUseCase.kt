// Package Relocation: Relocate components to the unified domain usecase package to keep architecture consistent.
package com.viel.aplayer.domain.usecase

import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.logger.LibraryWorkflowLogger
import com.viel.aplayer.media.PlaybackManager
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
    private val playbackManager: PlaybackManager,
    private val bookQueryGateway: com.viel.aplayer.data.gateway.BookQueryGateway,
    private val libraryRootGateway: com.viel.aplayer.data.gateway.LibraryRootGateway
) {

    /**
     * Execute Library Root Purging (Clean ingestion entries)
     * 
     * @param root Target LibraryRootEntity to remove
     * @return True if active playback was emergency stopped, false otherwise
     */
    suspend fun invoke(root: LibraryRootEntity): Boolean = withContext(Dispatchers.IO) {
        var playbackStopped = false

        // Retrieve active playback ID (Media player state lookup)
        // Queries the current playing audiobook ID from the playback domain.
        try {
            val currentBookId = playbackManager.currentPlayingBookId
            if (currentBookId != null) {
                // Query Active Audiobook Entity (Domain boundary crossing)
                // Queries target book details from bookQueryGateway to check if it belongs to the deleted root.
                val currentBook = bookQueryGateway.getBookById(currentBookId)
                if (currentBook != null && currentBook.rootId == root.id) {
                    // Halt Active Playback (Emergency state override)
                    // Triggers emergency stop commands if the book belongs to the target root, locking the stopped status.
                    playbackManager.stopPlayback()
                    playbackStopped = true
                    // Log Emergency Pause Actions (Process diagnostics logger)
                    // Logs focus loss and shutdown routines to the shared workflow log, rather than source-specific targets.
                    LibraryWorkflowLogger.debug("deleteRoot stopPlayback: rootId=${root.id}, currentBookId=$currentBookId")
                    LibraryWorkflowLogger.info("deleteRoot stopPlayback success: rootId=${root.id}")
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
}
