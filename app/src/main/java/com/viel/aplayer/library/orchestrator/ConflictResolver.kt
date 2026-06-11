package com.viel.aplayer.library.orchestrator

import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.library.FileIdentity

/**
 * Ownership Conflict Resolver (Import domain policy)
 *
 * Owns the deterministic source-priority rule for file ownership conflicts.
 * The pipeline asks this resolver what to do; database mutation and draft construction stay outside this class.
 */
internal class ConflictResolver {

    /**
     * Resolve Manifest Ownership (CUE/M3U8 conflict policy)
     *
     * Applies cue > m3u8 > other for persisted owners, turns missing manifest references into PARTIAL imports,
     * and returns refresh/replace/skip commands without touching the database.
     */
    fun resolveManifestOwnership(
        source: ImportSourceRef,
        claimedIdentities: List<FileIdentity>,
        reservation: ReservationResult,
        existingClaimIndex: ExistingClaimIndex,
        missingCount: Int
    ): ConflictResolution {
        val bookStatus = if (missingCount > 0) {
            AudiobookSchema.BookStatus.PARTIAL
        } else {
            AudiobookSchema.BookStatus.READY
        }

        if (reservation.reserved) {
            return ConflictResolution.CreateBook(bookStatus)
        }

        // Run Conflict Guard (Uncommitted owner protection)
        // In-flight owners are not preempted here because their draft commands may already be queued outside the database boundary.
        if (reservation.runConflicts.isNotEmpty()) {
            return ConflictResolution.Skip
        }

        val completeClaim = existingClaimIndex.completeExistingClaim(claimedIdentities)
        if (completeClaim != null) {
            return if (missingCount == 0) {
                ConflictResolution.RefreshBook(
                    bookId = completeClaim.bookId,
                    files = completeClaim.files
                )
            } else {
                // Same-Owner Partial Replacement (Manifest drift handling)
                // Rebuilds the existing manifest book as PARTIAL when references disappear, preserving progress through replacement migration.
                ConflictResolution.ReplaceBooks(
                    bookIds = listOf(completeClaim.bookId),
                    bookStatus = bookStatus
                )
            }
        }

        val existingBookIds = reservation.existingConflicts.map { it.bookId }.distinct()
        if (existingBookIds.isEmpty()) {
            return ConflictResolution.Skip
        }

        val incomingPriority = sourcePriority(source.sourceType)
        val canReplacePersistedOwners = existingBookIds.all { bookId ->
            incomingPriority > sourcePriority(existingClaimIndex.sourceTypeForBook(bookId))
        }
        return if (canReplacePersistedOwners) {
            // Higher-Priority Replacement (Persisted owner reassignment)
            // Emits the obsolete owners to remove after the incoming manifest draft has been inserted and migrated.
            ConflictResolution.ReplaceBooks(
                bookIds = existingBookIds,
                bookStatus = bookStatus
            )
        } else {
            ConflictResolution.Skip
        }
    }

    // Source Priority Type Safe: Use AudiobookSchema.SourceType? enum for priority rank resolution.
    private fun sourcePriority(sourceType: AudiobookSchema.SourceType?): Int =
        when (sourceType) {
            AudiobookSchema.SourceType.CUE -> 3
            AudiobookSchema.SourceType.M3U8 -> 2
            else -> 1
        }
}

/**
 * Conflict Resolution Result (Import decision contract)
 *
 * Represents what the scan pipeline should emit after comparing an incoming owner with existing reservations.
 */
internal sealed interface ConflictResolution {
    /**
     * Create New Book (Uncontested ownership)
     *
     * Indicates that the incoming source owns its files and should be inserted as READY or PARTIAL.
     */
    // Create Book Type Safe: Use BookStatus enum instead of String for type safety.
    data class CreateBook(val bookStatus: AudiobookSchema.BookStatus) : ConflictResolution

    /**
     * Refresh Existing Book (Idempotent rescan)
     *
     * Indicates that all claimed files already belong to the same book and only visibility markers need refreshing.
     */
    data class RefreshBook(
        val bookId: String,
        val files: List<BookFileEntity>
    ) : ConflictResolution

    /**
     * Replace Existing Books (Priority reassignment)
     *
     * Indicates that lower-priority or stale same-owner rows should be removed after state migration.
     */
    // Replace Books Type Safe: Use BookStatus enum instead of String for type safety.
    data class ReplaceBooks(
        val bookIds: List<String>,
        val bookStatus: AudiobookSchema.BookStatus
    ) : ConflictResolution

    /**
     * Skip Incoming Source (Winner already exists)
     *
     * Indicates that an equal/higher-priority owner already holds the files, so no command should be emitted.
     */
    data object Skip : ConflictResolution
}
